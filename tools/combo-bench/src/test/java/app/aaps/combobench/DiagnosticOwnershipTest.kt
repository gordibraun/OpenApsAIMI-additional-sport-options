package app.aaps.combobench

import org.junit.Assert.*
import org.junit.Test

class DiagnosticOwnershipTest {
    private val phoneId = BenchIdentity("session", "phone", "watch", "test-pump")
    private val watchId = BenchIdentity("session", "watch", "phone", "test-pump")

    private class MemoryStore(var value: OwnershipState = OwnershipState("phone")) : OwnershipStore {
        var failRead = false
        var failWrite = false
        var commitThenFail = false
        override fun load(): OwnershipState {
            check(!failRead)
            return value
        }
        override fun save(state: OwnershipState) {
            check(!failWrite)
            value = state
            check(!commitThenFail)
        }
    }

    private val phoneStore = MemoryStore()
    private val watchStore = MemoryStore()
    private var phone = DiagnosticOwnership(phoneId, phoneStore)
    private var watch = DiagnosticOwnership(watchId, watchStore)
    private fun rejected(action: () -> Unit) { assertThrows(Exception::class.java) { action() } }

    @Test fun initialOwnerOnlyCanStart() {
        rejected { watch.beginOperation("x") }
        phone.beginOperation("x")
        assertEquals("x", phoneStore.value.operation)
    }

    @Test fun revokeIsDurableBeforeGrantLeavesTransfer() {
        val grant = phone.transfer("g")
        assertEquals(grant, phoneStore.value.outbox)
        assertEquals("watch", phoneStore.value.owner)
        rejected { phone.beginOperation("x") }
        rejected { watch.beginOperation("x") }
        watch.accept("phone", grant)
        watch.beginOperation("x")
    }

    @Test fun droppedGrantDoesNotRestoreOldOwnerOnRestart() {
        phone.transfer("g")
        phone = DiagnosticOwnership(phoneId, phoneStore)
        rejected { phone.beginOperation("x") }
        rejected { watch.beginOperation("x") }
        assertEquals("g", phone.state().outbox!!.id)
    }

    @Test fun lostAckCanBeRecoveredByRepeatingIdenticalGrant() {
        val grant = phone.transfer("g")
        watch.accept("phone", grant)
        watch = DiagnosticOwnership(watchId, watchStore)
        watch.accept("phone", grant)
        phone.acknowledge("watch", grant)
        assertNull(phone.state().outbox)
        assertEquals("watch", phone.state().owner)
    }

    @Test fun reverseHandoffDoesNotNeedLostAckFromPreviousHandoff() {
        val first = phone.transfer("a")
        watch.accept("phone", first)
        val second = watch.transfer("b")
        phone.accept("watch", second)
        phone.acknowledge("watch", first)
        assertEquals("phone", phone.state().owner)
        assertEquals(2, phone.state().generation)
        rejected { watch.accept("phone", first) }
    }

    @Test fun replayWithChangedBodyIsNotAnIdempotentGrant() {
        val grant = phone.transfer("g")
        watch.accept("phone", grant)
        rejected { watch.accept("phone", grant.copy(id = "different")) }
    }

    @Test fun unrelatedSenderIsRejected() {
        val grant = phone.transfer("g")
        rejected { watch.accept("other", grant) }
        rejected { phone.acknowledge("other", grant) }
    }

    @Test fun OtherSessionPumpOrRecipientIsRejected() {
        val grant = phone.transfer("g")
        rejected { watch.accept("phone", grant.copy(session = "old")) }
        rejected { watch.accept("phone", grant.copy(pump = "other")) }
        rejected { watch.accept("phone", grant.copy(to = "other")) }
        rejected { watch.accept("phone", grant.copy(from = "other")) }
        assertEquals(0, watch.state().generation)
    }

    @Test fun skippedAndInvalidGenerationsAreRejected() {
        val grant = phone.transfer("g")
        rejected { watch.accept("phone", grant.copy(generation = 2)) }
        rejected { watch.accept("phone", grant.copy(previousGeneration = -1, generation = 0)) }
        rejected { watch.accept("phone", grant.copy(previousGeneration = 2, generation = 3)) }
    }

    @Test fun handoffAndSecondOperationAreBlockedWhileOperationIsRunning() {
        phone.beginOperation("x")
        rejected { phone.transfer("g") }
        rejected { phone.beginOperation("y") }
        rejected { phone.completeOperation("y") }
        phone.completeOperation("x")
        phone.transfer("g")
    }

    @Test fun restartWithUnfinishedOperationDoesNotAssumeSuccess() {
        phone.beginOperation("x")
        phone = DiagnosticOwnership(phoneId, phoneStore)
        rejected { phone.beginOperation("y") }
        rejected { phone.transfer("g") }
        assertEquals("x", phone.state().operation)
    }

    @Test fun failedRevocationDoesNotReturnGrantAndLatchesLock() {
        phoneStore.failWrite = true
        rejected { phone.transfer("g") }
        phoneStore.failWrite = false
        rejected { phone.beginOperation("x") }
        assertEquals("phone", phoneStore.value.owner)
    }

    @Test fun ambiguousSuccessfulWriteStaysRevokedAfterRestart() {
        phoneStore.commitThenFail = true
        rejected { phone.transfer("g") }
        phoneStore.commitThenFail = false
        phone = DiagnosticOwnership(phoneId, phoneStore)
        rejected { phone.beginOperation("x") }
        watch.accept("phone", phone.state().outbox!!)
        watch.beginOperation("x")
    }

    @Test fun failedAcceptanceLeavesBothBlockedUntilExplicitRetryAfterRestart() {
        val grant = phone.transfer("g")
        watchStore.failWrite = true
        rejected { watch.accept("phone", grant) }
        watchStore.failWrite = false
        rejected { watch.beginOperation("x") }
        rejected { phone.beginOperation("x") }
        watch = DiagnosticOwnership(watchId, watchStore)
        watch.accept("phone", grant)
        watch.beginOperation("x")
    }

    @Test fun failedOperationPersistenceCannotStartAnOperation() {
        phoneStore.failWrite = true
        rejected { phone.beginOperation("x") }
        assertNull(phoneStore.value.operation)
    }

    @Test fun corruptOrUnreadableStoreCannotInventAnOwner() {
        phoneStore.value = OwnershipState("stranger")
        rejected { phone.state() }
        phoneStore.value = OwnershipState("phone")
        rejected { phone.beginOperation("x") }
        watchStore.failRead = true
        rejected { watch.state() }
    }

    @Test fun wrongAcknowledgementCannotRestoreOrChangeOwnership() {
        val grant = phone.transfer("g")
        phone.acknowledge("watch", grant.copy(id = "other"))
        assertEquals(grant, phone.state().outbox)
        assertEquals("watch", phone.state().owner)
    }

    @Test fun counterOverflowDoesNotRevokeOrWrap() {
        phoneStore.value = OwnershipState("phone", Long.MAX_VALUE)
        rejected { phone.transfer("g") }
        assertEquals(Long.MAX_VALUE, phone.state().generation)
        assertEquals("phone", phone.state().owner)
    }

    @Test fun repeatedBidirectionalTransfersNeverHaveTwoOwners() {
        repeat(100) { i ->
            val sender = if (i % 2 == 0) phone else watch
            val receiver = if (i % 2 == 0) watch else phone
            val source = if (i % 2 == 0) "phone" else "watch"
            val target = if (i % 2 == 0) "watch" else "phone"
            val grant = sender.transfer("grant-$i")
            assertFalse(phone.state().owner == "phone" && watch.state().owner == "watch")
            receiver.accept(source, grant)
            receiver.accept(source, grant)
            sender.acknowledge(target, grant)
            assertFalse(phone.state().owner == "phone" && watch.state().owner == "watch")
            receiver.beginOperation("probe-$i")
            receiver.completeOperation("probe-$i")
            phone = DiagnosticOwnership(phoneId, phoneStore)
            watch = DiagnosticOwnership(watchId, watchStore)
        }
        assertEquals(100, phone.state().generation)
        assertEquals(100, watch.state().generation)
    }
}
