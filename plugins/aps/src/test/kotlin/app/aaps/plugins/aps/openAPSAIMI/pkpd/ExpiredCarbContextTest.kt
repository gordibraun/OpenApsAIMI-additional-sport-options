package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExpiredCarbContextTest {
    private val profile = mockk<OapsProfileAimi>(relaxed = true).also {
        every { it.carb_ratio } returns 10.3
    }

    private fun forecast(type: String?, explicit: Boolean, cob: Double = 0.0,
                         rescue: Boolean = false, bg: Double = 133.0, smb: Double = 0.0) =
        AdvancedPredictionEngine.predict(
            currentBG = bg, iobArray = Array(49) { IobTotal(it * 300_000L, activity = 0.002) },
            finalSensitivity = 44.0, cobG = cob, profile = profile,
            selectedFoodType = type, explicitCarbEntry = explicit, delta = 4.67,
            observedCarbImpactMgdlPer5m = 3.0, uamConfidence = 1.0,
            rescueFastActive = rescue, targetBG = 117.0, plannedSmbU = smb,
            plannedInsulinAction = testInsulinAction()
        )

    @Test fun expiredFoodTypeCannotHideObservedRiseWhenCobIsZero() {
        val withoutOldMeal = forecast(null, false)
        for (type in listOf("fast", "balanced", "slow")) {
            assertEquals(withoutOldMeal, forecast(type, true), type)
        }
    }

    @Test fun expiredFoodTypeDoesNotChangeProposedInsulinAction() {
        for (type in listOf("fast", "balanced", "slow")) {
            assertEquals(forecast(null, false, smb = 0.2), forecast(type, true, smb = 0.2), type)
        }
    }

    @Test fun recentLowStillUsesShortReboundWhenOldTypeExpires() {
        val short = forecast(null, false, rescue = true)
        assertEquals(short, forecast("fast", true, rescue = true))
        assertTrue(short.last() < forecast(null, false).last())
    }

    @Test fun riseBelowTargetStillUsesShortRebound() {
        assertEquals(forecast(null, false, bg = 95.0), forecast("fast", true, bg = 95.0))
    }

    @Test fun remainingDeclaredCarbsStillRespectTheirSelectedType() {
        assertTrue(forecast("fast", true, cob = 8.0)[6] > forecast("slow", true, cob = 8.0)[6])
    }
}
