package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt

class ExpiredCarbHistoryReplayTest {
    private data class Insulin(val time: Long, val activity: Double, val iob: Double)
    private data class Snapshot(val label: String, val bg: Double, val delta: Double, val isf: Double,
                                val carbRatio: Double, val observedImpact: Double, val remainingPeak: Double,
                                val before: List<Int>, val insulin: List<Insulin>)
    private val snapshots = javaClass.getResourceAsStream("/expired-carb-forecast-inputs.json")!!.bufferedReader().use {
        Gson().fromJson(it, Array<Snapshot>::class.java).toList()
    }

    private fun predict(s: Snapshot, expiredType: Boolean): List<Double> {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.carb_ratio } returns s.carbRatio
        return AdvancedPredictionEngine.predict(
            currentBG = s.bg, iobArray = s.insulin.map { IobTotal(it.time, activity = it.activity, iob = it.iob) }.toTypedArray(),
            finalSensitivity = s.isf, cobG = 0.0, profile = profile, delta = s.delta.toFloat().toDouble(),
            selectedFoodType = if (expiredType) "fast" else null, explicitCarbEntry = expiredType,
            uamConfidence = 1.0, targetBG = 117.0,
            observedCarbImpactMgdlPer5m = s.observedImpact, remainingCiPeakMgdlPer5m = s.remainingPeak
        )
    }

    @Test fun recordedRiseIsNoLongerHiddenByExpiredType() {
        val expected60 = listOf(181.6082340106391, 232.5250688215858)
        snapshots.forEachIndexed { index, s ->
            val corrected = predict(s, true)
            val withoutOldType = predict(s, false)
            assertEquals(withoutOldType, corrected, s.label)
            assertNotEquals(s.before, corrected.map { it.roundToInt() }, s.label)
            assertEquals(expected60[index], corrected[12], 0.001, s.label)
            println("EXPIRED TYPE ${s.label}: stored60=${s.before[12]}, corrected60=${corrected[12]}; storedEnd=${s.before.last()}, correctedEnd=${corrected.last()}")
        }
    }
}
