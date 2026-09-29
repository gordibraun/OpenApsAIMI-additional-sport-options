package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.core.interfaces.aps.MealData
import app.aaps.plugins.aps.openAPSAIMI.carbs.CarbsAdvisor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class MealDeviationInputTest {
    @Test fun missingMinimumDoesNotEnableRisingGlucoseHeuristics() {
        val raw = MealData()
        val normalized = MealDeviationInput.normalize(raw)
        assertEquals(999.0, raw.slopeFromMinDeviation)
        assertEquals(0.0, normalized.slopeFromMinDeviation)
        assertFalse(normalized.slopeFromMinDeviation >= 1.0)
        val slope = minOf(normalized.slopeFromMaxDeviation, -normalized.slopeFromMinDeviation / 3.0)
        assertEquals(0, CarbsAdvisor.estimateRequiredCarbs(152.0, 117.0, slope, 0.643, 3.8, 38.7, 0.0))
    }

    @Test fun realDeviationAndCarbsArePreserved() {
        val input = MealData(mealCOB = 13.0, slopeFromMinDeviation = 2.4, slopeFromMaxDeviation = -0.7)
        assertEquals(input, MealDeviationInput.normalize(input))
    }

    @Test fun nonFiniteSlopesAreNotMeasurements() {
        val normalized = MealDeviationInput.normalize(MealData(slopeFromMinDeviation = Double.NaN, slopeFromMaxDeviation = Double.POSITIVE_INFINITY))
        assertEquals(0.0, normalized.slopeFromMinDeviation)
        assertEquals(0.0, normalized.slopeFromMaxDeviation)
    }
}
