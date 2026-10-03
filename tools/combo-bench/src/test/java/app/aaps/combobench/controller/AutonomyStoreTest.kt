package app.aaps.combobench.controller

import app.aaps.combobench.JsonFiles
import app.aaps.pump.combowatch.executor.AutonomyPolicy
import app.aaps.pump.combowatch.protocol.BolusKind
import app.aaps.pump.combowatch.protocol.PumpEvent
import app.aaps.pump.combowatch.regulation.GlucoseReading
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The watch's own record of the pump has to follow the driver's events exactly as they come. */
class AutonomyStoreTest {

    private class MemoryFiles : JsonFiles {
        val content = HashMap<String, String>()
        override fun exists(name: String) = content.containsKey(name)
        override fun read(name: String) = JSONObject(content.getValue(name))
        override fun write(name: String, value: JSONObject) { content[name] = value.toString() }
    }

    private var now = 1_800_000_000_000L
    private val files = MemoryFiles()
    private val store = AutonomyStore(files) { now }
    private fun ago(minutes: Int) = now - minutes * 60_000L

    private fun started(at: Long, percent: Int, minutes: Int) =
        PumpEvent(0, PumpEvent.Type.TBR_STARTED, at, tbrPercentage = percent, tbrDurationMinutes = minutes, tbrType = "normal")
    private fun ended(at: Long) = PumpEvent(0, PumpEvent.Type.TBR_ENDED, at)

    @Test fun untilTheOwnerChoosesTheWatchOnlyObserves() {
        assertEquals(AutonomyPolicy.Mode.OBSERVE, store.mode())
        store.setMode(AutonomyPolicy.Mode.ACTIVE)
        assertEquals(AutonomyPolicy.Mode.ACTIVE, AutonomyStore(files) { now }.mode())
    }

    @Test fun aModeFileThatCannotBeReadMeansObserving() {
        files.content[AutonomyStore.MODE_FILE] = """{"mode":"SOMETHING"}"""
        assertEquals(AutonomyPolicy.Mode.OBSERVE, store.mode())
    }

    @Test fun aReadingIsKeptOnceAndOldOnesAreDropped() {
        assertTrue(store.addReading(GlucoseReading(ago(5), 120.0)))
        assertFalse(store.addReading(GlucoseReading(ago(5), 120.0)))
        assertTrue(store.addReading(GlucoseReading(ago(13 * 60), 90.0)))
        assertEquals(listOf(120.0), store.readings().map { it.mgdl })
    }

    @Test fun settingATemporaryBasalAsTheDriverReportsIt() {
        // Replacing one temporary basal with another: the driver reports the end of what was
        // running with the time of the change, then the start of the new one with the time it
        // began working on it - which is a little earlier.
        store.onPumpEvent(started(ago(20), 50, 30), byWatch = false)
        store.onPumpEvent(ended(ago(5)), byWatch = true)
        store.onPumpEvent(started(ago(5) - 14_000, 0, 30), byWatch = true)

        val log = store.delivery()
        assertEquals(50, log.percentAt(ago(10)))
        assertEquals(0, log.percentAt(ago(4)))
        assertEquals(0, log.percentAt(now))
        assertTrue(log.tbrAt(now)!!.byWatch)
        assertFalse(log.tbrAt(ago(10))!!.byWatch)
    }

    @Test fun aForcedCancelReportedThreeTimesEndsItOnce() {
        store.onPumpEvent(started(ago(20), 150, 30), byWatch = false)
        store.onPumpEvent(ended(ago(6)), byWatch = false)
        store.onPumpEvent(ended(ago(5)), byWatch = false)
        store.onPumpEvent(ended(ago(1)), byWatch = false)
        val log = store.delivery()
        assertEquals(150, log.percentAt(ago(7)))
        assertEquals(100, log.percentAt(ago(5)))
        assertEquals(ago(6), log.tbrs.single().endedEpochMs)
    }

    @Test fun theSameEventDeliveredTwiceChangesNothing() {
        repeat(2) { store.onPumpEvent(started(ago(10), 0, 30), byWatch = true) }
        val bolus = PumpEvent(0, PumpEvent.Type.BOLUS_INFUSED, ago(8), bolusId = 7, bolusTenthsIU = 3, bolusKind = BolusKind.SMB)
        repeat(2) { store.onPumpEvent(bolus, byWatch = false) }
        assertEquals(1, store.delivery().tbrs.size)
        assertEquals(listOf(0.3), store.delivery().boluses.map { it.units })
    }

    @Test fun whatThePumpShowsOverridesWhatTheRecordBelieved() {
        // Nothing recorded, and the pump runs 0 % with 12 minutes left: it started before this record did.
        store.syncWithPump(now, tbrRunning = true, tbrPercentage = 0, tbrRemainingMinutes = 12)
        assertEquals(0, store.delivery().percentAt(now + 60_000))
        assertEquals(100, store.delivery().percentAt(now + 13 * 60_000))
        assertFalse(store.delivery().tbrAt(now + 60_000)!!.byWatch)

        // Later the pump shows none although the record still has one running.
        now += 5 * 60_000
        store.syncWithPump(now, tbrRunning = false, tbrPercentage = null, tbrRemainingMinutes = null)
        assertEquals(100, store.delivery().percentAt(now + 1_000))
    }

    @Test fun aTemporaryBasalThePumpConfirmsIsLeftAsRecorded() {
        store.onPumpEvent(started(ago(10), 0, 30), byWatch = true)
        store.syncWithPump(now, tbrRunning = true, tbrPercentage = 0, tbrRemainingMinutes = 20)
        assertEquals(1, store.delivery().tbrs.size)
        assertTrue(store.delivery().tbrAt(now)!!.byWatch)
    }

    @Test fun unpairingForgetsTheOldPumpsDeliveryAndSnapshot() {
        store.onPumpEvent(started(ago(10), 0, 30), byWatch = true)
        files.content[AutonomyStore.SNAPSHOT_FILE] = """{"madeAt":1}"""
        store.forgetPump()
        assertTrue(store.delivery().tbrs.isEmpty())
        assertNull(store.snapshot())
    }

    @Test fun theJournalKeepsTheLatestEntries() {
        repeat(AutonomyStore.MAX_JOURNAL_ENTRIES + 5) { store.addToJournal(JSONObject().put("n", it)) }
        val journal = store.journal()
        assertEquals(AutonomyStore.MAX_JOURNAL_ENTRIES, journal.size)
        assertEquals(AutonomyStore.MAX_JOURNAL_ENTRIES + 4, journal.last().getInt("n"))
    }

    @Test fun carbsEnteredOnTheWatchAreKeptAndCountedWithDelivery() {
        store.addCarbs(app.aaps.pump.combowatch.regulation.CarbsRecord(now - 60_000L, 25))
        store.addCarbs(app.aaps.pump.combowatch.regulation.CarbsRecord(now, 10))
        assertEquals(listOf(25, 10), store.carbs().map { it.grams })
        assertEquals(2, store.delivery().carbs.size)
    }
}
