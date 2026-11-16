package app.aaps.pump.combowatch.regulation

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A stop of basal that runs out while glucose is still low and nobody has put another in its place.
 *
 * 16.11.2026: the phone had stopped basal at 08:09 for half an hour. At 08:33 it sent the next stop; the command
 * never reached the watch, while the phone's leases still did - so the watch took the phone to be in charge and
 * waited, and the phone waited twelve minutes for an answer to a command the watch had never had. From 08:39 to
 * 08:49 the pump ran at its profile's rate with glucose at 52-47.
 *
 * The watch reads the sensor itself and keeps its own record of what the pump runs, so it can see this without
 * anybody's help: glucose below [LOW_MGDL] now, and the reduced basal on the pump about to end, or just ended, by
 * running out - not replaced, not cancelled. Then it stops basal for another half hour itself, whoever is in
 * charge. It only ever takes insulin away, only where somebody had already decided to take it away, and only
 * after the phone has had its turn with the reading (the caller waits before asking; see the controller).
 *
 * It does not answer the other question - "glucose is low and nobody stopped basal at all": with the phone in
 * charge that is the phone's decision to make, and a watch stopping basal against it would be undone five
 * minutes later, over and over.
 */
object LowGlucoseHold {

    /** What the watch would do, and why, in the owner's language. */
    class Hold(val glucoseMgdl: Int, val running: TbrSegment, val text: String)

    /**
     * @return the stop to make now, or null when there is nothing for the watch to do here
     */
    fun decide(nowEpochMs: Long, readings: List<GlucoseReading>, delivery: DeliveryLog): Hold? {
        val newest = readings.maxByOrNull { it.atEpochMs } ?: return null
        if (nowEpochMs - newest.atEpochMs > READING_FRESH_MS || newest.atEpochMs > nowEpochMs + CLOCK_SLACK_MS) return null
        if (newest.mgdl >= LOW_MGDL) return null
        // The last thing done to the pump's basal. If it was to deliver - or to end a stop - that stands.
        val last = delivery.tbrs.lastOrNull() ?: return null
        if (last.percent > REDUCED_PERCENT) return null
        // Cut short means somebody replaced or cancelled it: a decision, not a lapse.
        if (last.endedEpochMs != null) return null
        val end = last.scheduledEndEpochMs
        if (end - nowEpochMs > ENDS_WITHIN_MS) return null          // still covers the time until the next reading
        if (nowEpochMs - end > LAPSED_MAX_MS) return null            // ran out long ago: no longer the same story
        val glucose = newest.mgdl.roundToInt()
        val clock = SimpleDateFormat("HH:mm", Locale.US).format(Date(end))
        val what = if (last.percent == 0) "остановка базала" else "базал ${last.percent} %"
        val lapse = if (end > nowEpochMs) "истекает в $clock" else "истекла в $clock"
        return Hold(
            glucose, last,
            "сахар $glucose, $what на помпе $lapse, а новой команды на её место нет — часы сами остановили базал ещё на $HOLD_MINUTES мин (предохранитель низкого сахара)"
        )
    }

    const val LOW_MGDL = 70.0

    /** A basal at or below this share of profile is a reduced one, set against a low. */
    const val REDUCED_PERCENT = 50
    const val HOLD_MINUTES = 30

    /** Readings come five minutes apart: a stop with less than this left would run out before the next one is acted on. */
    const val ENDS_WITHIN_MS = 7 * 60_000L

    /** After this the lapse is old: what the pump has done since is somebody's decision, or nobody's for too long to mend by a stop. */
    const val LAPSED_MAX_MS = 20 * 60_000L
    const val READING_FRESH_MS = 7 * 60_000L
    private const val CLOCK_SLACK_MS = 60_000L
}
