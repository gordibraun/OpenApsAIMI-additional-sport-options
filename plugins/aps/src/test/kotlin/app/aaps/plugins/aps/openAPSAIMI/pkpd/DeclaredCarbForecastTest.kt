package app.aaps.plugins.aps.openAPSAIMI.pkpd

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DeclaredCarbForecastTest {
    private val now = 20_000_000L
    private fun entry(ageMinutes: Int, grams: Double, type: String) =
        DeclaredCarbForecast.Entry(now - ageMinutes * 60_000L, grams, type)
    private fun forecast(entries: List<DeclaredCarbForecast.Entry>, cob: Double = 2.14, horizon: Int = 240) =
        DeclaredCarbForecast.calculate(entries, now, cob, 12.33, 4.5, horizon)

    @Test fun expiredExerciseCarbsCannotEraseOrRetypeTheNewOrdinaryMeal() {
        val meal = entry(27, 10.0, "balanced")
        val alone = forecast(listOf(meal))
        val withOldExercise = forecast(listOf(entry(158, 4.0, "fast"), meal))
        assertEquals(alone, withOldExercise)
        assertEquals(2.14, withOldExercise.cobG, 1e-9)
        assertEquals("balanced", withOldExercise.dominantFoodType)
        assertEquals(2.14 * 4.5, withOldExercise.impactMgdlPer5m!!.sum(), 1e-9)
    }

    @Test fun concurrentFoodsShareRemainingMassWithoutSubtractingOriginalExerciseGrams() {
        val inputs = listOf(entry(0, 4.0, "fast"), entry(0, 10.0, "balanced"))
        val result = forecast(inputs, cob = 7.0)
        assertEquals(7.0, result.cobG)
        assertEquals("balanced", result.dominantFoodType)
        assertEquals(7.0 * 4.5, result.impactMgdlPer5m!!.sum(), 1e-9)
        assertEquals(result, forecast(inputs.reversed(), cob = 7.0))
    }

    @Test fun equalMixedMassDoesNotInventADominantFoodType() {
        assertNull(forecast(listOf(entry(0, 5.0, "fast"), entry(0, 5.0, "slow")), 10.0).dominantFoodType)
    }

    @Test fun emptyCobDoesNotInheritTheFirstHistoricalFoodType() {
        val result = forecast(listOf(entry(150, 4.0, "fast"), entry(25, 10.0, "balanced")), 0.0)
        assertEquals(0.0, result.cobG)
        assertNull(result.dominantFoodType)
        assertNull(result.impactMgdlPer5m)
    }

    @Test fun fullyAbsorbedEntriesCannotCreateNewCarbs() {
        val result = forecast(listOf(entry(300, 10.0, "balanced")), 10.0)
        assertEquals(0.0, result.cobG)
        assertNull(result.dominantFoodType)
        assertEquals(emptyList<Double>(), result.impactMgdlPer5m)
    }

    @Test fun futureEntriesDoNotStealCurrentMealMassOrType() {
        val current = entry(20, 10.0, "balanced")
        assertEquals(forecast(listOf(current)), forecast(listOf(current, entry(-20, 100.0, "fast"))))
    }

    @Test fun shortHorizonDoesNotAccelerateTheMeal() {
        val entries = listOf(entry(5, 20.0, "slow"))
        val short = forecast(entries, 15.0, 30)
        val full = forecast(entries, 15.0)
        assertEquals(full.impactMgdlPer5m!!.take(6), short.impactMgdlPer5m)
        assertTrue(short.impactMgdlPer5m!!.sum() < full.impactMgdlPer5m.sum())
    }

    @Test fun unavailableHistoryKeepsAapsCobInsteadOfGuessingExerciseCoverage() {
        val result = forecast(emptyList())
        assertEquals(2.14, result.cobG)
        assertNull(result.impactMgdlPer5m)
    }
}
