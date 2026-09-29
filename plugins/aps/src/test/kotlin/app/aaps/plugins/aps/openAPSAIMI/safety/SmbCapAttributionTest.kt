package app.aaps.plugins.aps.openAPSAIMI.safety

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmbCapAttributionTest {
    @Test
    fun `rescue is not blamed for a tighter night or IOB limit`() {
        val caps = listOf(SmbCapAttribution(0.3, "rescue"), SmbCapAttribution(0.1, "night"), SmbCapAttribution(0.0, "IOB"))
        assertEquals(listOf("IOB"), SmbCapAttribution.bindingReasons(caps, 0.5, 0.0))
        assertEquals(listOf("night"), SmbCapAttribution.bindingReasons(caps.take(2), 0.5, 0.1))
        assertEquals(emptyList<String>(), SmbCapAttribution.bindingReasons(caps.take(2), 0.05, 0.05))
        val configured = listOf(SmbCapAttribution(0.1, "configured"), SmbCapAttribution(0.3, "rescue"))
        assertEquals(listOf("configured"), SmbCapAttribution.bindingReasons(configured, 0.5, 0.1))
    }

    private fun input(minimum: Double) = RecentSmbOverdeliveryGuard.Input(
        noActiveMealMode = false, visibleCobG = 20.0, explicitFoodActive = true,
        bg = 160.0, iobU = 3.0, maxSmbU = 1.0, highBgMaxSmbU = 2.0,
        recentSmb15U = 0.0, recentSmb30U = 0.0, proposedSmbU = 0.5,
        eventualBg = 200.0, predictedBg = 210.0, minGuardBg = minimum
    )

    @Test
    fun `zero and negative projected minima are not missing data`() {
        listOf(-50.0, 0.0, 60.0).forEach {
            assertTrue(RecentSmbOverdeliveryGuard.evaluate(input(it)).blockSmb)
            assertEquals(0.0, RecentSmbOverdeliveryGuard.correctionLimit(input(it)).maxSmbU)
        }
        assertFalse(RecentSmbOverdeliveryGuard.evaluate(input(Double.NaN)).blockSmb)
    }

    @Test
    fun `night reason retains the numeric budget`() {
        val night = input(200.0).copy(noActiveMealMode = true, visibleCobG = 0.0,
            explicitFoodActive = false, nightNoMeal = true, bg = 180.0, iobU = 1.0)
        val result = RecentSmbOverdeliveryGuard.correctionLimit(night)
        assertTrue(result.reason.contains("15м="))
        assertTrue(result.reason.contains("30м="))
    }
}
