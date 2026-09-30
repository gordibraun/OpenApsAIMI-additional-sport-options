package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.jupiter.api.Test

class ForecastAccountingTest {
    private val profile = mockk<OapsProfileAimi>(relaxed = true).also {
        every { it.carb_ratio } returns 10.0
        every { it.peakTime } returns 75.0
    }

    private fun forecast(fresh: Double = 0.0, smb: Double = 0.0, safety: String? = null, duration: Int = 30, rate: Double = 1.0) =
        AdvancedPredictionEngine.predict(
            currentBG = 220.0,
            iobArray = Array(49) { IobTotal(time = it * 300_000L, iob = 2.0, activity = 0.002) },
            finalSensitivity = 50.0, cobG = 0.0, profile = profile, plannedInsulinAction = testInsulinAction(),
            freshSmbPressureU = fresh, plannedSmbU = smb, safetyMechanism = safety,
            profileBasalUph = 1.0, plannedRateUph = rate, plannedDurationMin = duration
        )

    @Test fun deliveredSmbIsNotCountedAgainAsPressure() {
        assertEquals(forecast(), forecast(fresh = 2.0))
    }

    @Test fun safetyLabelDoesNotChangeInsulinPhysics() {
        assertEquals(forecast(smb = 0.5), forecast(smb = 0.5, safety = "HYPO_GUARD"))
    }

    @Test fun negativeNetActivityRaisesGlucoseByTheSignedBasalDeficit() {
        fun predict(activity: Double) = AdvancedPredictionEngine.predict(
            currentBG = 150.0,
            iobArray = Array(49) { IobTotal(time = it * 300_000L, activity = activity) },
            finalSensitivity = 50.0, cobG = 0.0, profile = profile
        ).last()
        assertEquals(150.0, predict(0.0), 1e-9)
        assertEquals(126.0, predict(0.002), 1e-9)
        assertEquals(174.0, predict(-0.002), 1e-9)
    }

    @Test fun signedPastBasalAndPlannedBasalAreAccountedForSeparately() {
        val curve = AdvancedPredictionEngine.predict(
            currentBG = 150.0,
            iobArray = Array(49) { IobTotal(time = it * 300_000L, activity = -0.002) },
            finalSensitivity = 50.0, cobG = 0.0, profile = profile, plannedInsulinAction = testInsulinAction(),
            profileBasalUph = 1.0, plannedRateUph = 0.0, plannedDurationMin = 30
        )
        val omittedEffect = -testInsulinAction().effectsPer5Minutes(0.0, -1.0, 30, 48).sum() * 50.0
        assertEquals(150.0 + 24.0 + omittedEffect, curve.last(), 1e-9)
    }

    @Test fun proposedInsulinIsIncludedExactlyOnce() {
        val effectWithinHorizon = testInsulinAction().effectsPer5Minutes(0.5, 0.0, 30, 48).sum() * 50.0
        assertEquals(effectWithinHorizon, forecast().last() - forecast(smb = 0.5).last(), 0.001)
    }

    @Test fun basalDurationIsPartOfTheDecision() {
        val action = testInsulinAction()
        val additionalEffect = (action.effectsPer5Minutes(0.0, 1.0, 60, 48).sum() -
            action.effectsPer5Minutes(0.0, 1.0, 30, 48).sum()) * 50.0
        assertEquals(additionalEffect, forecast(duration = 30, rate = 2.0).last() - forecast(duration = 60, rate = 2.0).last(), 0.001)
    }

    @Test fun observedCarbImpactUsesGlucoseAndActivityUnits() {
        val input = ForecastCarbImpact.calculate(7.0, 0.01, 50.0, 5.0, 20.0, 1.0, 90)
        assertEquals(9.5, input.observedMgdlPer5m, 0.001)
        assertEquals(0.0, input.remainingPeakMgdlPer5m, 0.001)
        assertEquals(2.0, input.absorptionHours, 0.001)
    }

    @Test fun emptyCobAndFlatGlucoseDoNotCreateCarbsFromCarbRatio() {
        val input = ForecastCarbImpact.calculate(0.0, 0.0, 50.0, 5.0, 0.0, 1.0, 90)
        assertEquals(0.0, input.observedMgdlPer5m, 0.0)
        assertEquals(0.0, input.remainingPeakMgdlPer5m, 0.0)
    }

    @Test fun decisionSearchReturnsOnlyAnEvaluatedSafeDose() {
        val evaluated = mutableListOf<Double>()
        val selected = ForecastDecisionSearch.safeFraction { evaluated.add(it); it <= 0.37 }
        assertTrue(selected in evaluated)
        assertTrue(selected <= 0.37 && selected > 0.35)
        assertEquals(1.0, ForecastDecisionSearch.safeFraction { true }, 0.0)
        assertEquals(0.0, ForecastDecisionSearch.safeFraction { false }, 0.0)
    }

    @Test fun candidatesUsePumpStepsAndNeverRoundTheDoseUp() {
        assertEquals(0.3, ForecastDecisionSearch.candidateBolus(0.73, 0.5, 0.1), 0.00001)
        assertEquals(0.0, ForecastDecisionSearch.candidateBolus(0.04, 1.0, 0.1), 0.0)
        assertEquals(0.0, ForecastDecisionSearch.candidateBolus(1.0, 0.0, 0.1), 0.0)
        assertEquals(0.0, ForecastDecisionSearch.candidateBolus(1.0, 1.0, Double.NaN), 0.0)
    }

    @Test fun shorterHorizonDoesNotAccelerateAbsorption() {
        val short = CarbAbsorptionModel.buildWeights(6, CarbAbsorptionModel.FoodType.BALANCED)
        val long = CarbAbsorptionModel.buildWeights(48, CarbAbsorptionModel.FoodType.BALANCED)
        short.indices.forEach { assertEquals(long[it], short[it], 0.0000001) }
        assertTrue(short.sum() < 1.0)
        assertEquals(1.0, long.sum(), 0.0000001)
    }

    @Test fun declaredCarbsCannotCreateMoreGlucoseThanTheirRemainingMass() {
        val curve = AdvancedPredictionEngine.predict(
            currentBG = 100.0, iobArray = emptyArray(), finalSensitivity = 50.0,
            cobG = 10.0, profile = profile, plannedInsulinAction = testInsulinAction(), selectedFoodType = "balanced", explicitCarbEntry = true,
            observedCarbImpactMgdlPer5m = 35.0, remainingCiPeakMgdlPer5m = 35.0,
            carbImpactTimelineMgdlPer5m = List(48) { 15.0 }
        )
        assertEquals(150.0, curve.last(), 0.0001)
        assertTrue(curve.all { it <= 150.0001 })
    }
}
