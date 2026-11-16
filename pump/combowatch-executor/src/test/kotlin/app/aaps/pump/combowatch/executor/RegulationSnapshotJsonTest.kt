package app.aaps.pump.combowatch.executor

import app.aaps.pump.combowatch.protocol.RegulationSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RegulationSnapshotJsonTest {

    private fun snapshot(carbs: Int? = null, within: Int? = null) = RegulationSnapshot(
        madeAtEpochMs = 1_000L, pumpSerial = "10392647", validUntilEpochMs = 2_000L,
        targetMgdl = 117.0, hypoThresholdMgdl = 78.5, sensitivityMgdlPerU = 49.0, carbRatioGPerU = 6.1,
        cobG = 4.7, iobU = 2.75, insulinActivity = listOf(0.02, 0.01), insulinRemaining = listOf(1.0, 0.5),
        carbsRequiredG = carbs, carbsRequiredWithinMin = within,
    )

    @Test fun `the phone's carbs ask survives the trip to the watch`() {
        val back = RegulationSnapshot.fromJson(snapshot(carbs = 5, within = 120).toJson())
        assertEquals(5, back.carbsRequiredG)
        assertEquals(120, back.carbsRequiredWithinMin)
    }

    @Test fun `when carbohydrates were last entered on the phone survives the trip, and an older phone says nothing`() {
        val back = RegulationSnapshot.fromJson(snapshot().copy(lastCarbsAtEpochMs = 1_794_677_258_000L).toJson())
        assertEquals(1_794_677_258_000L, back.lastCarbsAtEpochMs)
        assertNull(RegulationSnapshot.fromJson(snapshot().toJson()).lastCarbsAtEpochMs)
    }

    @Test fun `the rules on trial and what they did reach the watch as the phone said them`() {
        val trials = RegulationSnapshot.Trials(
            capOn = true, warnMode = RegulationSnapshot.Trials.WARN_RECORD,
            notes = listOf(RegulationSnapshot.Trials.Note(RegulationSnapshot.Trials.KEY_CAP, 1_794_691_430_000L, "Проба: микродоза 2,4 → 0,5 ЕД"))
        )
        val back = RegulationSnapshot.fromJson(snapshot().copy(trials = trials).toJson())
        assertEquals(trials, back.trials)
        assertNull(RegulationSnapshot.fromJson(snapshot().toJson()).trials)
    }

    @Test fun `zero grams is a statement, a missing field is not`() {
        val saysNothingNeeded = RegulationSnapshot.fromJson(snapshot(carbs = 0, within = null).toJson())
        assertEquals(0, saysNothingNeeded.carbsRequiredG)
        assertNull(saysNothingNeeded.carbsRequiredWithinMin)
        val olderPhone = RegulationSnapshot.fromJson(snapshot().toJson())
        assertNull(olderPhone.carbsRequiredG)
        assertNull(olderPhone.carbsRequiredWithinMin)
    }

    @Test fun `a basal profile command survives the trip to the watch, and older commands carry none`() {
        val profile = List(24) { 900 + it * 10 }
        val sent = app.aaps.pump.combowatch.protocol.ComboCommand(
            "p1", 7L, app.aaps.pump.combowatch.protocol.CommandKind.SET_BASAL_PROFILE, 1_000L, 2_000L, basalProfileFactors = profile
        )
        val back = app.aaps.pump.combowatch.protocol.ComboCommand.fromJson(org.json.JSONObject(sent.toJson().toString()))
        assertEquals(app.aaps.pump.combowatch.protocol.CommandKind.SET_BASAL_PROFILE, back.kind)
        assertEquals(profile, back.basalProfileFactors)
        val tbr = app.aaps.pump.combowatch.protocol.ComboCommand("t1", 7L, app.aaps.pump.combowatch.protocol.CommandKind.SET_TBR, 1_000L, 2_000L, percentage = 0, durationMinutes = 30)
        assertEquals(null, app.aaps.pump.combowatch.protocol.ComboCommand.fromJson(org.json.JSONObject(tbr.toJson().toString())).basalProfileFactors)
    }

    @Test fun `a watch says what it can do in its heartbeat, and an older one says nothing`() {
        fun beat(capabilities: List<String>?) = app.aaps.pump.combowatch.protocol.WatchHeartbeat(
            atEpochMs = 1L, leaseGeneration = 7L, leaseLive = true, executorBusy = false, awaitingReconciliation = false,
            pumpReachable = true, watchBatteryPercent = 80, capabilities = capabilities
        )
        val can = app.aaps.pump.combowatch.protocol.ComboWatchProtocol.CAN_SET_BASAL_PROFILE
        assertEquals(listOf(can), app.aaps.pump.combowatch.protocol.WatchHeartbeat.fromJson(org.json.JSONObject(beat(listOf(can)).toJson().toString())).capabilities)
        assertEquals(null, app.aaps.pump.combowatch.protocol.WatchHeartbeat.fromJson(org.json.JSONObject(beat(null).toJson().toString())).capabilities)
    }
}
