package app.aaps.pump.combowatch.executor

import app.aaps.pump.combowatch.protocol.ComboCommand
import app.aaps.pump.combowatch.protocol.CommandKind
import app.aaps.pump.combowatch.protocol.ControlLease
import app.aaps.pump.combowatch.protocol.Outcome
import app.aaps.pump.combowatch.protocol.PumpSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The invariant under test throughout: a given command id touches the pump at most once, and an
 * ending the executor could not read back holds everything else until it does.
 */
class ComboExecutorTest {

    private var now = 1_000_000L

    private class RecordingSession(
        private val answer: (ComboCommand) -> PumpSession.SessionResult
    ) : PumpSession {

        val runs = mutableListOf<String>()
        override fun run(command: ComboCommand): PumpSession.SessionResult {
            runs.add(command.id)
            return answer(command)
        }
    }

    private fun lease(generation: Long = 7L) =
        ControlLease(generation, now - 1_000, now + 60_000, "10392647", controllerIsWatch = true)

    private fun command(id: String = "c1", kind: CommandKind = CommandKind.SET_TBR) =
        ComboCommand(id, 7L, kind, now, now + 30_000, percentage = 0, durationMinutes = 30)

    private fun snapshot(tbrRunning: Boolean = true, percentage: Int? = 0) =
        PumpSnapshot(now, tbrRunning, percentage, 30, 95, "FULL_BATTERY", "10392647")

    private fun executor(session: PumpSession, journal: CommandJournal = SimpleCommandJournal()) =
        Triple(ComboExecutor(CommandGate({ now }), journal, session, { now }), journal, session)

    @Test fun `a successful stop is recorded as done and reports the pump it read back`() {
        val session = RecordingSession { PumpSession.SessionResult.Done(snapshot()) }
        val (executor, journal, _) = executor(session)

        val result = executor.execute(command(), lease())

        assertEquals(Outcome.DONE, result.outcome)
        assertEquals(0, result.snapshot?.tbrPercentage)
        assertFalse(journal.hasUnresolved())
        assertEquals(listOf("c1"), session.runs)
    }

    @Test fun `resending the same id never reaches the pump a second time`() {
        val session = RecordingSession { PumpSession.SessionResult.Done(snapshot()) }
        val (executor, _, _) = executor(session)

        val first = executor.execute(command(), lease())
        val second = executor.execute(command(), lease())

        assertEquals(Outcome.DONE, first.outcome)
        assertEquals(Outcome.DONE, second.outcome)
        assertEquals(listOf("c1"), session.runs, "the pump must be touched once, not twice")
    }

    @Test fun `a refusal is sticky, so a late copy cannot run after the phone moved on`() {
        val session = RecordingSession { PumpSession.SessionResult.Done(snapshot()) }
        val (executor, _, _) = executor(session)

        // Refused: no lease at all.
        val refused = executor.execute(command(), lease = null)
        assertEquals(Outcome.REFUSED, refused.outcome)

        // The same id arrives again, now with a perfectly good lease. It must still not run.
        val resent = executor.execute(command(), lease())
        assertEquals(Outcome.REFUSED, resent.outcome)
        assertTrue(session.runs.isEmpty())
    }

    @Test fun `a session that throws ends unknown, not failed`() {
        val session = RecordingSession { throw IllegalStateException("link died") }
        val (executor, journal, _) = executor(session)

        val result = executor.execute(command(), lease())

        assertEquals(Outcome.UNKNOWN, result.outcome)
        assertEquals("link died", result.reason)
        assertTrue(journal.hasUnresolved())
    }

    @Test fun `an unknown ending blocks the next therapy command until the pump is read back`() {
        val session = RecordingSession { PumpSession.SessionResult.Unknown("link lost mid-command") }
        val (executor, _, _) = executor(session)
        executor.execute(command("c1"), lease())
        assertTrue(executor.awaitingReconciliation)

        val blocked = executor.execute(command("c2"), lease())
        assertEquals(Outcome.REFUSED, blocked.outcome)
        assertTrue(blocked.reason!!.contains("reconciliation"))
        assertEquals(listOf("c1"), session.runs)
    }

    @Test fun `reading the pump back clears the block and lets therapy resume`() {
        var answer: PumpSession.SessionResult = PumpSession.SessionResult.Unknown("link lost")
        val session = RecordingSession { answer }
        val (executor, _, _) = executor(session)
        executor.execute(command("c1"), lease())
        assertTrue(executor.awaitingReconciliation)

        // A read is the one thing still allowed, and what it sees is what resolves the record.
        answer = PumpSession.SessionResult.Done(snapshot(tbrRunning = true, percentage = 0))
        val status = executor.execute(command("c2", CommandKind.STATUS), lease())
        assertEquals(Outcome.DONE, status.outcome)
        executor.reconcile(status.snapshot!!)

        assertFalse(executor.awaitingReconciliation)
        val afterwards = executor.execute(command("c3"), lease())
        assertEquals(Outcome.DONE, afterwards.outcome)
    }

    @Test fun `a failure that did not change delivery does not block anything`() {
        val session = RecordingSession { PumpSession.SessionResult.Failed("pump left the screen") }
        val (executor, _, _) = executor(session)

        val failed = executor.execute(command("c1"), lease())
        assertEquals(Outcome.FAILED, failed.outcome)
        assertFalse(executor.awaitingReconciliation)

        val next = executor.execute(command("c2"), lease())
        assertEquals(Outcome.FAILED, next.outcome)
        assertEquals(listOf("c1", "c2"), session.runs)
    }

    @Test fun `the journal says started before the pump is touched`() {
        val seen = mutableListOf<Boolean>()
        val journal = SimpleCommandJournal()
        val session = RecordingSession {
            // What a crash at this instant would leave behind.
            seen.add(journal.hasUnresolved())
            PumpSession.SessionResult.Done(snapshot())
        }
        ComboExecutor(CommandGate({ now }), journal, session, { now }).execute(command(), lease())
        assertEquals(listOf(true), seen)
    }

    @Test fun `a command that outlived its deadline is refused without a session`() {
        val session = RecordingSession { PumpSession.SessionResult.Done(snapshot()) }
        val (executor, _, _) = executor(session)

        val late = ComboCommand("c1", 7L, CommandKind.SET_TBR, now - 300_000, now - 1, 0, 30)
        val result = executor.execute(late, lease())

        assertEquals(Outcome.REFUSED, result.outcome)
        assertTrue(result.reason!!.contains("expired"))
        assertTrue(session.runs.isEmpty())
    }
}
