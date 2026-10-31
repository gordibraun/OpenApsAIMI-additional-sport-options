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

    @Test fun `zero grams is a statement, a missing field is not`() {
        val saysNothingNeeded = RegulationSnapshot.fromJson(snapshot(carbs = 0, within = null).toJson())
        assertEquals(0, saysNothingNeeded.carbsRequiredG)
        assertNull(saysNothingNeeded.carbsRequiredWithinMin)
        val olderPhone = RegulationSnapshot.fromJson(snapshot().toJson())
        assertNull(olderPhone.carbsRequiredG)
        assertNull(olderPhone.carbsRequiredWithinMin)
    }
}
