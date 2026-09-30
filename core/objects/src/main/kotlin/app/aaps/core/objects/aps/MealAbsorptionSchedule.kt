package app.aaps.core.objects.aps

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.pow

/** Shared mass-normalized schedule; it is a model, not measured absorption. */
object MealAbsorptionSchedule {
    fun durationMinutes(foodType: String?): Double = when (foodType?.lowercase()) {
        "fast" -> 45.0
        "slow" -> 240.0
        else -> 165.0
    }

    fun peakMinutes(foodType: String?): Double = when (foodType?.lowercase()) {
        "fast" -> 15.0
        "slow" -> 80.0
        else -> 50.0
    }

    fun weights(steps: Int, peakMinutes: Double, durationMinutes: Double): DoubleArray {
        if (steps <= 0) return doubleArrayOf()
        if (!peakMinutes.isFinite() || !durationMinutes.isFinite() || durationMinutes < 15.0) return DoubleArray(steps)
        val peak = peakMinutes.coerceIn(15.0, durationMinutes.coerceAtLeast(30.0))
        val sigma = (durationMinutes / 3.2).coerceAtLeast(20.0)
        val values = DoubleArray(steps)
        var sum = 0.0
        for (i in 0 until ceil(durationMinutes / 5.0).toInt()) {
            val minute = (i + 1) * 5.0
            if (minute > durationMinutes) continue
            val weight = exp(-0.5 * ((minute - peak) / sigma).pow(2.0))
            if (i < steps) values[i] = weight
            sum += weight
        }
        return if (sum > 0) DoubleArray(steps) { values[it] / sum } else DoubleArray(steps)
    }

    fun remainingFraction(elapsedMinutes: Double, foodType: String?): Double {
        val duration = durationMinutes(foodType)
        if (!elapsedMinutes.isFinite()) return 0.0
        if (elapsedMinutes <= 0.0) return 1.0
        if (elapsedMinutes >= duration) return 0.0
        val weights = weights(ceil(duration / 5.0).toInt(), peakMinutes(foodType), duration)
        val absorbed = weights.withIndex().sumOf { (i, weight) ->
            weight * ((elapsedMinutes - i * 5.0) / 5.0).coerceIn(0.0, 1.0)
        }
        return (1.0 - absorbed).coerceIn(0.0, 1.0)
    }
}
