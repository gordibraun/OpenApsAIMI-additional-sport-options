package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.math.abs
import kotlin.math.min

/**
 * Extra insulin is not given on a jump of the sensor that the next reading has yet to confirm, nor while
 * the sensor's readings jump back and forth.
 *
 * Night of 14.11.2026, a sensor on its second day: readings five minutes apart went 111, 118, 137, 118,
 * 90, 88 ... 96, 120, 149, 149, 131 ... 110, 137, 145, 132 - twenty to thirty mg/dL up and down with
 * nothing eaten and no insulin to explain it. Each jump up was answered at once with a 500 % temporary
 * basal and a microbolus, each jump down only with a few minutes without basal: a noise that is even in
 * both directions became about two units of extra insulin over four hours, and glucose was in the fifties
 * and sixties by five in the morning.
 *
 * The guard only ever takes extra insulin away - a microbolus, and basal above the profile's rate - and
 * only for the readings in question. Lowering basal or stopping it is never touched. It does not smooth
 * anything: the loop goes on working with the readings as they are.
 *
 *  - One reading that rises much faster than the rise before it, or faster than glucose rises without
 *    fast carbohydrates, waits for the next reading. A real rise is still there five minutes later.
 *  - While the readings of the last three quarters of an hour have gone up and straight back down (or
 *    down and back up) by twenty mg/dL in all, extra insulin waits - unless the level is clearly high
 *    by the middle one of the last three readings, where the noise does not change the conclusion.
 */
object SensorNoiseGuard {

    data class Reading(val atEpochMs: Long, val mgdl: Double)

    data class Input(
        /** The sensor's readings as they came, in any order. */
        val readings: List<Reading>,
        val nowEpochMs: Long,
        /** The microbolus the loop decided on, units. */
        val smbU: Double,
        /** The temporary basal the loop decided on, U/h; null when it asks for none. */
        val rateUh: Double?,
        val profileBasalUh: Double
    )

    data class Decision(
        val smbU: Double,
        val rateUh: Double?,
        val held: Boolean,
        /** For the loop's reason line and the log; null when nothing was held. */
        val reason: String? = null,
        val zigzagMgdl: Double = 0.0,
        val levelMgdl: Double? = null
    )

    const val WINDOW_MS = 45 * 60_000L

    /** Movement taken straight back within the window, mg/dL in all, from which the sensor counts as noisy. */
    const val ZIGZAG_NOISY_MGDL = 20.0

    /** At and above this by the middle one of the last three readings the level is high whatever the noise. */
    const val CLEARLY_HIGH_MGDL = 170.0

    /** A rise per reading that glucose does not make without fast carbohydrates (4 mg/dL a minute). */
    const val FAST_RISE_MGDL = 20.0

    /** A rise per reading that counts as a jump when the reading before rose less than half as much. */
    const val ABRUPT_RISE_MGDL = 12.0

    private const val NEWEST_MAX_AGE_MS = 7 * 60_000L
    private const val LEVEL_SPAN_MS = 16 * 60_000L
    private val STEP_MS = 200_000L..400_000L
    private const val BASAL_TOLERANCE = 1.05

    fun evaluate(input: Input): Decision {
        val unchanged = Decision(input.smbU, input.rateUh, held = false)
        val extraBasal = input.rateUh != null && input.profileBasalUh > 0.0 && input.rateUh > input.profileBasalUh * BASAL_TOLERANCE
        if (input.smbU <= 0.0 && !extraBasal) return unchanged

        val points = input.readings
            .filter { it.mgdl.isFinite() && it.mgdl > 38.0 && it.atEpochMs <= input.nowEpochMs + 60_000L && input.nowEpochMs - it.atEpochMs <= WINDOW_MS }
            .sortedBy { it.atEpochMs }
            .distinctBy { it.atEpochMs }
        if (points.size < 3) return unchanged
        val newest = points.last()
        if (input.nowEpochMs - newest.atEpochMs > NEWEST_MAX_AGE_MS) return unchanged

        val lastThree = points.takeLast(3).filter { newest.atEpochMs - it.atEpochMs <= LEVEL_SPAN_MS }.map { it.mgdl }.sorted()
        val level = if (lastThree.size == 3) lastThree[1] else lastThree.first()
        val zigzag = zigzag(points)
        val jump = unconfirmedRise(points)

        val reason = when {
            level >= CLEARLY_HIGH_MGDL -> null
            jump != null               ->
                "Скачок сенсора +${"%.0f".format(jump)} за одно показание не подтверждён: добавка инсулина ждёт следующего показания"
            zigzag >= ZIGZAG_NOISY_MGDL ->
                "Сенсор шумит (за 45 мин ${"%.0f".format(zigzag)} мг/дл скачков туда-обратно, уровень по трём показаниям ${"%.0f".format(level)}): " +
                    "добавки инсулина придержаны"
            else                       -> null
        } ?: return unchanged.copy(zigzagMgdl = zigzag, levelMgdl = level)

        return Decision(
            smbU = 0.0,
            rateUh = input.rateUh?.let { if (extraBasal) min(it, input.profileBasalUh) else it },
            held = true,
            reason = reason,
            zigzagMgdl = zigzag,
            levelMgdl = level
        )
    }

    /**
     * How much of the readings' movement was taken straight back: for each pair of neighbouring changes
     * of opposite sign, the smaller of the two. A real curve turns gently - its changes near a peak are
     * small - while noise jumps and returns.
     */
    internal fun zigzag(sorted: List<Reading>): Double {
        var total = 0.0
        var previous: Double? = null
        for (i in 1 until sorted.size) {
            if (sorted[i].atEpochMs - sorted[i - 1].atEpochMs !in STEP_MS) { previous = null; continue }
            val change = sorted[i].mgdl - sorted[i - 1].mgdl
            val before = previous
            if (before != null && before * change < 0) total += min(abs(before), abs(change))
            previous = change
        }
        return total
    }

    /** The newest reading's rise when no earlier reading supports it; null when it is not such a jump. */
    internal fun unconfirmedRise(sorted: List<Reading>): Double? {
        if (sorted.size < 2) return null
        val newest = sorted[sorted.size - 1]
        val before = sorted[sorted.size - 2]
        if (newest.atEpochMs - before.atEpochMs !in STEP_MS) return null
        val rise = newest.mgdl - before.mgdl
        if (rise >= FAST_RISE_MGDL) return rise
        if (rise < ABRUPT_RISE_MGDL || sorted.size < 3) return null
        val earlier = sorted[sorted.size - 3]
        if (before.atEpochMs - earlier.atEpochMs !in STEP_MS) return null
        val riseBefore = before.mgdl - earlier.mgdl
        return if (rise >= 2.0 * maxOf(riseBefore, 3.0)) rise else null
    }
}
