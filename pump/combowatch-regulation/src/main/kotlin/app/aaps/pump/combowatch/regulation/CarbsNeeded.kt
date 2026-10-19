package app.aaps.pump.combowatch.regulation

import kotlin.math.ceil

/**
 * How many grams of carbohydrate keep a forecast out of a low: nothing while its lowest point
 * stays at or above the floor, otherwise enough to lift that point clear of it, by the
 * carbohydrate sensitivity (ISF over the carb ratio). The shortfall to the *target* was shown
 * until 19 Oct 2026 and read as "eat this" even with a forecast already rising to 135: the owner
 * ate, and the number hardly moved, because the target was still far. Large amounts are capped at
 * what one sitting can be.
 */
object CarbsNeeded {

    /** A forecast dipping under this is a low on the way. */
    const val FLOOR_MGDL = 80.0

    /** What the lowest point is lifted to: clear of the floor, not to the target. */
    const val LIFT_TO_MGDL = 90.0
    const val MAX_GRAMS = 60

    fun gramsToAvoidLow(
        forecastMgdl: List<Int>, sensitivityMgdlPerU: Double, carbRatioGPerU: Double,
        floorMgdl: Double = FLOOR_MGDL, liftToMgdl: Double = LIFT_TO_MGDL,
    ): Int? {
        val lowest = forecastMgdl.minOrNull() ?: return null
        if (lowest >= floorMgdl) return null
        val deficit = liftToMgdl - lowest
        val mgdlPerGram = if (carbRatioGPerU > 0.0) sensitivityMgdlPerU / carbRatioGPerU else return null
        if (!mgdlPerGram.isFinite() || mgdlPerGram <= 0.0) return null
        return ceil(deficit / mgdlPerGram).toInt().coerceIn(1, MAX_GRAMS)
    }
}
