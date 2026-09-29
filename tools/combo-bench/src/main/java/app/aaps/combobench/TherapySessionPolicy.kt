package app.aaps.combobench

/**
 * The only pump commands this bench may issue, and only on the off-body test Combo 10392647:
 * read the status, stop delivery (0 % TBR, the same emulated stop AAPS uses) or return to 100 %.
 * Boluses, basal profile writes and real pump STOP are not offered anywhere.
 */
internal object TherapySessionPolicy {
    const val PUMP = ManualReconnectPolicy.PUMP
    const val ADDRESS = ManualReconnectPolicy.ADDRESS
    val STOP_DURATIONS = listOf(15, 30, 45, 60)
    /** Ordinary packets advance the nonce by one; PumpIO's connection recovery jumps by this amount. */
    const val NONCE_RECOVERY_STEP = 500
    /** Connect + status + basal profile read + one TBR stay far below this many packets. */
    const val NONCE_BUDGET = 4_000
    const val CONNECT_TIMEOUT_MS = 4 * 60_000L
    const val COMMAND_TIMEOUT_MS = 3 * 60_000L
    const val STATUS_TIMEOUT_MS = 90_000L
    const val TOTAL_TIMEOUT_MS = 7 * 60_000L
    const val LINK_CLOSE_WAIT_MS = 15_000L
    const val BLUETOOTH_WATCHDOG_MS = 30_000L

    enum class Kind(val percentage: Int?) { STATUS(null), STOP(0), RESUME(100) }

    data class Request(val kind: Kind, val durationMinutes: Int) {
        val percentage get() = kind.percentage
    }

    fun request(kind: Kind, durationMinutes: Int = 0): Request {
        when (kind) {
            Kind.STOP -> require(durationMinutes in STOP_DURATIONS) { "Остановка разрешена на 15, 30, 45 или 60 минут" }
            else -> require(durationMinutes == 0) { "Длительность задаётся только для остановки" }
        }
        return Request(kind, durationMinutes)
    }

    /** A locked session (unclear pump state) may only be followed by a status read, which reconciles it. */
    fun mayStart(kind: Kind, active: Boolean, locked: Boolean, otherWorkActive: Boolean): Boolean =
        !active && !otherWorkActive && (!locked || kind == Kind.STATUS)

    fun nonceStepAllowed(step: Long): Boolean = step == 1L || step == NONCE_RECOVERY_STEP.toLong()

    /**
     * The session may be repeated only when the driver closed the link itself, the bond is unchanged,
     * the radio link is known to be down and the outcome of every therapy command is known.
     */
    fun settled(disconnectCompleted: Boolean, bondIntact: Boolean, linkCleanupKnown: Boolean,
                cleanupFailed: Boolean, commandOutcomeKnown: Boolean): Boolean =
        disconnectCompleted && bondIntact && linkCleanupKnown && !cleanupFailed && commandOutcomeKnown
}
