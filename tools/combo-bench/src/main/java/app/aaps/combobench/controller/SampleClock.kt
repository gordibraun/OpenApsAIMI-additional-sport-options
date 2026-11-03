package app.aaps.combobench.controller

/**
 * Health Services stamps a sample as a duration since boot; which clock that boot refers to is
 * not the same on every device. On this watch (02–03.11.2026) the pulse came out nine hours
 * behind the wall clock - the share of the uptime spent in deep sleep - so sessions the pulse
 * started were filed in the small hours. Both readings of the duration are tried, against the
 * realtime clock and against the awake-time clock, and the one that lands nearest to now wins;
 * a reading in the future or older than a plausible delivery delay is a wrong clock, not a time.
 */
object SampleClock {

    /** A passive sample reaches the app within minutes; anything older than this is not a sample time. */
    const val MAX_AGE_MS = 2 * 60 * 60_000L

    /** Clock drift tolerance for "not in the future". */
    const val FUTURE_SLACK_MS = 60_000L

    fun resolve(nowEpochMs: Long, elapsedRealtimeMs: Long, uptimeMs: Long, durationFromBootMs: Long): Long {
        val byElapsed = nowEpochMs - elapsedRealtimeMs + durationFromBootMs
        val byUptime = nowEpochMs - uptimeMs + durationFromBootMs
        return listOf(byElapsed, byUptime)
            .filter { it <= nowEpochMs + FUTURE_SLACK_MS && nowEpochMs - it <= MAX_AGE_MS }
            .maxOrNull()
            ?.coerceAtMost(nowEpochMs)
            ?: nowEpochMs
    }
}
