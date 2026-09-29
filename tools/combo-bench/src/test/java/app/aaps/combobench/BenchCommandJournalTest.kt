package app.aaps.combobench

import org.junit.Assert.*
import org.junit.Test

class BenchCommandJournalTest {
    private val phoneId = BenchIdentity("session", "phone", "watch", "test-pump")
    private val watchId = BenchIdentity("session", "watch", "phone", "test-pump")
    private val request = BenchCommandRequest("request-1", 0, "a".repeat(64))
    private var sent = 0

    private class MemoryOwnership : OwnershipStore {
        var data = OwnershipState("phone")
        var failCompletion = false
        override fun load() = data
        override fun save(state: OwnershipState) {
            check(!failCompletion || state.operation != null)
            data = state
        }
    }

    private class MemoryJournal(id: BenchIdentity) : BenchCommandStore {
        var data = BenchCommandSnapshot(id.session, id.pump, id.local)
        var failRead = false
        var failAtSave = 0
        var commitThenFail = false
        var saves = 0
        override fun load(): BenchCommandSnapshot { check(!failRead); return data }
        override fun save(snapshot: BenchCommandSnapshot) {
            saves++
            val failed = saves == failAtSave
            if (!failed || commitThenFail) data = snapshot.copy(records = snapshot.records.toMap())
            check(!failed)
        }
    }

    private val ownershipStore = MemoryOwnership()
    private var ownership = DiagnosticOwnership(phoneId, ownershipStore)
    private val store = MemoryJournal(phoneId)
    private var journal = BenchCommandJournal(phoneId, ownership, store)

    private fun restart() {
        ownership = DiagnosticOwnership(phoneId, ownershipStore)
        journal = BenchCommandJournal(phoneId, ownership, store)
    }

    private fun rejected(action: () -> Unit) { assertThrows(Exception::class.java) { action() } }
    private fun submit(command: BenchCommandRequest = request) = journal.executeSimulated(command) { sent++ }

    @Test fun operationAndRecordExistBeforeSimulatedTransportIsCalled() {
        journal.executeSimulated(request) {
            assertNotNull(ownership.state().operation)
            assertEquals(BenchCommandOutcome.SUBMITTED, store.data.records[request.id]?.outcome)
            rejected { ownership.transfer("handoff") }
            sent++
        }
        assertEquals(1, sent)
        assertEquals(BenchCommandOutcome.ACKNOWLEDGED, journal.snapshot().records[request.id]?.outcome)
        assertNull(ownership.state().operation)
    }

    @Test fun acknowledgedRequestCannotBeSentAgainAfterRestart() {
        submit()
        restart()
        rejected { submit() }
        rejected { submit(request.copy(fingerprint = "b".repeat(64))) }
        assertEquals(1, sent)
    }

    @Test fun lostAcknowledgementDoesNotMeanNothingWasSent() {
        rejected {
            journal.executeSimulated(request) { sent++; error("Connection lost after submission") }
        }
        assertEquals(BenchCommandOutcome.UNKNOWN, store.data.records[request.id]?.outcome)
        restart()
        rejected { submit() }
        rejected { submit(request.copy(id = "another-request")) }
        rejected { ownership.transfer("handoff") }
        rejected { journal.finishAcknowledged(request) }
        assertEquals(1, sent)
    }

    @Test fun processDeathAfterSendingRemainsUnresolvedOnRestart() {
        assertThrows(AssertionError::class.java) {
            journal.executeSimulated(request) { sent++; throw AssertionError("Simulated process death") }
        }
        restart()
        assertEquals(BenchCommandOutcome.SUBMITTED, journal.snapshot().records[request.id]?.outcome)
        rejected { submit() }
        rejected { ownership.transfer("handoff") }
        assertEquals(1, sent)
    }

    @Test fun failedInitialRecordWriteNeverCallsTransportAndKeepsOwnershipLocked() {
        store.failAtSave = 1
        rejected { submit() }
        assertEquals(0, sent)
        restart()
        rejected { submit() }
        rejected { ownership.transfer("handoff") }
    }

    @Test fun ambiguousInitialWriteCannotBeRetriedAfterRestart() {
        store.failAtSave = 1
        store.commitThenFail = true
        rejected { submit() }
        restart()
        rejected { submit() }
        assertEquals(0, sent)
    }

    @Test fun failedAcknowledgementWriteDoesNotPermitRetryOrHandoff() {
        store.failAtSave = 2
        rejected { submit() }
        restart()
        rejected { submit() }
        rejected { journal.finishAcknowledged(request) }
        rejected { ownership.transfer("handoff") }
        assertEquals(1, sent)
    }

    @Test fun persistedAcknowledgementCanFinishAfterCrashWithoutSendingAgain() {
        ownershipStore.failCompletion = true
        rejected { submit() }
        assertEquals(BenchCommandOutcome.ACKNOWLEDGED, store.data.records[request.id]?.outcome)
        ownershipStore.failCompletion = false
        restart()
        journal.finishAcknowledged(request)
        assertNull(ownership.state().operation)
        rejected { submit() }
        assertEquals(1, sent)
    }

    @Test fun alteredAcknowledgementCannotReleaseOperation() {
        ownershipStore.failCompletion = true
        rejected { submit() }
        ownershipStore.failCompletion = false
        restart()
        rejected { journal.finishAcknowledged(request.copy(fingerprint = "b".repeat(64))) }
        rejected { journal.finishAcknowledged(request.copy(generation = 1)) }
        assertNotNull(ownership.state().operation)
    }

    @Test fun missingOrWrongJournalCannotSilentlyStartAnEmptyHistory() {
        store.failRead = true
        rejected { submit() }
        store.failRead = false
        rejected { submit() }
        restart()
        store.data = store.data.copy(session = "other-session")
        rejected { submit() }
        assertEquals(0, sent)
    }

    @Test fun malformedRecordFailsClosed() {
        store.data = store.data.copy(records = mapOf("wrong-key" to BenchCommandRecord(request, BenchCommandOutcome.ACKNOWLEDGED)))
        rejected { submit(request.copy(id = "new")) }
        assertEquals(0, sent)
    }

    @Test fun unknownRecordBlocksEvenIfOwnershipLockWasLost() {
        store.data = store.data.copy(records = mapOf(request.id to BenchCommandRecord(request, BenchCommandOutcome.UNKNOWN)))
        rejected { submit(request.copy(id = "new")) }
        assertEquals(0, sent)
    }

    @Test fun generationChangeRejectsOldRequestEvenWhenPhoneOwnsAgain() {
        ownershipStore.data = OwnershipState("phone", generation = 2)
        rejected { submit() }
        assertEquals(0, sent)
        submit(request.copy(generation = 2))
        assertEquals(1, sent)
    }

    @Test fun disconnectedWatchCannotAcquireOwnershipByStartingACommand() {
        val watchOwner = DiagnosticOwnership(watchId, MemoryOwnership())
        val watchJournal = BenchCommandJournal(watchId, watchOwner, MemoryJournal(watchId))
        // There is deliberately no timeout/peer-offline path that manufactures a grant.
        rejected { watchJournal.executeSimulated(request) { sent++ } }
        assertEquals(0, sent)
        submit()
        assertEquals(1, sent)
    }

    @Test fun coordinatedHandoffAllowsNewWatchRequestButNotPhoneOrOldRequest() {
        submit()
        val watchStore = MemoryOwnership()
        val watchOwner = DiagnosticOwnership(watchId, watchStore)
        val watchJournal = BenchCommandJournal(watchId, watchOwner, MemoryJournal(watchId))
        val grant = ownership.transfer("handoff")
        watchOwner.accept("phone", grant)
        // An acknowledgement lost on the network does not return the phone's right.
        restart()
        rejected { submit(request.copy(id = "phone-retry")) }
        rejected { watchJournal.executeSimulated(request) { sent++ } }
        watchJournal.executeSimulated(request.copy(id = "new-watch-request", generation = grant.generation)) { sent++ }
        assertEquals(2, sent)
        assertEquals(1, watchJournal.snapshot().records.size)
    }

    @Test fun invalidRequestsNeverReachSimulatedTransport() {
        listOf(request.copy(id = ""), request.copy(generation = -1), request.copy(fingerprint = "not-a-digest")).forEach {
            rejected { submit(it) }
        }
        assertEquals(0, sent)
        assertNull(ownership.state().operation)
    }
}
