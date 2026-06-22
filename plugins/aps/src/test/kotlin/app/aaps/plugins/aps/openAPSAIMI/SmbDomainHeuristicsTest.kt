package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.model.Constants
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActivityStage
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActivityState
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActivityWindow
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdParams
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.pkpd.SmbDamping
import app.aaps.plugins.aps.openAPSAIMI.safety.HighBgOverride
import app.aaps.plugins.aps.openAPSAIMI.safety.HypoTools
import app.aaps.plugins.aps.openAPSAIMI.safety.RecentSmbOverdeliveryGuard
import app.aaps.plugins.aps.openAPSAIMI.smb.SmbDampingUsecase
import app.aaps.plugins.aps.openAPSAIMI.smb.SmbQuantizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmbDomainHeuristicsTest {

    private fun runtime(
        tailFraction: Double = 0.0,
        stage: InsulinActivityStage = InsulinActivityStage.PEAK,
        relativeActivity: Double = 0.5,
        postWindowFraction: Double = 0.0,
        anticipation: Double = 0.0
    ): PkPdRuntime =
        PkPdRuntime(
            params = PkPdParams(diaHrs = 10.0, peakMin = 60.0),
            tailFraction = tailFraction,
            fusedIsf = 45.0,
            profileIsf = 45.0,
            tddIsf = 45.0,
            pkpdScale = 1.0,
            damping = SmbDamping(),
            activity = InsulinActivityState(
                window = InsulinActivityWindow(
                    onsetMin = 30.0,
                    peakMin = 60.0,
                    offsetMin = 240.0,
                    diaMin = 600.0
                ),
                relativeActivity = relativeActivity,
                normalizedPosition = 0.5,
                postWindowFraction = postWindowFraction,
                anticipationWeight = anticipation,
                minutesUntilOnset = 0.0,
                stage = stage
            )
        )

    @Test
    fun mealModeBypassSkipsDamping() {
        val out = SmbDampingUsecase.run(
            runtime(),
            SmbDampingUsecase.Input(
                smbDecision = 1.0,
                exercise = false,
                suspectedLateFatMeal = false,
                mealModeRun = true,
                highBgRiseActive = false
            )
        )
        assertEquals(1.0, out.smbAfterDamping, 1e-6)
        assertTrue(out.audit?.mealBypass == true)
    }

    @Test
    fun highBgRiseBypassSkipsDamping() {
        val out = SmbDampingUsecase.run(
            runtime(),
            SmbDampingUsecase.Input(
                smbDecision = 1.2,
                exercise = false,
                suspectedLateFatMeal = false,
                mealModeRun = false,
                highBgRiseActive = true
            )
        )
        assertEquals(1.2, out.smbAfterDamping, 1e-6)
        assertTrue(out.audit?.mealBypass == true)
    }

    @Test
    fun exerciseDampingApplied() {
        val out = SmbDampingUsecase.run(
            runtime(),
            SmbDampingUsecase.Input(
                smbDecision = 1.0,
                exercise = true,
                suspectedLateFatMeal = false,
                mealModeRun = false,
                highBgRiseActive = false
            )
        )
        assertTrue(out.smbAfterDamping < 1.0)
        assertTrue(out.audit?.exerciseApplied == true)
    }

    @Test
    fun lateFatDampingApplied() {
        val out = SmbDampingUsecase.run(
            runtime(),
            SmbDampingUsecase.Input(
                smbDecision = 1.0,
                exercise = false,
                suspectedLateFatMeal = true,
                mealModeRun = false,
                highBgRiseActive = false
            )
        )
        assertTrue(out.smbAfterDamping < 1.0)
        assertTrue(out.audit?.lateFatApplied == true)
    }

    @Test
    fun tailDampingApplied() {
        val out = SmbDampingUsecase.run(
            runtime(
                tailFraction = 0.6,
                stage = InsulinActivityStage.TAIL,
                relativeActivity = 0.0,
                postWindowFraction = 0.8
            ),
            SmbDampingUsecase.Input(
                smbDecision = 1.0,
                exercise = false,
                suspectedLateFatMeal = false,
                mealModeRun = false,
                highBgRiseActive = false
            )
        )
        assertTrue(out.smbAfterDamping < 1.0)
        assertTrue(out.audit?.tailApplied == true)
    }

    @Test
    fun highBgOverrideForcesInterval() {
        val res = HighBgOverride.apply(
            bg = 190.0,
            delta = 2.0,
            predictedBg = 170.0,
            eventualBg = 165.0,
            hypoGuard = 70.0,
            iob = 0.2,
            maxSmb = 2.0,
            currentDose = 0.1,
            pumpStep = 0.05
        )
        assertTrue(res.overrideUsed)
        assertEquals(0.05, res.dose, 1e-6)
        assertEquals(0, res.newInterval)
    }

    @Test
    fun highBgOverrideRespectsHypoGuard() {
        val res = HighBgOverride.apply(
            bg = 155.0,
            delta = 1.6,
            predictedBg = 75.0,
            eventualBg = 72.0,
            hypoGuard = 80.0,
            iob = 0.1,
            maxSmb = 2.0,
            currentDose = 0.2,
            pumpStep = 0.05
        )
        assertFalse(res.overrideUsed)
        assertEquals(0.2, res.dose, 1e-6)
        assertEquals(null, res.newInterval)
    }

    @Test
    fun highBgOverrideTriggersAtStrongThreshold() {
        val res = HighBgOverride.apply(
            bg = Constants.HIGH_BG_OVERRIDE_BG_STRONG,
            delta = 0.2,
            predictedBg = 170.0,
            eventualBg = 168.0,
            hypoGuard = 90.0,
            iob = 0.1,
            maxSmb = 2.0,
            currentDose = 0.0,
            pumpStep = Constants.DEFAULT_INSULIN_STEP_U
        )
        assertTrue(res.overrideUsed)
        assertEquals(Constants.DEFAULT_INSULIN_STEP_U, res.dose, 1e-6)
        assertEquals(0, res.newInterval)
    }

    @Test
    fun recentSmbGuardBlocksCumulativeHighBgBurstWithoutFood() {
        val decision = RecentSmbOverdeliveryGuard.evaluate(
            RecentSmbOverdeliveryGuard.Input(
                noActiveMealMode = true,
                visibleCobG = 0.0,
                explicitFoodActive = false,
                bg = 205.0,
                iobU = 5.95,
                maxSmbU = 1.5,
                highBgMaxSmbU = 2.5,
                recentSmb15U = 3.8,
                recentSmb30U = 5.6
            )
        )

        assertTrue(decision.blockSmb)
    }

    @Test
    fun recentSmbGuardAllowsExplicitFoodDespiteBurst() {
        val decision = RecentSmbOverdeliveryGuard.evaluate(
            RecentSmbOverdeliveryGuard.Input(
                noActiveMealMode = true,
                visibleCobG = 0.0,
                explicitFoodActive = true,
                bg = 205.0,
                iobU = 5.95,
                maxSmbU = 1.5,
                highBgMaxSmbU = 2.5,
                recentSmb15U = 3.8,
                recentSmb30U = 5.6
            )
        )

        assertFalse(decision.blockSmb)
    }

    @Test
    fun recentSmbGuardBlocksModerateBgWhenIobAlreadyHeavyWithoutFood() {
        val decision = RecentSmbOverdeliveryGuard.evaluate(
            RecentSmbOverdeliveryGuard.Input(
                noActiveMealMode = true,
                visibleCobG = 0.0,
                explicitFoodActive = false,
                bg = 152.0,
                iobU = 8.59,
                maxSmbU = 1.5,
                highBgMaxSmbU = 2.5,
                recentSmb15U = 2.1,
                recentSmb30U = 2.1
            )
        )

        assertTrue(decision.blockSmb)
    }

    @Test
    fun recentSmbGuardAllowsModerateBgWhenIobIsNotAlreadyHeavy() {
        val decision = RecentSmbOverdeliveryGuard.evaluate(
            RecentSmbOverdeliveryGuard.Input(
                noActiveMealMode = true,
                visibleCobG = 0.0,
                explicitFoodActive = false,
                bg = 152.0,
                iobU = 3.2,
                maxSmbU = 1.5,
                highBgMaxSmbU = 2.5,
                recentSmb15U = 0.0,
                recentSmb30U = 0.0
            )
        )

        assertFalse(decision.blockSmb)
    }

    @Test
    fun recentSmbGuardBlocksDoseThatWouldCreateBurstWithoutFood() {
        val decision = RecentSmbOverdeliveryGuard.evaluate(
            RecentSmbOverdeliveryGuard.Input(
                noActiveMealMode = true,
                visibleCobG = 0.0,
                explicitFoodActive = false,
                bg = 205.0,
                iobU = 4.0,
                maxSmbU = 1.5,
                highBgMaxSmbU = 2.5,
                recentSmb15U = 1.4,
                recentSmb30U = 2.5,
                proposedSmbU = 0.8
            )
        )

        assertTrue(decision.blockSmb)
    }

    @Test
    fun recentSmbGuardBlocksNightNoMealWithIobPressure() {
        val decision = RecentSmbOverdeliveryGuard.evaluate(
            RecentSmbOverdeliveryGuard.Input(
                noActiveMealMode = true,
                visibleCobG = 0.0,
                explicitFoodActive = false,
                bg = 190.0,
                iobU = 3.3,
                maxSmbU = 1.5,
                highBgMaxSmbU = 2.5,
                recentSmb15U = 0.0,
                recentSmb30U = 0.4,
                proposedSmbU = 0.4,
                nightNoMeal = true,
                eventualBg = 160.0
            )
        )

        assertTrue(decision.blockSmb)
    }

    @Test
    fun recentSmbGuardLeavesExtremeBgToHighBgLogic() {
        val decision = RecentSmbOverdeliveryGuard.evaluate(
            RecentSmbOverdeliveryGuard.Input(
                noActiveMealMode = true,
                visibleCobG = 0.0,
                explicitFoodActive = false,
                bg = 280.0,
                iobU = 5.95,
                maxSmbU = 1.5,
                highBgMaxSmbU = 2.5,
                recentSmb15U = 3.8,
                recentSmb30U = 5.6
            )
        )

        assertFalse(decision.blockSmb)
    }

    @Test
    fun recentSmbCorrectionLimitDampsNightNoFoodBeforeHardBlock() {
        val limit = RecentSmbOverdeliveryGuard.correctionLimit(
            RecentSmbOverdeliveryGuard.Input(
                noActiveMealMode = true,
                visibleCobG = 0.0,
                explicitFoodActive = false,
                bg = 152.0,
                iobU = 3.0,
                maxSmbU = 1.5,
                highBgMaxSmbU = 2.5,
                recentSmb15U = 0.0,
                recentSmb30U = 0.6,
                nightNoMeal = true,
                delta = 6.0,
                eventualBg = 170.0
            )
        )

        assertEquals(0.0, limit.maxSmbU, 1e-6)
        assertTrue(limit.reason.contains("нет активной еды"))
    }

    @Test
    fun recentSmbCorrectionLimitPreservesExplicitFood() {
        val limit = RecentSmbOverdeliveryGuard.correctionLimit(
            RecentSmbOverdeliveryGuard.Input(
                noActiveMealMode = true,
                visibleCobG = 0.0,
                explicitFoodActive = true,
                bg = 152.0,
                iobU = 3.0,
                maxSmbU = 1.5,
                highBgMaxSmbU = 2.5,
                recentSmb15U = 0.0,
                recentSmb30U = 0.6,
                nightNoMeal = true,
                delta = 6.0,
                eventualBg = 170.0
            )
        )

        assertEquals(2.5, limit.maxSmbU, 1e-6)
        assertTrue(limit.reason.isBlank())
    }

    @Test
    fun recentSmbGuardBlocksUnsafeLowForecastEvenWithVisibleFood() {
        val decision = RecentSmbOverdeliveryGuard.evaluate(
            RecentSmbOverdeliveryGuard.Input(
                noActiveMealMode = false,
                visibleCobG = 15.9,
                explicitFoodActive = true,
                bg = 158.0,
                iobU = 11.18,
                maxSmbU = 1.5,
                highBgMaxSmbU = 2.5,
                recentSmb15U = 0.9,
                recentSmb30U = 5.6,
                proposedSmbU = 0.85,
                eventualBg = 39.0,
                predictedBg = 39.0,
                minGuardBg = 39.0,
                targetBg = 117.0
            )
        )

        assertTrue(decision.blockSmb)
        assertTrue(decision.reason.contains("прогноз ниже цели"))
    }

    @Test
    fun recentSmbCorrectionLimitBlocksUnsafeLowForecastEvenWithVisibleFood() {
        val limit = RecentSmbOverdeliveryGuard.correctionLimit(
            RecentSmbOverdeliveryGuard.Input(
                noActiveMealMode = false,
                visibleCobG = 15.9,
                explicitFoodActive = true,
                bg = 158.0,
                iobU = 11.18,
                maxSmbU = 1.5,
                highBgMaxSmbU = 2.5,
                recentSmb15U = 0.9,
                recentSmb30U = 5.6,
                eventualBg = 39.0,
                predictedBg = 39.0,
                minGuardBg = 39.0,
                targetBg = 117.0
            )
        )

        assertEquals(0.0, limit.maxSmbU, 1e-6)
        assertTrue(limit.reason.contains("прогноз ниже цели"))
    }

    @Test
    fun recentSmbCorrectionLimitLeavesExtremeHighForecast() {
        val limit = RecentSmbOverdeliveryGuard.correctionLimit(
            RecentSmbOverdeliveryGuard.Input(
                noActiveMealMode = true,
                visibleCobG = 0.0,
                explicitFoodActive = false,
                bg = 280.0,
                iobU = 5.95,
                maxSmbU = 1.5,
                highBgMaxSmbU = 2.5,
                recentSmb15U = 3.8,
                recentSmb30U = 5.6,
                nightNoMeal = true,
                delta = 8.0,
                eventualBg = 285.0
            )
        )

        assertEquals(2.5, limit.maxSmbU, 1e-6)
        assertTrue(limit.reason.isBlank())
    }

    @Test
    fun quantizerRespectsPumpStep() {
        val quantized = SmbQuantizer.quantizeToPumpStep(0.123f, 0.05f)
        assertEquals(0.1f, quantized, 1e-6f)
    }

    @Test
    fun hypoToolsMinutesAboveThreshold() {
        val minutes = HypoTools.calculateMinutesAboveThreshold(bg = 80.0, slope = -1.0, thresholdBG = 70.0)
        assertEquals(10, minutes)
    }
}
