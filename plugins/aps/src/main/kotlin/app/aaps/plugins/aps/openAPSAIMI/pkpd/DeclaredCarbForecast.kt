package app.aaps.plugins.aps.openAPSAIMI.pkpd

import kotlin.math.min

internal object DeclaredCarbForecast {
    data class Entry(val timestamp: Long, val amount: Double, val foodType: String)
    data class Result(
        val cobG: Double,
        val physiologicalCobG: Double,
        val dominantFoodType: String?,
        val impactMgdlPer5m: List<Double>?
    )

    fun calculate(
        entries: List<Entry>,
        now: Long,
        aapsCobG: Double,
        delta: Double,
        carbSensitivityMgdlPerGram: Double?,
        horizonMinutes: Int = 240
    ): Result {
        val cob = aapsCobG.takeIf { it.isFinite() && it > 0.0 } ?: return Result(0.0, 0.0, null, null)
        val current = entries.filter { it.timestamp <= now && it.amount.isFinite() && it.amount > 0.0 }
        if (current.isEmpty()) return Result(cob, 0.0, null, null)
        val remaining = current.map { entry ->
            entry.amount * CarbAbsorptionModel.remainingFraction((now - entry.timestamp) / 60_000.0, entry.foodType, delta)
        }
        val physiologicalCob = remaining.sum()
        if (physiologicalCob <= 0.0) return Result(0.0, 0.0, null, emptyList())

        // AAPS has already removed absorbed carbs. Past exercise must not subtract
        // the original exercise-carb entry from the remaining mass of a newer meal.
        val forecastCob = min(cob, physiologicalCob)
        val scale = forecastCob / physiologicalCob
        val byType = current.indices.groupBy { current[it].foodType }.mapValues { (_, indices) ->
            indices.sumOf { remaining[it] * scale }
        }
        val foodType = byType.maxByOrNull { it.value }
            ?.takeIf { it.value > 0.0 && it.value >= forecastCob * 0.55 }?.key
        val timeline = carbSensitivityMgdlPerGram?.takeIf { it.isFinite() && it > 0.0 }?.let { csf ->
            List((horizonMinutes / 5).coerceAtLeast(0)) { index ->
                current.sumOf { entry ->
                    val start = (now - entry.timestamp) / 60_000.0 + index * 5
                    val before = CarbAbsorptionModel.remainingFraction(start, entry.foodType, delta)
                    val after = CarbAbsorptionModel.remainingFraction(start + 5, entry.foodType, delta)
                    entry.amount * scale * csf * (before - after).coerceIn(0.0, 1.0)
                }
            }
        }
        return Result(forecastCob, physiologicalCob, foodType, timeline)
    }
}
