package app.aaps.pump.combowatch.regulation

import kotlin.math.ceil

/**
 * How many grams of carbohydrate a forecast is short of the target: enough to lift its lowest
 * point up to the target, by the carbohydrate sensitivity (ISF over the carb ratio). Small
 * shortfalls are not worth eating for; large ones are capped at what one sitting can be.
 */
object CarbsNeeded {

    /** Under this the forecast is as good as on target. */
    const val MIN_DEFICIT_MGDL = 3.0
    const val MAX_GRAMS = 60

    fun gramsToTarget(forecastMgdl: List<Int>, targetMgdl: Double, sensitivityMgdlPerU: Double, carbRatioGPerU: Double): Int? {
        val lowest = forecastMgdl.minOrNull() ?: return null
        val deficit = targetMgdl - lowest
        if (deficit < MIN_DEFICIT_MGDL) return null
        val mgdlPerGram = if (carbRatioGPerU > 0.0) sensitivityMgdlPerU / carbRatioGPerU else return null
        if (!mgdlPerGram.isFinite() || mgdlPerGram <= 0.0) return null
        return ceil(deficit / mgdlPerGram).toInt().coerceIn(1, MAX_GRAMS)
    }
}
