package app.aaps.pump.combowatch.regulation

import kotlin.math.roundToInt

/**
 * Whether insulin is doing what the forecast said it would.
 *
 * The forecast made an hour or two ago said where glucose would be now, counting the insulin at
 * work, the carbohydrates entered and the basal. When glucose is high and sits far above what
 * several of those forecasts promised, while boluses were given in the meantime, insulin is not
 * acting as the model assumed: the set or the cannula may be failing, the insulin may be spoilt,
 * or the body is resisting it (illness, a day spent sitting). The data cannot tell those apart;
 * the owner can, so the verdict is a question for the owner, not a dose (24 Oct 2026: 18 U over
 * five hours against glucose that stayed at 210-277, with nobody told).
 */
object InsulinEffectiveness {

    enum class Level { NONE, SUSPECT, STRONG }

    /** A forecast the watch made: glucose every five minutes from [atEpochMs], the first value being glucose then. */
    class ForecastMade(val atEpochMs: Long, val seriesMgdl: List<Int>)

    class Verdict(
        val level: Level,
        val bgMgdl: Int,
        /** How far above the forecasts' promise glucose is now, mg/dL (median over the comparisons). */
        val residualMgdl: Int,
        val comparisons: Int,
        /** Boluses given in the last two hours, units. */
        val bolusesU: Double,
        /** One or two sentences for the owner. */
        val text: String
    ) {
        companion object {
            fun none(bg: Int = 0) = Verdict(Level.NONE, bg, 0, 0, 0.0, "")
        }
    }

    fun evaluate(nowEpochMs: Long, readings: List<GlucoseReading>, forecasts: List<ForecastMade>, delivery: DeliveryLog): Verdict {
        val trend = GlucoseTrend.from(readings, nowEpochMs) ?: return Verdict.none()
        if ((nowEpochMs - trend.atEpochMs) / 60_000.0 > WatchRegulator.MAX_READING_AGE_MINUTES) return Verdict.none()
        val bg = trend.mgdl.roundToInt()
        val residuals = forecasts.mapNotNull { made ->
            val age = nowEpochMs - made.atEpochMs
            if (age !in LOOKBACK_MIN_MS..LOOKBACK_MAX_MS) return@mapNotNull null
            val step = (age / 300_000.0).roundToInt()
            made.seriesMgdl.getOrNull(step)?.let { predicted -> bg - predicted }
        }.sorted()
        val bolusesU = delivery.boluses.filter { it.atEpochMs in (nowEpochMs - WINDOW_MS)..nowEpochMs }.sumOf { it.units }
        if (residuals.size < MIN_COMPARISONS || bg < MIN_BG_MGDL || bolusesU < MIN_BOLUS_U) return Verdict.none(bg)
        // The lower median: with an even count the smaller of the two middle values, the conservative one.
        val residual = residuals[(residuals.size - 1) / 2]
        val level = when {
            residual >= STRONG_RESIDUAL_MGDL && bg >= STRONG_BG_MGDL -> Level.STRONG
            residual >= SUSPECT_RESIDUAL_MGDL                        -> Level.SUSPECT
            else                                                     -> Level.NONE
        }
        if (level == Level.NONE) return Verdict.none(bg)
        val u = String.format(java.util.Locale.US, "%.1f", bolusesU)
        val text = (if (level == Level.STRONG) "Инсулин почти не действует: " else "Инсулин действует хуже, чем ожидалось: ") +
            "сахар $bg" + (if (trend.known) " (${if (trend.delta >= 0) "+" else "−"}${kotlin.math.abs(trend.delta).roundToInt()} за 5 мин)" else "") +
            ", за 2 ч подано $u ЕД болюсами, прогноз обещал около ${bg - residual} — по факту на $residual выше."
        return Verdict(level, bg, residual, residuals.size, bolusesU, text)
    }

    /** Forecasts made this long ago are compared with glucose now: long enough for insulin to have acted, not so long as to be another story. */
    const val LOOKBACK_MIN_MS = 60 * 60_000L
    const val LOOKBACK_MAX_MS = 120 * 60_000L
    const val MIN_COMPARISONS = 2

    /** Below this glucose nothing is said: insulin that is late to act at 160 is not an emergency. */
    const val MIN_BG_MGDL = 180
    const val STRONG_BG_MGDL = 200
    const val SUSPECT_RESIDUAL_MGDL = 60
    const val STRONG_RESIDUAL_MGDL = 100

    /** Without boluses in the window a high glucose is an unannounced meal or basal, not insulin failing. */
    const val WINDOW_MS = 2 * 60 * 60_000L
    const val MIN_BOLUS_U = 1.0
}
