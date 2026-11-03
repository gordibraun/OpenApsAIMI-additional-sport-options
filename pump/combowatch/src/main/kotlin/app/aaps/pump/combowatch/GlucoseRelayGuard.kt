package app.aaps.pump.combowatch

/**
 * The phone gets its glucose from the watch through a small relay app (WearTester's phone side).
 * On the night of 03.11.2026 the system force-stopped that relay; a stopped package cannot be
 * bound by Play Services, so for eight hours the watch sat connected with fresh readings while
 * AAPS had none and alarmed to nobody. When the last reading is old but the watch is in touch,
 * the relay's exported listener service is started explicitly: that clears the stopped state
 * and delivery resumes. Pure decision here; the plugin acts on it.
 */
object GlucoseRelayGuard {

    const val RELAY_PACKAGE = "com.example.weartester"
    const val RELAY_SERVICE = "com.example.weartester.WatchGlucoseReceiverService"

    /** No reading for this long counts as the relay being dead (readings come every five minutes). */
    const val STALE_MS = 12 * 60_000L

    /** The watch must have been heard this recently: a watch that is away explains the silence by itself. */
    const val WATCH_FRESH_MS = 3 * 60_000L

    /** One kick, then time for the readings to come back before the next. */
    const val RETRY_MS = 10 * 60_000L

    fun shouldKick(nowEpochMs: Long, lastGlucoseEpochMs: Long?, lastWatchContactEpochMs: Long, lastKickEpochMs: Long): Boolean {
        if (nowEpochMs - lastWatchContactEpochMs > WATCH_FRESH_MS) return false
        if (lastGlucoseEpochMs != null && nowEpochMs - lastGlucoseEpochMs < STALE_MS) return false
        if (lastKickEpochMs > 0L && nowEpochMs - lastKickEpochMs < RETRY_MS) return false
        return true
    }
}
