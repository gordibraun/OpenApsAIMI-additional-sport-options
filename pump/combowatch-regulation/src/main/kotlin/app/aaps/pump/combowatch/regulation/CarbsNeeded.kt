package app.aaps.pump.combowatch.regulation

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

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

    /**
     * The phone's own rule, AIMI's «Углеводы по финальному прогнозу», so that the watch alone
     * arrives at the same figure the phone would show: the lowest point of the forecast between
     * +20 and +120 min, lifted to the target plus a pressure buffer, by the carbohydrate
     * sensitivity (ISF over the carb ratio), rounded. Zero grams is a result, not an absence; the
     * phone shows the figure from its threshold ([PHONE_SHOW_FROM_G] on this phone) and so does the face.
     */
    data class PhoneLikeAsk(val grams: Int, val withinMinutes: Int)

    /** +20 min: the earliest zone where new basal or a microbolus still changes the outcome (AIMI's actionStartIndex). */
    const val PHONE_WINDOW_START_INDEX = 4
    /** +120 min, the end of the phone's working zone. */
    const val PHONE_WINDOW_END_INDEX = 24
    /** The phone's carbsReqThreshold setting on the owner's phone (31.10.2026). */
    const val PHONE_SHOW_FROM_G = 5

    fun likeThePhone(
        forecastMgdl: List<Int>, targetMgdl: Double, sensitivityMgdlPerU: Double, carbRatioGPerU: Double, bufferMgdl: Double = 0.0,
    ): PhoneLikeAsk? {
        if (!targetMgdl.isFinite() || targetMgdl <= 0.0 || carbRatioGPerU <= 0.0) return null
        val csf = sensitivityMgdlPerU / carbRatioGPerU
        if (!csf.isFinite() || csf <= 0.0) return null
        val end = min(forecastMgdl.lastIndex, PHONE_WINDOW_END_INDEX)
        if (forecastMgdl.size <= PHONE_WINDOW_START_INDEX || end < PHONE_WINDOW_START_INDEX) return null
        val window = (PHONE_WINDOW_START_INDEX..end).map { it to forecastMgdl[it] }.filter { (_, value) -> value > 0 }
        val (minIndex, minValue) = window.minByOrNull { it.second } ?: return null
        val deficit = (targetMgdl + bufferMgdl - minValue).coerceAtLeast(0.0)
        return PhoneLikeAsk((deficit / csf).roundToInt().coerceAtLeast(0), minIndex * 5)
    }

    /** AIMI's pressure buffer on top of the target: recent microboluses or a lot of insulin on board ask for a few more mg/dL. */
    fun pressureBufferMgdl(recentSmb30U: Double, iobU: Double, nightNoMeal: Boolean): Double = when {
        nightNoMeal && iobU >= 1.5         -> 8.0
        recentSmb30U >= 1.5                -> 10.0
        recentSmb30U >= 0.8 || iobU >= 3.0 -> 5.0
        else                               -> 0.0
    }
}
