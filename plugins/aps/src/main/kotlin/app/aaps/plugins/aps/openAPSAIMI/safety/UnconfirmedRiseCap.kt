package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.math.max

/**
 * ON TRIAL since 15.11.2026, at the owner's «да»: the first microbolus on a rise that one reading alone shows
 * is held to half a unit. The rest is left to the next reading, five minutes on: it either confirms the rise -
 * and the loop then gives what it finds right - or shows that there was none.
 *
 * Night of 15.11.2026: readings 120, 113, 112, [one missed], 139, then 130, 125, 121, 119 - a jump no second
 * reading supported, from a sensor the body was shielding (-96 dBm). The loop took it for an unannounced meal,
 * forecast 270 and gave 2.4 U at once, its largest microbolus in four weeks; glucose was at 53 an hour and a
 * half later although basal had been at zero for two hours.
 *
 * A rise is unconfirmed when the newest reading stands at least [MIN_STEP_MGDL_PER_5MIN] above the one before it,
 * counted per five minutes, and the step before that one is not known - a reading was missed, so the rise is
 * seen across a gap - or was no rise itself (at most [CONFIRMING_STEP_MGDL_PER_5MIN]).
 *
 * Replayed over four weeks of this owner's data, 606 microboluses: 20 would have been cut. In 11 of them the
 * rise held ten minutes later, and the part above half a unit would have come five minutes late; in 6 glucose
 * had fallen back by then, two of those ending below 70.
 *
 * It is not [SensorNoiseGuard] (wired in for a few hours on 14.11 and taken out): no dose is held back
 * altogether, basal is not touched, and a jump across a missed reading - which that guard could not see - is
 * the very case it is for.
 */
object UnconfirmedRiseCap {

    data class Reading(val atEpochMs: Long, val mgdl: Double)

    data class Decision(
        /** The microbolus that may go, units. */
        val smbU: Double,
        val capped: Boolean,
        /** The rise the newest reading shows, mg/dL per five minutes. */
        val stepMgdlPer5Min: Double = 0.0,
        /** Over how many minutes that rise was seen: five normally, ten or more across a missed reading. */
        val overMinutes: Double = 0.0,
        val acrossMissedReading: Boolean = false,
        /** The reading the decision rests on; a note is written once per reading. */
        val newestAtEpochMs: Long = 0L
    )

    /** The most a rise seen in one reading may get at once, units. */
    const val CAP_U = 0.5

    /** A rise per five minutes from which the question is asked at all. */
    const val MIN_STEP_MGDL_PER_5MIN = 8.0

    /** The reading before must have risen by more than this, per five minutes, for the rise to count as confirmed. */
    const val CONFIRMING_STEP_MGDL_PER_5MIN = 2.0

    /** Two readings further apart than this have a missed one between them. */
    private const val MISSED_READING_MS = 450_000L

    /** A decision made on a reading older than this is not a decision on that reading's jump. */
    private const val NEWEST_MAX_AGE_MS = 7 * 60_000L

    /**
     * @param readings the sensor's readings of the last half hour or more, in any order
     * @param smbU the microbolus the loop decided on
     * @param smbGivenSinceNewestU microboluses already given since the newest reading was taken: with them
     *   the reading's share is [CAP_U] in all, however many times the loop runs on it
     */
    fun evaluate(readings: List<Reading>, nowEpochMs: Long, smbU: Double, smbGivenSinceNewestU: Double = 0.0): Decision {
        val unchanged = Decision(smbU, capped = false)
        if (!(smbU > 0.0)) return unchanged
        // One point per reading: the same sample stored twice a few seconds apart is one sample.
        val points = ArrayList<Reading>()
        for (reading in readings.filter { it.mgdl.isFinite() && it.mgdl > 38.0 && it.atEpochMs <= nowEpochMs + 60_000L }.sortedBy { it.atEpochMs })
            if (points.isEmpty() || reading.atEpochMs - points.last().atEpochMs >= 60_000L) points += reading
        if (points.size < 2) return unchanged
        val newest = points[points.size - 1]
        val before = points[points.size - 2]
        if (nowEpochMs - newest.atEpochMs > NEWEST_MAX_AGE_MS) return unchanged
        val overMinutes = (newest.atEpochMs - before.atEpochMs) / 60_000.0
        val step = (newest.mgdl - before.mgdl) / overMinutes * 5.0
        if (step < MIN_STEP_MGDL_PER_5MIN) return unchanged
        val acrossMissedReading = newest.atEpochMs - before.atEpochMs > MISSED_READING_MS
        val earlier = points.getOrNull(points.size - 3)?.takeIf { before.atEpochMs - it.atEpochMs <= MISSED_READING_MS }
        val stepBefore = earlier?.let { (before.mgdl - it.mgdl) / ((before.atEpochMs - it.atEpochMs) / 60_000.0) * 5.0 }
        val confirmed = !acrossMissedReading && stepBefore != null && stepBefore > CONFIRMING_STEP_MGDL_PER_5MIN
        if (confirmed) return unchanged
        // In steps the pump can give; what is left of the reading's share after what it has had already.
        val allowed = max(0.0, Math.floor((CAP_U - smbGivenSinceNewestU) * 10.0 + 1e-6) / 10.0)
        if (smbU <= allowed + 1e-6) return unchanged
        return Decision(allowed, capped = true, stepMgdlPer5Min = step, overMinutes = overMinutes, acrossMissedReading = acrossMissedReading, newestAtEpochMs = newest.atEpochMs)
    }
}
