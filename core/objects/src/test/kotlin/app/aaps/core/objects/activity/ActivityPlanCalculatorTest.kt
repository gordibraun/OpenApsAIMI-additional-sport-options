package app.aaps.core.objects.activity

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ActivityPlanCalculatorTest {
    private fun plan(mode: String, bg: Double, type: String = "fast") = ActivityPlanCalculator.build(
        mode, 30, 0, type, 1.2, 50.0, 10.0, 110.0, 100.0, 70.0, bg, List(48) { bg })

    @Test fun preservesPhoneWalkFormula() {
        val p = plan("WALK", 150.0)
        assertEquals(20, p.effectPercent)
        assertEquals(0, p.tailMinutes)
        assertEquals(1.8, p.glucoseUseMgdlPer5m, 0.00001)
        assertEquals(139.2, p.forecastMin, 0.00001)
        assertEquals(0, p.requiredCarbs)
    }

    @Test fun preservesSportTailAndCarbPreview() {
        val p = plan("SPORT", 100.0)
        assertEquals(60, p.tailMinutes)
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
        assertEquals(180, ActivityPlanCalculator.tailMinutes("SPORT", 90))
        assertEquals(30, ActivityPlanCalculator.tailMinutes("WALK", 90))
    }

    @Test fun rejectsUnknownModeOrUnsupportedDuration() {
        assertThrows(IllegalArgumentException::class.java) { plan("unknown", 100.0) }
        assertThrows(IllegalArgumentException::class.java) { ActivityPlanCalculator.build(
            "SPORT", -1, 0, null, 1.0, 50.0, 10.0, 110.0, 100.0, 70.0, 100.0, listOf(100.0)) }
    }
}
