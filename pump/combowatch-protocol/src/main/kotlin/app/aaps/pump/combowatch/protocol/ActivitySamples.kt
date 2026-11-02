package app.aaps.pump.combowatch.protocol

/** One minute of the watch's senses: the mean heart rate of the minute and the steps taken in it; either may be unknown. */
data class ActivitySample(val minuteEpochMs: Long, val heartRateBpm: Int?, val steps: Int?)

/**
 * The text form of a run of [ActivitySample]s inside a [PumpEvent.Type.ACTIVITY_SAMPLES] event:
 * `v1;<first minute epoch ms>;<hr>/<steps>,<hr>/<steps>,...` with one entry per minute from the
 * first, `-` for an unknown figure and `-/-` for a minute with nothing. Compact enough for an
 * hour of samples to fit in a few hundred bytes, readable in a log.
 */
object ActivitySamples {

    const val VERSION = "v1"
    const val MINUTE_MS = 60_000L

    /** A gap longer than this starts a new event, so that an empty afternoon is not sent as a thousand `-/-`. */
    const val MAX_GAP_MINUTES = 30

    /** The most minutes one event carries. */
    const val MAX_MINUTES_PER_EVENT = 120

    fun encode(samples: List<ActivitySample>): String {
        val sorted = samples.filter { it.heartRateBpm != null || it.steps != null }.sortedBy { it.minuteEpochMs }
        if (sorted.isEmpty()) return "$VERSION;0;"
        val first = minuteOf(sorted.first().minuteEpochMs)
        val byMinute = sorted.associateBy { minuteOf(it.minuteEpochMs) }
        val last = minuteOf(sorted.last().minuteEpochMs)
        val entries = ArrayList<String>(((last - first) / MINUTE_MS).toInt() + 1)
        var minute = first
        while (minute <= last) {
            val sample = byMinute[minute]
            entries += "${sample?.heartRateBpm?.toString() ?: "-"}/${sample?.steps?.toString() ?: "-"}"
            minute += MINUTE_MS
        }
        return "$VERSION;$first;${entries.joinToString(",")}"
    }

    /** The samples of [text]; an unknown version or a malformed text yields nothing rather than an error. */
    fun decode(text: String?): List<ActivitySample> {
        val parts = text?.split(';', limit = 3) ?: return emptyList()
        if (parts.size < 3 || parts[0] != VERSION) return emptyList()
        val first = parts[1].toLongOrNull() ?: return emptyList()
        if (parts[2].isEmpty()) return emptyList()
        return parts[2].split(',').mapIndexedNotNull { index, entry ->
            val slash = entry.indexOf('/')
            if (slash < 0) return@mapIndexedNotNull null
            val hr = entry.substring(0, slash).toIntOrNull()
            val steps = entry.substring(slash + 1).toIntOrNull()
            if (hr == null && steps == null) null else ActivitySample(first + index * MINUTE_MS, hr, steps)
        }
    }

    /**
     * Cut a run of samples into the pieces that go into separate events: a new piece at every
     * gap longer than [MAX_GAP_MINUTES] and every [MAX_MINUTES_PER_EVENT] minutes of span.
     */
    fun pieces(samples: List<ActivitySample>): List<List<ActivitySample>> {
        val sorted = samples.sortedBy { it.minuteEpochMs }
        val out = mutableListOf<MutableList<ActivitySample>>()
        for (sample in sorted) {
            val current = out.lastOrNull()
            val minute = minuteOf(sample.minuteEpochMs)
            if (current == null ||
                minute - minuteOf(current.last().minuteEpochMs) > MAX_GAP_MINUTES * MINUTE_MS ||
                minute - minuteOf(current.first().minuteEpochMs) >= MAX_MINUTES_PER_EVENT * MINUTE_MS
            ) out += mutableListOf(sample) else current += sample
        }
        return out
    }

    fun minuteOf(epochMs: Long): Long = epochMs - Math.floorMod(epochMs, MINUTE_MS)
}
