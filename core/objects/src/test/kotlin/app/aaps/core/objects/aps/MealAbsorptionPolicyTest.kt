package app.aaps.core.objects.aps

import app.aaps.core.interfaces.aps.AutosensData
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MealAbsorptionPolicyTest {
    private val start = 1_790_746_469_000L
    private fun entry(type: String? = "balanced", grams: Double = 10.0) =
        AutosensData.CarbsInPast(start, grams, remaining = grams, foodType = type)

    private fun calculate(entries: List<AutosensData.CarbsInPast>, age: Int = 40, deviation: Double = 0.0, enabled: Boolean = true, isf: Double = 55.58743788) =
        MealAbsorptionPolicy.calculate(start + age * 60_000L, deviation, 8.0, 10.3, isf, entries.sumOf { it.remaining }, entries, enabled)

    @Test fun september30MinimumAloneDoesNotEraseTenGramsInFortyMinutes() {
        val meal = entry()
        var oldCob = 10.0
        for (age in 5..40 step 5) {
            val result = calculate(listOf(meal), age)
            assertTrue(result.typedFallback)
            assertEquals(result.totalG, result.observedG + result.fallbackG, 1e-9)
            meal.remaining -= result.totalG
            oldCob = (oldCob - 8 * 10.3 / 55.58743788).coerceAtLeast(0.0)
        }
        assertEquals(0.0, oldCob)
        assertEquals(10 * MealAbsorptionSchedule.remainingFraction(40.0, "balanced"), meal.remaining, 1e-9)
        assertTrue(meal.remaining > 6.0)
    }

    @Test fun measuredDeviationCanStillConsumeFoodFasterThanTheSchedule() {
        val r = calculate(listOf(entry()), deviation = 30.0)
        assertEquals(30 * 10.3 / 55.58743788, r.totalG, 1e-8)
        assertEquals(0.0, r.fallbackG)
    }

    @Test fun rescueUnknownAndMixedFastFoodPreserveLegacyMinimum() {
        for (entries in listOf(listOf(entry("fast")), listOf(entry(null)), listOf(entry(), entry("fast")))) {
            val r = calculate(entries)
            assertFalse(r.typedFallback)
            assertEquals(8 * 10.3 / 55.58743788, r.totalG, 1e-8)
        }
    }

    @Test fun otherAlgorithmsPreserveTheirMinimum() {
        val r = calculate(listOf(entry()), enabled = false)
        assertFalse(r.typedFallback)
        assertEquals(8 * 10.3 / 55.58743788, r.totalG, 1e-8)
    }

    @Test fun loweringFoodIsfDoesNotAccelerateTypedFallback() {
        assertEquals(calculate(listOf(entry()), isf = 55.0).totalG, calculate(listOf(entry()), isf = 30.0).totalG, 1e-8)
    }

    @Test fun typedFallbackNeverAddsMassOrExceedsTheOldFallback() {
        for (type in listOf("balanced", "slow")) for (grams in listOf(1.0, 10.0, 80.0)) {
            val meal = entry(type, grams)
            for (age in 5..240 step 5) {
                val before = meal.remaining
                val r = calculate(listOf(meal), age)
                assertTrue(r.totalG >= 0.0 && r.totalG <= before + 1e-9)
                assertTrue(r.totalG <= 8 * 10.3 / 55.58743788 + 1e-9)
                meal.remaining -= r.totalG
            }
        }
    }

    @Test fun scheduleKeepsMassAcrossForecastHorizonsAndFoodTypes() {
        for (type in listOf("fast", "balanced", "slow")) {
            val duration = MealAbsorptionSchedule.durationMinutes(type)
            val peak = MealAbsorptionSchedule.peakMinutes(type)
            val full = MealAbsorptionSchedule.weights((duration / 5).toInt(), peak, duration)
            val short = MealAbsorptionSchedule.weights(6, peak, duration)
            assertEquals(1.0, full.sum(), 1e-9)
            assertArrayEquals(full.take(6).toDoubleArray(), short, 1e-9)
            assertEquals(0.0, MealAbsorptionSchedule.remainingFraction(duration, type))
        }
    }

    @Test fun invalidSensitivityDoesNotEraseCarbs() {
        for (isf in listOf(Double.NaN, 0.0, -1.0, Double.POSITIVE_INFINITY)) {
            assertEquals(0.0, calculate(listOf(entry()), isf = isf).totalG)
        }
    }
}
