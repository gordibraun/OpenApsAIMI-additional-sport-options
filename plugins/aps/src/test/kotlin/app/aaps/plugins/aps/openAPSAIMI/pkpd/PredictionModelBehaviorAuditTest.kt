package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Characterizes model inconsistencies for audit, not desired or clinically safe behavior. Replace on repair. */
class PredictionModelBehaviorAuditTest {
    private val profile = mockk<OapsProfileAimi>(relaxed = true).also {
        every { it.carb_ratio } returns 10.0
        every { it.peakTime } returns 75.0
    }

    private fun predict(delta: Double, cob: Double = 0.0, type: String? = null,
                        activity: Double = 0.02, smb: Double = 0.0) =
        AdvancedPredictionEngine.predict(
            currentBG = 180.0,
            iobArray = Array(49) { IobTotal(it * 300_000L, activity = activity) },
            finalSensitivity = 50.0, cobG = cob, profile = profile,
            selectedFoodType = type, explicitCarbEntry = cob > 0.0,
            delta = delta, plannedSmbU = smb, uamConfidence = 1.0,
            targetBG = 117.0, plannedInsulinAction = testInsulinAction()
        )

    @Test fun typedFoodForecastCannotDistinguishModerateAndRapidFallingDelta() {
        for (type in listOf("fast", "balanced", "slow")) {
            val moderate = predict(delta = -3.0, cob = 10.0, type = type)
            val rapid = predict(delta = -20.0, cob = 10.0, type = type)
            assertEquals(moderate, rapid, type)
            println("MODEL AUDIT type=$type: delta -3 and -20 produce identical curves; first steps=${rapid.take(4)}")
        }
    }

    @Test fun growthWeakensTheExistingInsulinTermWithoutChangingTheIsfInput() {
        val flatInsulinEffect = predict(delta = 0.0, activity = 0.0)[1] - predict(delta = 0.0)[1]
        val risingInsulinEffect = predict(delta = 6.0, activity = 0.0)[1] - predict(delta = 6.0)[1]
        assertEquals(50.0 * 0.02 * 5.0, flatInsulinEffect, 1e-9)
        assertEquals(0.5 + 0.5 * 5.0 / 90.0, risingInsulinEffect / flatInsulinEffect, 1e-9)
        println("MODEL AUDIT same ISF=50: first-5min insulin drop flat=$flatInsulinEffect, rising=$risingInsulinEffect; relative=${risingInsulinEffect / flatInsulinEffect}")
    }

    @Test fun foodTypeNoLongerChangesTheActionTimingOfTheSameProposedBolus() {
        fun insulinDrop(type: String, index: Int): Double =
            predict(delta = 0.0, cob = 10.0, type = type, activity = 0.0)[index] -
                predict(delta = 0.0, cob = 10.0, type = type, activity = 0.0, smb = 0.3)[index]

        val fast30 = insulinDrop("fast", 6)
        val slow30 = insulinDrop("slow", 6)
        assertEquals(fast30, slow30, 1e-8)
        val withinHorizon = testInsulinAction().effectsPer5Minutes(0.3, 0.0, 0, 48).sum() * 50.0
        assertEquals(withinHorizon, insulinDrop("fast", 48), 1e-8)
        assertEquals(withinHorizon, insulinDrop("slow", 48), 1e-8)
        assertTrue(withinHorizon < 15.0)
        println("MODEL REGRESSION same proposed bolus: drop at +30 fast=$fast30, slow=$slow30; +240=$withinHorizon, remaining tail preserved")
    }
}
