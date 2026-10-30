package app.aaps.core.objects.activity

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ActivityPlanCalculatorTest {
    private fun plan(mode: String, bg: Double, type: String = "fast") = ActivityPlanCalculator.build(
        mode, 30, 0, type, 1.2, 50.0, 10.0, 110.0, 100.0, 70.0, bg, List(48) { bg })

    @Test fun preservesPhoneWalkFormula() {
        val p = plan("WALK", 150.0)
        assertEquals(20, p.effectPercent)
        assertEquals(120, p.tailMinutes)            // insulin works better for two hours after a half-hour walk
        assertEquals(0, p.activityWindowEndMinutes - 30)   // the forecast's glucose use stops with the walk itself
        assertEquals(1.8, p.glucoseUseMgdlPer5m, 0.00001)
        assertEquals(139.2, p.forecastMin, 0.00001)
        assertEquals(0, p.requiredCarbs)
    }

    @Test fun preservesSportTailAndCarbPreview() {
        val p = plan("SPORT", 100.0)
        assertEquals(360, p.tailMinutes)            // six hours of raised sensitivity after a short session
        assertEquals(90, p.activityWindowEndMinutes)  // glucose use: the session plus the short tail of an hour
        assertEquals(68.95, p.forecastMin, 0.00001)
        assertEquals(7, p.requiredCarbs)
        assertEquals(0, p.carbsWithinMinutes)
    }

    @Test fun carbTypeChangesTimingNotActivityEffect() {
        val fast = plan("SPORT", 120.0)
        val ordinary = plan("SPORT", 120.0, "balanced")
        assertEquals(fast.requiredCarbs, ordinary.requiredCarbs)
        assertEquals(fast.forecastMin, ordinary.forecastMin)
        assertTrue(ordinary.carbsWithinMinutes <= fast.carbsWithinMinutes)
    }

    @Test fun noteUsesExistingAimiActivityProtocol() {
        assertEquals("AIMI_ACTIVITY_V2 mode=WALK effect=20 startOffset=20 duration=50 tail=0 requiredCarbs=0 carbType=none",
            ActivityPlanCalculator.note("WALK", 20, 20, 50, 0, 0, null))
        assertEquals(480, ActivityPlanCalculator.tailMinutes("SPORT", 90))
        assertEquals(180, ActivityPlanCalculator.tailMinutes("WALK", 90))
        assertEquals(180, ActivityPlanCalculator.useTailMinutes("SPORT", 90))
        assertEquals(30, ActivityPlanCalculator.useTailMinutes("WALK", 90))
    }

    @Test fun tailHoldsThenFades() {
        // Sport: full for the first hour of an eight-hour tail, half-way at four and a half hours, nothing at the end.
        assertEquals(1.0, ActivityPlanCalculator.tailPhase("SPORT", 480, 0.0), 1e-9)
        assertEquals(1.0, ActivityPlanCalculator.tailPhase("SPORT", 480, 60.0), 1e-9)
        assertEquals(0.5, ActivityPlanCalculator.tailPhase("SPORT", 480, 270.0), 1e-9)
        assertEquals(0.0, ActivityPlanCalculator.tailPhase("SPORT", 480, 480.0), 1e-9)
        // A walk holds for half an hour of a two-hour tail.
        assertEquals(1.0, ActivityPlanCalculator.tailPhase("WALK", 120, 30.0), 1e-9)
        assertEquals(0.5, ActivityPlanCalculator.tailPhase("WALK", 120, 75.0), 1e-9)
        assertEquals(0.0, ActivityPlanCalculator.tailPhase("WALK", 0, 10.0), 1e-9)
    }

    @Test fun rejectsUnknownModeOrUnsupportedDuration() {
        assertThrows(IllegalArgumentException::class.java) { plan("unknown", 100.0) }
        assertThrows(IllegalArgumentException::class.java) { ActivityPlanCalculator.build(
            "SPORT", -1, 0, null, 1.0, 50.0, 10.0, 110.0, 100.0, 70.0, 100.0, listOf(100.0)) }
    }
}
