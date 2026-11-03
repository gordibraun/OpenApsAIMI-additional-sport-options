package app.aaps.combobench.controller

/**
 * On 03.11.2026 the controller stopped hearing the phone at 01:50 - no lease, no command, no
 * acknowledgement reached it for ten hours - while its own events kept going out and the phone
 * sat connected next to it; only a restart of the process cured it, and nobody noticed before
 * the owner's boluses failed. This notices that state from the outside signs alone: the phone is
 * nearby, we keep sending, nothing comes back. Pure logic; the host feeds it and restarts.
 */
class DeafnessWatchdog(
    private val quietMs: Long = QUIET_MS,
    private val minSendsSinceAck: Int = MIN_SENDS_SINCE_ACK,
    private val restartGapMs: Long = RESTART_GAP_MS
) {

    data class State(
        /** When the phone was last heard (lease, command or acknowledgement); 0 for never. */
        val lastHeardEpochMs: Long,
        /** When this process started: the reference while the phone has not been heard yet. */
        val startedEpochMs: Long,
        /** Sends of pending events since the last acknowledgement, and when the last went out. */
        val sendsSinceAck: Int,
        val lastSendEpochMs: Long,
        /** When the watchdog last restarted the process; 0 for never. */
        val lastRestartEpochMs: Long
    )

    sealed class Verdict {
        object Fine : Verdict()
        /** Deaf, but a restart is not allowed yet (too soon after the last one, or the pump is busy). */
        data class DeafButWait(val why: String) : Verdict()
        data class Restart(val why: String) : Verdict()
    }

    fun check(nowEpochMs: Long, state: State, phoneNearby: Boolean, pumpBusy: Boolean): Verdict {
        if (!phoneNearby) return Verdict.Fine
        val reference = maxOf(state.lastHeardEpochMs, state.startedEpochMs)
        val quietFor = nowEpochMs - reference
        if (quietFor < quietMs) return Verdict.Fine
        if (state.sendsSinceAck < minSendsSinceAck) return Verdict.Fine
        if (nowEpochMs - state.lastSendEpochMs > quietMs) return Verdict.Fine
        val why = "телефон рядом, но не слышен ${quietFor / 60_000} мин; отправок без подтверждения ${state.sendsSinceAck}"
        if (pumpBusy) return Verdict.DeafButWait("$why; помпа занята")
        if (state.lastRestartEpochMs > 0L && nowEpochMs - state.lastRestartEpochMs < restartGapMs) return Verdict.DeafButWait("$why; перезапуск был ${(nowEpochMs - state.lastRestartEpochMs) / 60_000} мин назад")
        return Verdict.Restart(why)
    }

    companion object {

        const val QUIET_MS = 20 * 60_000L
        const val MIN_SENDS_SINCE_ACK = 3
        const val RESTART_GAP_MS = 60 * 60_000L
    }
}
