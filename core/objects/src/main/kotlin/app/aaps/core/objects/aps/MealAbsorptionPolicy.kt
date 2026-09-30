package app.aaps.core.objects.aps

import app.aaps.core.interfaces.aps.AutosensData
import kotlin.math.max
import kotlin.math.min

object MealAbsorptionPolicy {
    data class Result(val observedG: Double, val fallbackG: Double, val totalG: Double, val typedFallback: Boolean)

    fun calculate(
        now: Long,
        deviation: Double,
        minimumImpact: Double,
        ic: Double,
        isf: Double,
        previousCob: Double,
        entries: List<AutosensData.CarbsInPast>,
        useTypedFallback: Boolean
    ): Result {
        if (!ic.isFinite() || ic <= 0 || !isf.isFinite() || isf <= 0 ||
            !previousCob.isFinite() || previousCob <= 0 || !deviation.isFinite() || !minimumImpact.isFinite()
        ) return Result(0.0, 0.0, 0.0, false)
        val observed = max(0.0, deviation) * ic / isf
        val legacyFloor = max(0.0, minimumImpact) * ic / isf
        // New entries in this bucket are added to COB after this deduction.
        val active = entries.filter { it.time <= now - 5 * 60_000L && it.remaining > 0 }
        val typed = useTypedFallback && active.isNotEmpty() && active.all {
            it.carbs.isFinite() && it.carbs > 0 && it.remaining.isFinite() &&
                it.foodType?.lowercase() in listOf("balanced", "slow")
        }
        // Preserve rescue/fast and unknown-food behavior, including mixed meals.
        val floor = if (typed) {
            val scheduled = active.sumOf { entry ->
                val age = (now - entry.time) / 60_000.0
                val fraction = MealAbsorptionSchedule.remainingFraction(age - 5.0, entry.foodType) -
                    MealAbsorptionSchedule.remainingFraction(age, entry.foodType)
                min(entry.remaining, entry.carbs * fraction.coerceAtLeast(0.0))
            }
            min(legacyFloor, scheduled)
        } else legacyFloor
        val total = if (typed) max(observed, floor).coerceAtMost(previousCob) else max(observed, floor)
        val countedObserved = min(observed, total)
        return Result(countedObserved, (total - countedObserved).coerceAtLeast(0.0), total, typed)
    }
}
