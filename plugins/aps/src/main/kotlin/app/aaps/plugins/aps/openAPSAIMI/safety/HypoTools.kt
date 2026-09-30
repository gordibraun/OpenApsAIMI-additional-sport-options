package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.math.roundToInt

object HypoTools {

    // OpenAPS min_bg is the lower target, never the observed or predicted minimum.
    fun thresholdFromTarget(lowerTargetMgdl: Double, lgsThreshold: Int?): Double =
        maxOf(lowerTargetMgdl - 0.5 * (lowerTargetMgdl - 40.0), lgsThreshold?.toDouble() ?: 0.0)

    fun finalForecastFloor(hypoThreshold: Double, targetMgdl: Double): Double =
        maxOf(hypoThreshold + 15.0, targetMgdl - 10.0)

    fun calculateMinutesAboveThreshold(bg: Double, slope: Double, thresholdBG: Double): Int {
        val bgDiff = bg - thresholdBG
        if (slope >= 0) return Int.MAX_VALUE
        val minutes = bgDiff / -slope
        return if (minutes.isFinite() && minutes > 0) minutes.roundToInt() else Int.MAX_VALUE
    }

    fun calculateDropPerHour(bgHistory: List<Float>, windowMinutes: Float): Float {
        if (bgHistory.size < 2) return 0f
        val first = bgHistory.first()
        val last  = bgHistory.last()
        val drop  = (first - last)
        return drop * (60f / windowMinutes)
    }
}
