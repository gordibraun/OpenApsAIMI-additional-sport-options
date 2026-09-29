package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RescueReboundReplayTest {
    private data class Insulin(val time: Long, val activity: Double, val iob: Double)
    private data class Carb(val time: Long, val amount: Double, val type: String)
    private data class Snapshot(
        val label: String, val bg: Double, val delta: Double, val cob: Double, val isf: Double,
        val carbRatio: Double, val profileBasal: Double, val before: List<Int>,
        val insulin: List<Insulin>, val carbs: List<Carb>
    )
    private val snapshots = javaClass.getResourceAsStream("/rebound-forecast-inputs.json")!!.bufferedReader().use {
        Gson().fromJson(it, Array<Snapshot>::class.java).toList()
    }

    // Recorded inputs, relative timestamps, and the real prediction engine. This is
    // an open-loop calculation replay, not a simulated patient or a dosing recommendation.
    private fun predict(s: Snapshot, legacy: Boolean, smb: Double = 0.0, rate: Double = s.profileBasal,
                        retainFoodLabel: Boolean = true): List<Double> {
        val carbs = DeclaredCarbForecast.calculate(
            entries = s.carbs.map { DeclaredCarbForecast.Entry(it.time, it.amount, it.type) },
            now = 0L, aapsCobG = s.cob, delta = s.delta, carbSensitivityMgdlPerGram = s.isf / s.carbRatio
        )
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.carb_ratio } returns s.carbRatio
        return AdvancedPredictionEngine.predict(
            currentBG = s.bg,
            iobArray = s.insulin.map {
                IobTotal(it.time, iob = it.iob, activity = if (legacy) it.activity.coerceAtLeast(0.0) else it.activity)
            }.toTypedArray(),
            finalSensitivity = s.isf, cobG = if (legacy) 0.0 else carbs.cobG, profile = profile,
            selectedFoodType = if (!retainFoodLabel) null else if (legacy && s.cob > 0.0) "fast" else carbs.dominantFoodType ?: "balanced",
            carbSensitivityMgdlPerGram = s.isf / s.carbRatio, delta = s.delta,
            explicitCarbEntry = retainFoodLabel, uamConfidence = if (s.cob > 0.0) 0.0 else 0.35,
            targetBG = 117.0, plannedSmbU = smb, plannedRateUph = rate, profileBasalUph = s.profileBasal,
            carbImpactTimelineMgdlPer5m = if (legacy) null else carbs.impactMgdlPer5m
        )
    }

    @Test fun retainedLabelCannotReactivateFoodWhenLegacyInputsLostAllCarbs() {
        snapshots.forEach { s ->
            assertEquals(predict(s, legacy = true, retainFoodLabel = false), predict(s, legacy = true), s.label)
        }
    }

    @Test fun restoredCarbsAndSignedActivityCorrectThePreBolusForecast() {
        snapshots.filter { it.cob > 0.0 }.forEach { s ->
            // The saved baseline preserves the old bug. The current engine must not
            // be asked to emulate its former zero-COB/explicit-food inconsistency.
            val oldEnd = s.before.last().toDouble()
            val corrected = predict(s, legacy = false)
            assertTrue(corrected.last() > oldEnd + 9.0, s.label)
            assertTrue(corrected.last() < oldEnd + 20.0, s.label)
            println("REPLAY ${s.label}: oldEnd=$oldEnd, correctedEnd=${corrected.last()}")
        }
    }

    @Test fun positiveSoftCapStillRequiresASafeFinalPrediction() {
        val s = snapshots.single { it.label == "11:50:05" }
        val fraction = ForecastDecisionSearch.safeFraction { share ->
            val dose = ForecastDecisionSearch.candidateBolus(0.3, share, 0.1)
            predict(s, legacy = false, smb = dose).min() >= 107.0
        }
        val dose = ForecastDecisionSearch.candidateBolus(0.3, fraction, 0.1)
        assertTrue(dose > 0.0 && dose <= 0.3)
        assertTrue(predict(s, legacy = false, smb = dose).min() >= 107.0)
        println("REPLAY ${s.label}: safe capped candidate=$dose at profile basal (not a full APS decision)")
    }

    @Test fun laterRecordedBolusesStillPreventAnUnsafeExtraDose() {
        val s = snapshots.single { it.label == "12:35:56" }
        val rejected = predict(s, legacy = false, smb = 0.9, rate = 2.4)
        assertTrue(rejected.min() < 65.0)
        assertEquals(predict(s, legacy = true), predict(s, legacy = false))
    }
}
