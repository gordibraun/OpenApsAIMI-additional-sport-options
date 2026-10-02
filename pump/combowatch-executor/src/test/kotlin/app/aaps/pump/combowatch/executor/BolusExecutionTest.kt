package app.aaps.pump.combowatch.executor

import app.aaps.pump.combowatch.protocol.BolusKind
import app.aaps.pump.combowatch.protocol.BolusReceipt
import app.aaps.pump.combowatch.protocol.ComboCommand
import app.aaps.pump.combowatch.protocol.CommandKind
import app.aaps.pump.combowatch.protocol.ControlLease
import app.aaps.pump.combowatch.protocol.Outcome
import app.aaps.pump.combowatch.protocol.PumpSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A bolus cannot be taken back, so these pin down the one thing that matters most: an unclear
 * ending never turns into a second delivery, and it is the pump's own history that settles it.
 */
class BolusExecutionTest {

    private var now = 1_000_000L

    private class RecordingSession(private val answer: (ComboCommand) -> PumpSession.SessionResult) : PumpSession {

        val runs = mutableListOf<String>()
        override fun run(command: ComboCommand): PumpSession.SessionResult {
            runs.add(command.id)
            return answer(command)
        }
    }

    private fun lease() = ControlLease(7L, now - 1_000, now + 60_000, "10392647", controllerIsWatch = true)

    private fun bolus(id: String = "b1", tenths: Int? = 3, kind: BolusKind? = BolusKind.SMB) =
        ComboCommand(id, 7L, CommandKind.DELIVER_BOLUS, now, now + 30_000, bolusTenthsIU = tenths, bolusKind = kind)

    private fun snapshot() = PumpSnapshot(now, tbrRunning = false, null, null, 90, "FULL_BATTERY", "10392647")

    private fun receipt(tenths: Int = 3, at: Long = now + 5_000) = BolusReceipt(bolusId = 42L, timestampEpochMs = at, tenthsIU = tenths)

    private fun gate(max: Int = 30) = CommandGate({ now }, { max })

    @Test fun `a microbolus within the watch's limit is delivered and its receipt reported`() {
        val session = RecordingSession { PumpSession.SessionResult.Done(snapshot(), bolus = receipt()) }
        val executor = ComboExecutor(gate(), SimpleCommandJournal(), session) { now }

        val result = executor.execute(bolus(), lease())

        assertEquals(Outcome.DONE, result.outcome)
        assertEquals(3, result.bolus?.tenthsIU)
        assertEquals(42L, result.bolus?.bolusId)
    }

    @Test fun `a bolus above the watch's own limit never reaches the pump`() {
        val session = RecordingSession { PumpSession.SessionResult.Done(snapshot(), bolus = receipt(40)) }
        val executor = ComboExecutor(gate(max = 30), SimpleCommandJournal(), session) { now }

        val result = executor.execute(bolus(tenths = 40), lease())

        assertEquals(Outcome.REFUSED, result.outcome)
        assertTrue(result.reason!!.contains("limit"))
        assertTrue(session.runs.isEmpty())
    }

    @Test fun `malformed bolus requests are refused without a session`() {
        val g = gate()
        val journal = SimpleCommandJournal()
        for (bad in listOf(bolus(tenths = null), bolus(tenths = 0), bolus(tenths = -3), bolus(kind = null)))
            assertInstanceOf(CommandGate.Admission.Refused::class.java, g.admit(bad, lease(), journal, busy = false, awaitingReconciliation = false))
    }

    @Test fun `resending a delivered bolus does not deliver it again`() {
        val session = RecordingSession { PumpSession.SessionResult.Done(snapshot(), bolus = receipt()) }
        val executor = ComboExecutor(gate(), SimpleCommandJournal(), session) { now }

        executor.execute(bolus(), lease())
        val again = executor.execute(bolus(), lease())

        assertEquals(Outcome.DONE, again.outcome)
        assertEquals(listOf("b1"), session.runs, "insulin must be delivered once, not twice")
    }

    @Test fun `a link lost mid-bolus blocks the next bolus instead of repeating it`() {
        val session = RecordingSession { throw IllegalStateException("link lost") }
        val executor = ComboExecutor(gate(), SimpleCommandJournal(), session) { now }

        val first = executor.execute(bolus("b1"), lease())
        assertEquals(Outcome.UNKNOWN, first.outcome)

        val second = executor.execute(bolus("b2"), lease())
        assertEquals(Outcome.REFUSED, second.outcome)
        assertEquals(listOf("b1"), session.runs)
    }

    @Test fun `the pump's history settles an unclear bolus as delivered`() {
        val journal = SimpleCommandJournal()
        val session = RecordingSession { PumpSession.SessionResult.Unknown("link lost") }
        val executor = ComboExecutor(gate(), journal, session) { now }
        executor.execute(bolus("b1"), lease())
        assertTrue(executor.awaitingReconciliation)

        // On reconnect the driver finds the bolus in the pump's history.
        executor.reconcile(snapshot(), bolusesInHistory = listOf(receipt(tenths = 3, at = now + 4_000)))

        assertFalse(executor.awaitingReconciliation)
        assertEquals(Outcome.DONE, journal.entry("b1")?.outcome)
    }

    @Test fun `the pump's history settles an unclear bolus as not delivered`() {
        val journal = SimpleCommandJournal()
        val session = RecordingSession { PumpSession.SessionResult.Unknown("link lost") }
        val executor = ComboExecutor(gate(), journal, session) { now }
        executor.execute(bolus("b1"), lease())

        executor.reconcile(snapshot(), bolusesInHistory = emptyList())

        assertFalse(executor.awaitingReconciliation)
        assertEquals(Outcome.FAILED, journal.entry("b1")?.outcome)
    }

    @Test fun `an older bolus in the history is not mistaken for the unclear one`() {
        val journal = SimpleCommandJournal()
        val session = RecordingSession { PumpSession.SessionResult.Unknown("link lost") }
        val executor = ComboExecutor(gate(), journal, session) { now }
        executor.execute(bolus("b1"), lease())

        // Delivered an hour before the command was even issued.
        executor.reconcile(snapshot(), bolusesInHistory = listOf(receipt(at = now - 3_600_000)))

        assertEquals(Outcome.FAILED, journal.entry("b1")?.outcome)
    }

    @Test fun `a bolus cut short reports what the pump actually delivered`() {
        val session = RecordingSession {
            PumpSession.SessionResult.Failed("bolus aborted", snapshot(), bolus = receipt(tenths = 1))
        }
        val executor = ComboExecutor(gate(), SimpleCommandJournal(), session) { now }

        val result = executor.execute(bolus(tenths = 3), lease())

        assertEquals(Outcome.FAILED, result.outcome)
        assertEquals(1, result.bolus?.tenthsIU)
    }

    @Test fun `an unclear temporary basal is settled against the pump's screen`() {
        val journal = SimpleCommandJournal()
        val session = RecordingSession { PumpSession.SessionResult.Unknown("link lost") }
        val executor = ComboExecutor(gate(), journal, session) { now }
        val stop = ComboCommand("t1", 7L, CommandKind.SET_TBR, now, now + 30_000, percentage = 0, durationMinutes = 30)
        executor.execute(stop, lease())

        executor.reconcile(PumpSnapshot(now, tbrRunning = true, 0, 29, 90, "FULL_BATTERY", "10392647"))
        assertEquals(Outcome.DONE, journal.entry("t1")?.outcome)
    }

    @Test fun `an unclear temporary basal that did not take is settled as failed`() {
        val journal = SimpleCommandJournal()
        val session = RecordingSession { PumpSession.SessionResult.Unknown("link lost") }
        val executor = ComboExecutor(gate(), journal, session) { now }
        val stop = ComboCommand("t1", 7L, CommandKind.SET_TBR, now, now + 30_000, percentage = 0, durationMinutes = 30)
        executor.execute(stop, lease())

        executor.reconcile(snapshot())
        assertEquals(Outcome.FAILED, journal.entry("t1")?.outcome)
    }
}

class SelfHealingTest {

    private var now = 1_000_000L

    private fun lease() = app.aaps.pump.combowatch.protocol.ControlLease(7L, now - 1_000, now + 60_000, "10392647", controllerIsWatch = true)
    private fun snapshot() = app.aaps.pump.combowatch.protocol.PumpSnapshot(now, tbrRunning = false, null, null, 90, "FULL_BATTERY", "10392647")

    @org.junit.jupiter.api.Test fun `reading the pump at the start of a command is not evidence about that command`() {
        // The session reads the pump as it connects and feeds reconcile(); the running command
        // itself must not be settled by that read.
        val journal = SimpleCommandJournal()
        lateinit var executor: ComboExecutor
        val session = object : PumpSession {
            override fun run(command: app.aaps.pump.combowatch.protocol.ComboCommand): PumpSession.SessionResult {
                executor.reconcile(snapshot(), emptyList())
                return PumpSession.SessionResult.Done(snapshot(), bolus = app.aaps.pump.combowatch.protocol.BolusReceipt(1L, now, 3))
            }
        }
        executor = ComboExecutor(CommandGate({ now }), journal, session) { now }
        val bolus = app.aaps.pump.combowatch.protocol.ComboCommand(
            "b1", 7L, app.aaps.pump.combowatch.protocol.CommandKind.DELIVER_BOLUS, now, now + 30_000,
            bolusTenthsIU = 3, bolusKind = app.aaps.pump.combowatch.protocol.BolusKind.SMB
        )
        val result = executor.execute(bolus, lease())
        org.junit.jupiter.api.Assertions.assertEquals(app.aaps.pump.combowatch.protocol.Outcome.DONE, result.outcome)
        org.junit.jupiter.api.Assertions.assertEquals(app.aaps.pump.combowatch.protocol.Outcome.DONE, journal.entry("b1")?.outcome)
    }

    @org.junit.jupiter.api.Test fun `an unclear ending heals itself by reading the pump, without being asked`() {
        val journal = SimpleCommandJournal()
        lateinit var executor: ComboExecutor
        var answer: () -> PumpSession.SessionResult = { PumpSession.SessionResult.Unknown("link lost") }
        val runs = mutableListOf<app.aaps.pump.combowatch.protocol.CommandKind>()
        val session = object : PumpSession {
            override fun run(command: app.aaps.pump.combowatch.protocol.ComboCommand): PumpSession.SessionResult {
                runs.add(command.kind)
                return answer()
            }
        }
        executor = ComboExecutor(CommandGate({ now }), journal, session) { now }
        val stop = app.aaps.pump.combowatch.protocol.ComboCommand(
            "t1", 7L, app.aaps.pump.combowatch.protocol.CommandKind.SET_TBR, now, now + 30_000, percentage = 0, durationMinutes = 30
        )
        executor.execute(stop, lease())
        org.junit.jupiter.api.Assertions.assertTrue(executor.awaitingReconciliation)

        // The self-initiated read: the session's own connect feeds reconcile().
        answer = {
            executor.reconcile(app.aaps.pump.combowatch.protocol.PumpSnapshot(now, true, 0, 29, 90, "FULL_BATTERY", "10392647"))
            PumpSession.SessionResult.Done(snapshot())
        }
        org.junit.jupiter.api.Assertions.assertTrue(executor.reconcileNow())
        org.junit.jupiter.api.Assertions.assertFalse(executor.awaitingReconciliation)
        // Only a read was issued for the healing; nothing was changed on the pump.
        org.junit.jupiter.api.Assertions.assertEquals(app.aaps.pump.combowatch.protocol.CommandKind.STATUS, runs.last())
    }
}
