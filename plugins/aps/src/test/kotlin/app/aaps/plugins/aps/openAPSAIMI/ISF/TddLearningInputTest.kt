package app.aaps.plugins.aps.openAPSAIMI.ISF

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TddLearningInputTest {
    private val day = 24 * 60 * 60_000L
    private val points = (0L..day step 5 * 60_000L).toList()

    @Test fun completeDayMayUpdateTdd() {
        assertTrue(TddLearningInput.continuous(points, 0, day))
        assertEquals(TddLearningInput.Selection(38.0, true, "complete history"), TddLearningInput.select(38.0, 45.0, 56.0, true))
    }

    @Test fun missingStartEndOrMiddleIsNotACompleteDay() {
        for (p in listOf(points.drop(10), points.dropLast(10), points.filterNot { it in day / 3..day / 2 }, emptyList())) {
            assertFalse(TddLearningInput.continuous(p, 0, day))
        }
    }

    @Test fun repeatedCalculationsCannotFillAHoleInHistory() {
        assertFalse(TddLearningInput.continuous(List(400) { day / 2 }, 0, day))
        assertFalse(TddLearningInput.continuous(points, 0, day / 2))
    }

    @Test fun incompleteLowTddHoldsPreviousInsteadOfLearningResistanceFromMissingDoses() {
        val s = TddLearningInput.select(30.18, 45.0, 56.0, false)
        assertEquals(45.0, s.units)
        assertFalse(s.learn)
    }

    @Test fun fallbackIsInInsulinUnitsNotIsfUnits() {
        assertEquals(56.0, TddLearningInput.select(null, null, 56.0, true).units)
        for (observed in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0)) {
            assertFalse(TddLearningInput.select(observed, 45.0, 56.0, true).learn)
        }
    }

    @Test fun missingHistoryDoesNotUpdateEmaAndCompleteHistoryUpdatesItGradually() {
        val held = TddLearningInput.select(30.18, 45.0, 56.0, false)
        assertEquals(45.0, TddLearningInput.updatedEma(held, 45.0, 0.2))
        val complete = TddLearningInput.select(40.0, 45.0, 56.0, true)
        assertEquals(44.0, TddLearningInput.updatedEma(complete, 45.0, 0.2))
    }

    @Test fun invalidPersistedEmaIsNotPropagatedIntoTheIsfEngine() {
        for (previous in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val fallback = TddLearningInput.select(null, previous, 56.0, false)
            assertEquals(56.0, TddLearningInput.updatedEma(fallback, previous, 0.2))
        }
        assertNull(TddLearningInput.updatedEma(TddLearningInput.select(null, null, 0.0, false), null, 0.2))
    }
}
