package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.math.floor

/** Select only basal, never a bolus, after SMB has already been prohibited. */
internal object GuardedBasalSelector {
    data class Choice(val rate: Double, val reason: String)

    fun select(
        maximumRate: Double,
        basalStep: Double,
        bg: Double,
        delta: Double,
        shortDelta: Double,
        target: Double,
        durationMinutes: Int = 30,
        forecast: (Double) -> List<Int>
    ): Choice {
        if (listOf(maximumRate, basalStep, bg, delta, shortDelta, target).any { !it.isFinite() } ||
            maximumRate <= 0 || basalStep <= 0 || target <= 0 || durationMinutes <= 0
        ) return Choice(0.0, "invalid inputs")
        if (bg <= target) return Choice(0.0, "at or below target")
        // A slight decline well above target still needs a forecast. Retain a separate
        // stop when either observed trend reaches target within the proposed temp basal.
        val trendFloor = bg + minOf(0.0, delta, shortDelta) * durationMinutes / 5.0
        if (trendFloor <= target) return Choice(0.0, "trend reaches target")
        fun safe(rate: Double): Boolean {
            val values = forecast(rate)
            // Require the complete four-hour forecast, not a partial or flat fallback.
            return values.size >= 48 && values.all { it > 0 && it.toDouble() >= target }
        }
        val steps = floor((maximumRate + 1e-9) / basalStep).toInt().coerceAtMost(1000)
        if (steps <= 0) return Choice(0.0, "below pump step")
        val upper = steps * basalStep
        if (safe(upper)) return Choice(upper, "profile basal forecast safe")
        if (!safe(0.0)) return Choice(0.0, "risk remains without basal")
        var lo = 0
        var hi = steps
        while (hi - lo > 1) {
            val middle = (lo + hi) / 2
            if (safe(middle * basalStep)) lo = middle else hi = middle
        }
        return Choice(lo * basalStep, "reduced basal forecast safe")
    }
}
