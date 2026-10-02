package app.aaps.pump.combowatch.regulation

import kotlin.math.min

/** A temporary basal as the watch's own driver recorded it on the pump. */
data class TbrSegment(
    val startEpochMs: Long,
    val percent: Int,
    val durationMinutes: Int,
    /** When it was cut short - replaced or cancelled; null if it ran, or is running, to its end. */
    val endedEpochMs: Long? = null,
    /** True when the watch chose it by itself, false when the phone asked for it. */
    val byWatch: Boolean = false
) {

    val scheduledEndEpochMs: Long get() = startEpochMs + durationMinutes * 60_000L
    val endEpochMs: Long get() = min(endedEpochMs ?: scheduledEndEpochMs, scheduledEndEpochMs)
    fun runsAt(epochMs: Long): Boolean = epochMs >= startEpochMs && epochMs < endEpochMs
}

/** A bolus the pump delivered, as its own history recorded it. */
data class BolusRecord(val atEpochMs: Long, val units: Double)

/**
 * What the pump actually delivered lately, kept by the watch from its own driver's events.
 * It is the one thing about insulin the watch knows better than the phone's last snapshot.
 */
class DeliveryLog(tbrs: List<TbrSegment> = emptyList(), val boluses: List<BolusRecord> = emptyList()) {

    val tbrs: List<TbrSegment> = tbrs.sortedBy { it.startEpochMs }

    /** The temporary basal in force at a moment; of two that overlap in the records, the later one. */
    fun tbrAt(epochMs: Long): TbrSegment? = tbrs.lastOrNull { it.runsAt(epochMs) }

    /** The percentage of profile basal the pump was, or is, delivering at a moment. */
    fun percentAt(epochMs: Long): Int = tbrAt(epochMs)?.percent ?: 100

    /** For how many minutes up to a moment the pump has delivered no basal without a break. */
    fun zeroMinutesUpTo(epochMs: Long): Int = minutesAt(0, epochMs)

    /**
     * For how many minutes up to a moment the pump has run at the percentage it runs at then.
     * Renewing a temporary basal at the same percentage does not start the count again.
     * Zero when no temporary basal runs.
     */
    fun heldMinutesUpTo(epochMs: Long): Int = tbrAt(epochMs)?.let { minutesAt(it.percent, epochMs) } ?: 0

    private fun minutesAt(percent: Int, epochMs: Long): Int {
        var cursor = epochMs
        while (true) {
            val segment = tbrs.lastOrNull { it.percent == percent && it.startEpochMs < cursor && it.endEpochMs >= cursor - CONTIGUOUS_GAP_MS } ?: break
            cursor = segment.startEpochMs
        }
        return ((epochMs - cursor) / 60_000L).toInt()
    }

    private companion object {

        /** Two temporary basals with less than this between them count as one stretch: replacing one takes a moment. */
        const val CONTIGUOUS_GAP_MS = 2 * 60_000L
    }
}
