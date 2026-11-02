package app.aaps.combobench.controller

import app.aaps.pump.combowatch.protocol.ActivitySample
import app.aaps.pump.combowatch.protocol.ActivitySamples.MINUTE_MS
import app.aaps.pump.combowatch.protocol.ActivitySamples.minuteOf
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Minute buckets of the heart rate and the steps Health Services hands the watch, kept until
 * they are complete and then given out once for the record (02.11.2026: the owner wants the
 * pulse and the steps collected to study how long exercise keeps working). Pure logic; the
 * host feeds it, persists it and ships what it gives out.
 *
 * A minute is given out once it is [GRACE_MINUTES] old, because the step counts of a minute may
 * arrive a few minutes after it. Steps that arrive for a minute already given out are put into
 * the oldest minute still pending, so that no step is lost, only moved by a few minutes.
 */
class ActivitySampleLog(private val maxPendingMinutes: Int = 24 * 60) {

    private class Minute(var hrSum: Long = 0L, var hrCount: Int = 0, var steps: Int? = null) {

        fun toSample(minute: Long) = ActivitySample(minute, if (hrCount > 0) (hrSum.toDouble() / hrCount).roundToInt() else null, steps)
    }

    private val pending = java.util.TreeMap<Long, Minute>()

    /** The newest minute given out; nothing at or before it is pending any more. */
    private var takenUpToMinute: Long = 0L

    /** The last daily step total Health Services reported, and when: deltas come from it. */
    private var dailyTotal: Long? = null
    private var dailyTotalAtEpochMs: Long = 0L

    var lastFlushEpochMs: Long = 0L

    val pendingMinutes: Int get() = pending.size

    fun heartRate(atEpochMs: Long, bpm: Int) {
        if (bpm !in 30..230) return
        bucket(minuteOf(atEpochMs)).apply { hrSum += bpm; hrCount++ }
        trim()
    }

    /** Steps counted between [startEpochMs] and [endEpochMs], spread evenly over the minutes in between. */
    fun steps(startEpochMs: Long, endEpochMs: Long, count: Long) {
        if (count <= 0L || endEpochMs < startEpochMs) return
        spread(minuteOf(startEpochMs), minuteOf(endEpochMs), count)
    }

    /** The steps of the day so far, as Health Services reports them; the difference from the last report is what was walked since. */
    fun stepsDaily(atEpochMs: Long, total: Long) {
        if (total < 0L) return
        val previous = dailyTotal
        val previousAt = dailyTotalAtEpochMs
        dailyTotal = total
        dailyTotalAtEpochMs = atEpochMs
        if (previous == null) return
        // A smaller total means the day rolled over: the new total is what was walked since midnight.
        val delta = if (total >= previous) total - previous else total
        if (delta <= 0L) return
        val from = if (previousAt in 1 until atEpochMs && atEpochMs - previousAt <= 60 * MINUTE_MS) minuteOf(previousAt) + MINUTE_MS else minuteOf(atEpochMs)
        spread(minOf(from, minuteOf(atEpochMs)), minuteOf(atEpochMs), delta)
    }

    /** Minutes at least [GRACE_MINUTES] old, oldest first; they are no longer pending afterwards. */
    fun takeCompleted(nowEpochMs: Long): List<ActivitySample> {
        val limit = minuteOf(nowEpochMs) - GRACE_MINUTES * MINUTE_MS
        val out = mutableListOf<ActivitySample>()
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val (minute, data) = it.next()
            if (minute > limit) break
            data.toSample(minute).takeIf { s -> s.heartRateBpm != null || s.steps != null }?.let { s -> out += s }
            takenUpToMinute = maxOf(takenUpToMinute, minute)
            it.remove()
        }
        return out
    }

    private fun spread(fromMinute: Long, toMinute: Long, count: Long) {
        val first = maxOf(fromMinute, takenUpToMinute + MINUTE_MS).coerceAtMost(toMinute.coerceAtLeast(fromMinute))
        val minutes = ((toMinute - first) / MINUTE_MS).toInt().coerceAtLeast(0) + 1
        val each = count / minutes
        var remainder = count - each * minutes
        var minute = first
        repeat(minutes) {
            var share = each
            if (remainder > 0) { share++; remainder-- }
            if (share > 0 || minute == toMinute) {
                val b = bucket(minute)
                b.steps = (b.steps ?: 0) + share.toInt()
            }
            minute += MINUTE_MS
        }
        trim()
    }

    private fun bucket(minute: Long): Minute {
        // A minute already given out cannot be reopened: its figures go to the oldest minute still open.
        val key = if (minute <= takenUpToMinute) takenUpToMinute + MINUTE_MS else minute
        return pending.getOrPut(key) { Minute() }
    }

    private fun trim() {
        while (pending.size > maxPendingMinutes) pending.pollFirstEntry()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("takenUpTo", takenUpToMinute)
        .put("dailyTotal", dailyTotal ?: JSONObject.NULL)
        .put("dailyTotalAt", dailyTotalAtEpochMs)
        .put("lastFlush", lastFlushEpochMs)
        .put("pending", JSONArray().apply {
            pending.forEach { (minute, m) -> put(JSONArray().put(minute).put(m.hrSum).put(m.hrCount).put(m.steps ?: JSONObject.NULL)) }
        })

    companion object {

        const val GRACE_MINUTES = 5

        fun fromJson(json: JSONObject, maxPendingMinutes: Int = 24 * 60): ActivitySampleLog {
            val log = ActivitySampleLog(maxPendingMinutes)
            log.takenUpToMinute = json.optLong("takenUpTo")
            log.dailyTotal = if (json.isNull("dailyTotal")) null else json.optLong("dailyTotal")
            log.dailyTotalAtEpochMs = json.optLong("dailyTotalAt")
            log.lastFlushEpochMs = json.optLong("lastFlush")
            json.optJSONArray("pending")?.let { array ->
                for (i in 0 until array.length()) {
                    val row = array.getJSONArray(i)
                    log.pending[row.getLong(0)] = Minute(row.getLong(1), row.getInt(2), if (row.isNull(3)) null else row.getInt(3))
                }
            }
            return log
        }
    }
}
