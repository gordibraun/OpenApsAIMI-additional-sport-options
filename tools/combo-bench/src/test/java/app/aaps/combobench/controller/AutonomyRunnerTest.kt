package app.aaps.combobench.controller

import app.aaps.combobench.JsonFiles
import app.aaps.pump.combowatch.executor.AutonomyPolicy
import app.aaps.pump.combowatch.executor.ComboExecutor
import app.aaps.pump.combowatch.executor.CommandGate
import app.aaps.pump.combowatch.executor.PumpSession
import app.aaps.pump.combowatch.executor.SimpleCommandJournal
import app.aaps.pump.combowatch.protocol.ComboCommand
import app.aaps.pump.combowatch.protocol.CommandKind
import app.aaps.pump.combowatch.protocol.ControlLease
import app.aaps.pump.combowatch.protocol.PumpEvent
import app.aaps.pump.combowatch.protocol.RegulationSnapshot
import app.aaps.pump.combowatch.regulation.GlucoseReading
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole of the watch's own path, from a sensor reading to the pump, with the real executor,
 * gate and journal and a pump that only records what it is told.
 */
class AutonomyRunnerTest {

    private class MemoryFiles : JsonFiles {
        val content = HashMap<String, String>()
        override fun exists(name: String) = content.containsKey(name)
        override fun read(name: String) = JSONObject(content.getValue(name))
        override fun write(name: String, value: JSONObject) { content[name] = value.toString() }
    }

    private var now = 1_800_000_000_000L
    private val pump = "PUMP_41056642"
    private val store = AutonomyStore(MemoryFiles()) { now }
    private val sent = mutableListOf<ComboCommand>()
    private val notes = mutableListOf<String>()
    private val carbs = mutableListOf<Int>()
    private val carbsWhy = mutableListOf<String>()
    private var phoneLease: ControlLease? = null
    private var phoneHeard = 0L
    private var held: String? = pump
    private var pumpAnswers: (ComboCommand) -> PumpSession.SessionResult = { PumpSession.SessionResult.Done(null) }
    private var benchUsesThePump = false

    private lateinit var runner: AutonomyRunner

    /** A pump that does what it is told and reports it the way the driver does. */
    private val session = object : PumpSession {
        override fun run(command: ComboCommand): PumpSession.SessionResult {
            sent += command
            val answer = pumpAnswers(command)
            if (answer is PumpSession.SessionResult.Done && command.kind == CommandKind.SET_TBR) {
                store.onPumpEvent(PumpEvent(0, PumpEvent.Type.TBR_ENDED, now), runner.ownCommandInFlight)
                store.onPumpEvent(
                    PumpEvent(0, PumpEvent.Type.TBR_STARTED, now, tbrPercentage = command.percentage, tbrDurationMinutes = command.durationMinutes),
                    runner.ownCommandInFlight
                )
            }
            return answer
        }
    }
    private val executor = ComboExecutor(CommandGate({ now }, heldPump = { held }), SimpleCommandJournal(), session) { now }

    init {
        runner = AutonomyRunner(
            store = store, executor = executor,
            phoneLease = { phoneLease }, phoneLastHeardEpochMs = { phoneHeard }, heldPump = { held },
            pumpBasalUph = { List(24) { 1.2 } },
            note = { text, _ -> notes += text }, askForCarbs = { grams, why -> carbs += grams; carbsWhy += why },
            pumpInOtherUse = { benchUsesThePump },
            hourOfDay = { 12 }, nowEpochMs = { now }
        )
    }

    private fun minutes(n: Int) = n * 60_000L

    /** The phone was in charge and has been silent for [silentMinutes]. */
    private fun phoneLeft(silentMinutes: Int = 12) {
        phoneHeard = now - minutes(silentMinutes)
        phoneLease = ControlLease(5L, phoneHeard, phoneHeard + minutes(10), pump, true)
        store.saveSnapshot(
            RegulationSnapshot(
                madeAtEpochMs = phoneHeard, pumpSerial = pump, validUntilEpochMs = phoneHeard + minutes(24 * 60),
                targetMgdl = 117.0, hypoThresholdMgdl = 70.0, sensitivityMgdlPerU = 50.0, carbRatioGPerU = 10.0,
                cobG = 0.0, iobU = 0.0, insulinActivity = List(48) { 0.0 },
                insulinRemaining = List(193) { (1.0 - it * 2.5 / 300.0).coerceIn(0.0, 1.0) }
            )
        )
    }

    private fun glucose(last: Double, per5: Double) {
        for (step in 8 downTo 0) store.addReading(GlucoseReading(now - minutes(step * 5), last - per5 * step))
    }

    @Test fun whileThePhoneIsInChargeNothingIsComputedAtAll() {
        phoneLeft(silentMinutes = 3)
        store.setMode(AutonomyPolicy.Mode.ACTIVE)
        glucose(150.0, -8.0)
        assertFalse(runner.wantsToRun())
        assertNull(runner.onReading())
        assertTrue(store.journal().isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test fun switchedOffTheWatchDoesNothingEvenAlone() {
        phoneLeft()
        store.setMode(AutonomyPolicy.Mode.OFF)
        glucose(150.0, -8.0)
        assertFalse(runner.wantsToRun())
        assertNull(runner.onReading())
        assertTrue(sent.isEmpty())
    }

    @Test fun observingItWritesDownWhatItWouldDoAndLeavesThePumpAlone() {
        phoneLeft()
        glucose(150.0, -8.0)
        assertEquals(AutonomyPolicy.Mode.OBSERVE, store.mode())
        assertTrue(runner.wantsToRun())

        val entry = runner.onReading()!!
        assertEquals("FAST_FALL", entry.getString("rule"))
        assertEquals("TBR 0 % 30 min", entry.getString("action"))
        assertFalse(entry.getBoolean("done"))
        assertTrue(sent.isEmpty())
        assertEquals(1, store.journal().size)
        assertEquals(1, notes.size)
        assertTrue(notes.single().contains("наблюдение"))

        // Five minutes later the same finding is journalled again but not sent to the phone again.
        now += minutes(5)
        store.addReading(GlucoseReading(now, 142.0))
        runner.onReading()
        assertEquals(2, store.journal().size)
        assertEquals(1, notes.size)
    }

    @Test fun workingItStopsBasalThroughTheSameExecutorAsAPhoneCommand() {
        phoneLeft()
        store.setMode(AutonomyPolicy.Mode.ACTIVE)
        glucose(150.0, -8.0)

        val entry = runner.onReading()!!
        assertTrue(entry.getBoolean("done"))
        val command = sent.single()
        assertEquals(CommandKind.SET_TBR, command.kind)
        assertEquals(0, command.percentage)
        assertEquals(30, command.durationMinutes)
        assertNull(command.bolusTenthsIU)
        // The pump's record now shows the stop as the watch's own.
        assertEquals(0, store.delivery().percentAt(now + 1_000))
        assertTrue(store.delivery().tbrAt(now + 1_000)!!.byWatch)
        assertTrue(notes.single().startsWith("Часы без телефона: "))
        assertFalse(notes.single().contains("наблюдение"))

        // Next reading, still falling: the stop runs, nothing more is sent.
        now += minutes(5)
        store.addReading(GlucoseReading(now, 142.0))
        assertEquals("LEAVE", runner.onReading()!!.getString("action"))
        assertEquals(1, sent.size)
    }

    @Test fun aCommandThePumpDidNotTakeIsSaidSo() {
        phoneLeft()
        store.setMode(AutonomyPolicy.Mode.ACTIVE)
        glucose(150.0, -8.0)
        pumpAnswers = { PumpSession.SessionResult.Failed("pump not reached: out of range") }

        val entry = runner.onReading()!!
        assertFalse(entry.getBoolean("done"))
        assertEquals("FAILED", entry.getString("outcome"))
        assertTrue(notes.single().contains("не выполнено"))
        assertEquals(100, store.delivery().percentAt(now + 1_000))
    }

    @Test fun anUnclearEndingStopsTheWatchUntilThePumpHasBeenReadBack() {
        phoneLeft()
        store.setMode(AutonomyPolicy.Mode.ACTIVE)
        glucose(150.0, -8.0)
        pumpAnswers = { PumpSession.SessionResult.Unknown("link lost") }
        runner.onReading()
        assertEquals(1, sent.size)

        // The same executor rule that binds the phone binds the watch: nothing on top of an unknown.
        now += minutes(5)
        store.addReading(GlucoseReading(now, 142.0))
        assertFalse(runner.wantsToRun())
        assertNull(runner.onReading())
        assertEquals(1, sent.size)
    }

    @Test fun aloneWithAnUnclearEndingTheWatchAsksForThePumpToBeReadBack() {
        phoneLeft()
        store.setMode(AutonomyPolicy.Mode.ACTIVE)
        glucose(150.0, -8.0)
        assertFalse(runner.needsSettling())
        pumpAnswers = { PumpSession.SessionResult.Unknown("link lost") }
        runner.onReading()

        // Not free to decide, but the one thing that would free it is the watch's to do.
        assertFalse(runner.wantsToRun())
        assertTrue(runner.needsSettling())

        // With the phone back it is the phone's business again.
        phoneHeard = now
        assertFalse(runner.needsSettling())
    }

    @Test fun whileTheBenchScreensUseThePumpTheWatchKeepsOut() {
        phoneLeft()
        store.setMode(AutonomyPolicy.Mode.ACTIVE)
        glucose(150.0, -8.0)
        benchUsesThePump = true
        assertFalse(runner.wantsToRun())
        assertNull(runner.onReading())
        assertTrue(sent.isEmpty())

        benchUsesThePump = false
        assertTrue(runner.onReading()!!.getBoolean("done"))
    }

    @Test fun aPumpOutOfReachIsReportedOnceNotAtEveryReading() {
        phoneLeft()
        store.setMode(AutonomyPolicy.Mode.ACTIVE)
        glucose(150.0, -8.0)
        pumpAnswers = { PumpSession.SessionResult.Failed("pump not reached: out of range") }
        runner.onReading()
        for (step in 1..3) {
            now += minutes(5)
            store.addReading(GlucoseReading(now, 150.0 - 8.0 * step))
            runner.onReading()
        }
        // Tried at every reading, said once.
        assertEquals(4, sent.size)
        assertEquals(1, notes.size)

        // Back in reach: done, and said.
        pumpAnswers = { PumpSession.SessionResult.Done(null) }
        now += minutes(5)
        store.addReading(GlucoseReading(now, 118.0))
        assertTrue(runner.onReading()!!.getBoolean("done"))
        assertEquals(2, notes.size)
        assertFalse(notes.last().contains("не выполнено"))
    }

    @Test fun asSoonAsThePhoneSpeaksAgainTheWatchStepsBack() {
        phoneLeft()
        store.setMode(AutonomyPolicy.Mode.ACTIVE)
        glucose(150.0, -8.0)
        assertTrue(runner.wantsToRun())

        phoneHeard = now
        phoneLease = ControlLease(6L, now, now + minutes(10), pump, true)
        assertFalse(runner.wantsToRun())
        assertNull(runner.onReading())
        assertTrue(sent.isEmpty())
    }

    @Test fun aPhoneThatTookControlBackIsNotAPhoneThatIsAway() {
        phoneLeft()
        phoneLease = phoneLease!!.copy(controllerIsWatch = false)
        store.setMode(AutonomyPolicy.Mode.ACTIVE)
        glucose(150.0, -8.0)
        assertFalse(runner.wantsToRun())
    }

    @Test fun steadyGlucoseIsJournalledAndNothingIsSent() {
        phoneLeft()
        store.setMode(AutonomyPolicy.Mode.ACTIVE)
        glucose(130.0, 0.0)
        val entry = runner.onReading()!!
        assertEquals("ALL_CLEAR", entry.getString("rule"))
        assertEquals("LEAVE", entry.getString("action"))
        assertTrue(sent.isEmpty())
        assertTrue(notes.isEmpty())
    }

    @Test fun aRehearsalShowsTheDecisionAndTouchesNothingEvenWithThePhoneInCharge() {
        phoneLeft(silentMinutes = 1)
        store.setMode(AutonomyPolicy.Mode.ACTIVE)
        glucose(150.0, -8.0)
        val entry = runner.onReading(rehearsal = true)
        assertNotNull(entry)
        assertEquals("REHEARSAL", entry!!.getString("mode"))
        assertEquals("the phone is in charge", entry.getString("standing"))
        assertEquals("TBR 0 % 30 min", entry.getString("action"))
        // The watch's forecast is there to be compared with the phone's.
        assertEquals(49, entry.getJSONArray("watchForecast").length())
        assertEquals(150, entry.getJSONArray("watchForecast").getInt(0))
        assertEquals(50.0, entry.getJSONObject("snapshot").getDouble("sensitivity"), 0.0)
        assertTrue(entry.getBoolean("basalProfileKnown"))
        assertTrue(sent.isEmpty())
        assertTrue(notes.isEmpty())
        assertTrue(store.journal().isEmpty())
    }

    @Test fun theWearerIsAskedToEatOnceNotEveryFiveMinutes() {
        phoneLeft()
        // A lot of insulin about to act: no stop of basal can hold the forecast up.
        store.saveSnapshot(store.snapshot()!!.copy(insulinActivity = List(48) { 0.02 }, iobU = 3.0))
        store.setMode(AutonomyPolicy.Mode.ACTIVE)
        glucose(95.0, 0.0)
        runner.onReading()
        now += minutes(5)
        store.addReading(GlucoseReading(now, 94.0))
        runner.onReading()
        assertEquals(1, carbs.size)
        assertTrue(carbs.single() in 5..40)
        assertFalse(carbsWhy.single().contains("наблюдения"))
    }

    @Test fun whileOnlyObservingTheWearerIsStillAskedToEatAndToldThatBasalWasNotStopped() {
        phoneLeft()
        store.saveSnapshot(store.snapshot()!!.copy(insulinActivity = List(48) { 0.02 }, iobU = 3.0))
        glucose(95.0, 0.0)
        assertEquals(AutonomyPolicy.Mode.OBSERVE, store.mode())
        runner.onReading()
        assertTrue(sent.isEmpty())
        assertEquals(1, carbs.size)
        assertTrue(carbsWhy.single().contains("режиме наблюдения"))
    }
}
