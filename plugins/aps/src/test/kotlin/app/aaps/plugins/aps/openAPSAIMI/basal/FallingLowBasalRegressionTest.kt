package app.aaps.plugins.aps.openAPSAIMI.basal

import android.content.Context
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.AIMIAdaptiveBasal
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyDecision
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FallingLowBasalRegressionTest {
    private val planner = mockk<BasalPlanner>().also { every { it.plan(any(), any()) } returns null }
    private val engine = BasalDecisionEngine(mockk<Context>(relaxed = true), mockk<AIMIAdaptiveBasal>(relaxed = true), planner)
    private val helpers = BasalDecisionEngine.Helpers(
        calculateRate = { _, current, factor, _ -> current * factor },
        calculateBasalRate = { _, current, factor -> current * factor },
        detectMealOnset = { _, _, _, _, _ -> false }, round = { v, _ -> v })

    private fun input(bg: Double, delta: Double, iob: Double) = BasalDecisionEngine.Input(
        bg = bg, profileCurrentBasal = 1.2, basalEstimate = 1.2,
        tdd7P = 45.0, tdd7Days = 45.0, variableSensitivity = 50.0, profileSens = 108.0,
        predictedBg = 90.0, targetBg = 117.0, eventualBg = 90.0,
        iob = iob, maxIob = 15.0, allowMealHighIob = false,
        safetyDecision = SafetyDecision(false, 1.0, "", false, false), mealData = MealData(),
        delta = delta, shortAvgDelta = delta, longAvgDelta = delta, combinedDelta = delta,
        bgAcceleration = -0.1, slopeFromMaxDeviation = 0.1, slopeFromMinDeviation = 0.1,
        forcedBasal = 0.0, forcedMealActive = false, isMealActive = false, runtimeMinValue = 0,
        snackTime = false, snackRuntimeMin = 0, fastingTime = false, sportTime = false,
        honeymoon = false, pregnancyEnable = false, mealTime = false, mealRuntimeMin = 0,
        bfastTime = false, bfastRuntimeMin = 0, lunchTime = false, lunchRuntimeMin = 0,
        dinnerTime = false, dinnerRuntimeMin = 0, highCarbTime = false, highCarbRuntimeMin = 0,
        timenow = 7, sixAmHour = 6, recentSteps5Minutes = 0, nightMode = false,
        modesCondition = false, autodrive = false, currentTemp = CurrentTemp(30, 0.0, 30),
        glucoseStatus = null, featuresCombinedDelta = null, smbToGive = 0.0,
        zeroSinceMin = 0, minutesSinceLastChange = 5)

    @Test fun lowNetIobDoesNotSkipReductionDuringRecordedSlowFall() {
        for (iob in listOf(-0.1, 0.0, 0.047, 0.1, 0.11)) {
            val decision = engine.decide(input(83.0, -1.67, iob), RT(runningDynamicIsf = true), helpers)
            assertEquals(0.3, decision.rate, 1e-9, "IOB=$iob")
        }
    }

    @Test fun acceleratingFallAt95DoesNotRestoreFullBasal() {
        val decision = engine.decide(input(95.0, -2.33, 0.205), RT(runningDynamicIsf = true), helpers)
        assertEquals(0.6, decision.rate, 1e-9)
    }

    @Test fun existingZeroBasalForFasterLowFallIsPreserved() {
        val decision = engine.decide(input(83.0, -3.0, 0.047), RT(runningDynamicIsf = true), helpers)
        assertEquals(0.0, decision.rate, 1e-9)
    }
}
