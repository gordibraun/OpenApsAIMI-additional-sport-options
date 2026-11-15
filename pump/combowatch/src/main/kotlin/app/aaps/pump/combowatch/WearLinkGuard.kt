package app.aaps.pump.combowatch

/**
 * The Wear link to the watch can go half-open: the watch's messages still arrive (through
 * Google's cloud, over Wi-Fi), while everything the phone sends goes into a Bluetooth socket the
 * watch has long given up on - no error, no «no watch connected», just no answers (03.11.2026,
 * four times in one day, up to an hour each until somebody restarted Bluetooth). The phone cannot
 * restart Bluetooth itself; the relay app (WearTester's phone side) can, on request.
 *
 * Pure decision: the link is judged half-open when several lease renewals in a row got no sign of
 * arrival while the watch is plainly alive and near - something from it, or its glucose, came
 * recently and a watch node is listed. Not more than one restart per [RETRY_MS].
 */
object WearLinkGuard {

    const val RELAY_PACKAGE = "com.example.weartester"
    const val RESET_ACTION = "com.example.weartester.RESET_BLUETOOTH"
    const val EXTRA_REASON = "reason"

    /** Renewals go out every two minutes; three without a sign of arrival is six minutes of a dead direction. */
    const val MIN_UNANSWERED_LEASES = 3

    /** The watch, or its glucose through the relay, must have been heard this recently: a watch that is away explains the silence itself. */
    const val WATCH_ALIVE_MS = 20 * 60_000L

    /** One restart, then time for the link to come back before the next. */
    const val RETRY_MS = 20 * 60_000L

    /** A request the relay turned away as too soon after its own last restart is repeated after this long. */
    const val RETRY_AFTER_REFUSAL_MS = 3 * 60_000L

    /**
     * What to keep as "the last restart" when the relay refused one as too soon: a moment that makes
     * [check] ask again in [RETRY_AFTER_REFUSAL_MS] instead of a whole [RETRY_MS]. A refusal is not a
     * restart: 15.11.2026 12:45 it was counted as one, and the phone would have gone unheard by the
     * watch for twenty more minutes while the relay was ready to restart six minutes later.
     */
    fun lastRestartAfterRefusal(nowEpochMs: Long): Long = nowEpochMs - RETRY_MS + RETRY_AFTER_REFUSAL_MS

    sealed class Verdict {
        object Fine : Verdict()
        data class Restart(val why: String) : Verdict()
    }

    fun check(
        nowEpochMs: Long,
        leasesUnanswered: Int,
        lastWatchContactEpochMs: Long,
        lastGlucoseEpochMs: Long?,
        watchNodeSeen: Boolean,
        lastRestartEpochMs: Long
    ): Verdict {
        if (leasesUnanswered < MIN_UNANSWERED_LEASES) return Verdict.Fine
        if (!watchNodeSeen) return Verdict.Fine
        val lastAlive = maxOf(lastWatchContactEpochMs, lastGlucoseEpochMs ?: 0L)
        if (nowEpochMs - lastAlive > WATCH_ALIVE_MS) return Verdict.Fine
        if (lastRestartEpochMs > 0L && nowEpochMs - lastRestartEpochMs < RETRY_MS) return Verdict.Fine
        val aliveMin = (nowEpochMs - lastAlive) / 60_000L
        return Verdict.Restart("$leasesUnanswered аренд без ответа, часы слышны $aliveMin мин назад")
    }
}
