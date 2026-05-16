package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedPredictionEngineTest {

    @Test
    fun `test predict no horizon`() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        val result = AdvancedPredictionEngine.predict(
            currentBG = 100.0,
            iobArray = emptyArray(),
            finalSensitivity = 50.0,
            cobG = 0.0,
            profile = profile,
            horizonMinutes = 0
        )
        assertEquals(1, result.size)
        assertEquals(100.0, result[0], 0.0)
    }

    @Test
    fun `test predict flat with no IOB or COB`() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.carb_ratio } returns 10.0
        every { profile.peakTime } returns 75.0

        val result = AdvancedPredictionEngine.predict(
            currentBG = 100.0,
            iobArray = emptyArray(),
            finalSensitivity = 50.0,
            cobG = 0.0,
            profile = profile,
            horizonMinutes = 60
        )
        // Should remain flat at 100
        assertTrue(result.all { it == 100.0 })
    }

    @Test
    fun `test predict drop with IOB`() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.carb_ratio } returns 10.0
        every { profile.peakTime } returns 75.0

        val iobEntry = mockk<IobTotal>()
        every { iobEntry.iob } returns 1.0
        every { iobEntry.time } returns System.currentTimeMillis()

        val result = AdvancedPredictionEngine.predict(
            currentBG = 200.0,
            iobArray = arrayOf(iobEntry),
            finalSensitivity = 50.0,
            cobG = 0.0,
            profile = profile,
            horizonMinutes = 60
        )
        // Should drop
        assertTrue(result.last() < 200.0)
    }

    @Test
    fun `rescue fast forecast has shorter rebound tail than normal UAM`() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.carb_ratio } returns 10.0
        every { profile.peakTime } returns 75.0

        val normalUam = AdvancedPredictionEngine.predict(
            currentBG = 100.0,
            iobArray = emptyArray(),
            finalSensitivity = 50.0,
            cobG = 0.0,
            profile = profile,
            delta = 5.0,
            observedCarbImpactMgdlPer5m = 10.0,
            remainingCiPeakMgdlPer5m = 10.0,
            horizonMinutes = 60
        )
        val rescueFast = AdvancedPredictionEngine.predict(
            currentBG = 100.0,
            iobArray = emptyArray(),
            finalSensitivity = 50.0,
            cobG = 0.0,
            profile = profile,
            delta = 5.0,
            observedCarbImpactMgdlPer5m = 10.0,
            remainingCiPeakMgdlPer5m = 10.0,
            rescueFastActive = true,
            horizonMinutes = 60
        )

        assertTrue(rescueFast.last() < normalUam.last())
    }

    @Test
    fun `unified momentum is shorter for fast carbs than slow carbs`() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.carb_ratio } returns 10.0
        every { profile.peakTime } returns 75.0

        val fast = AdvancedPredictionEngine.predict(
            currentBG = 107.0,
            iobArray = emptyArray(),
            finalSensitivity = 43.0,
            cobG = 15.0,
            profile = profile,
            selectedFoodType = "fast",
            delta = 14.0,
            explicitCarbEntry = true,
            horizonMinutes = 60
        )
        val slow = AdvancedPredictionEngine.predict(
            currentBG = 107.0,
            iobArray = emptyArray(),
            finalSensitivity = 43.0,
            cobG = 15.0,
            profile = profile,
            selectedFoodType = "slow",
            delta = 14.0,
            explicitCarbEntry = true,
            horizonMinutes = 60
        )

        assertTrue(fast.last() < slow.last())
    }

    @Test
    fun `protective zero basal decision lifts falling forecast without adding insulin`() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.carb_ratio } returns 10.0
        every { profile.peakTime } returns 75.0

        val now = System.currentTimeMillis()
        val iob = Array(49) { index ->
            IobTotal(
                time = now + index * 5 * 60_000L,
                iob = 1.8,
                activity = (0.020 - index * 0.00025).coerceAtLeast(0.006)
            )
        }

        val withoutDecision = AdvancedPredictionEngine.predict(
            currentBG = 139.0,
            iobArray = iob,
            finalSensitivity = 35.0,
            cobG = 0.0,
            profile = profile,
            delta = -6.0,
            horizonMinutes = 120
        )
        val withProtectiveZeroBasal = AdvancedPredictionEngine.predict(
            currentBG = 139.0,
            iobArray = iob,
            finalSensitivity = 35.0,
            cobG = 0.0,
            profile = profile,
            delta = -6.0,
            plannedSmbU = 0.0,
            plannedRateUph = 0.0,
            profileBasalUph = 1.0,
            plannedDurationMin = 30,
            mpcShare = 0.6,
            piShare = 0.4,
            safetyMechanism = "Защита от раннего перелива",
            horizonMinutes = 120
        )

        assertTrue(withProtectiveZeroBasal.last() > withoutDecision.last())
    }

    @Test
    fun `final SMB is fully reflected in post decision forecast`() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.carb_ratio } returns 10.0
        every { profile.peakTime } returns 75.0

        val withoutDecision = AdvancedPredictionEngine.predict(
            currentBG = 121.0,
            iobArray = emptyArray(),
            finalSensitivity = 39.0,
            cobG = 0.0,
            profile = profile,
            selectedFoodType = "balanced",
            delta = 0.3,
            observedCarbImpactMgdlPer5m = 1.0,
            uamConfidence = 0.0,
            horizonMinutes = 240
        )
        val withFinalSmbAndBasal = AdvancedPredictionEngine.predict(
            currentBG = 121.0,
            iobArray = emptyArray(),
            finalSensitivity = 39.0,
            cobG = 0.0,
            profile = profile,
            selectedFoodType = "balanced",
            delta = 0.3,
            plannedSmbU = 0.55,
            plannedRateUph = 1.56,
            profileBasalUph = 1.20,
            plannedDurationMin = 30,
            mpcShare = 0.52,
            piShare = 0.48,
            observedCarbImpactMgdlPer5m = 1.0,
            uamConfidence = 0.0,
            horizonMinutes = 240
        )

        assertTrue(withFinalSmbAndBasal.last() <= withoutDecision.last() - 20.0)
        assertTrue(withFinalSmbAndBasal.minOrNull()!! < 110.0)
    }
}
