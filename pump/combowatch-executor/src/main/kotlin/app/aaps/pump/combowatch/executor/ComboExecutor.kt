package app.aaps.pump.combowatch.executor

import app.aaps.pump.combowatch.protocol.BolusReceipt
import app.aaps.pump.combowatch.protocol.ComboCommand
import app.aaps.pump.combowatch.protocol.ComboResult
import app.aaps.pump.combowatch.protocol.CommandKind
import app.aaps.pump.combowatch.protocol.ControlLease
import app.aaps.pump.combowatch.protocol.Outcome
import app.aaps.pump.combowatch.protocol.PumpSnapshot

/**
 * One Bluetooth session with the pump, as the executor needs to see it. The real implementation
 * drives the AAPS Combo driver; tests supply their own.
 */
interface PumpSession {

    sealed interface SessionResult {

        /** The command took effect, and what is reported here was read back from the pump. */
        data class Done(
            val snapshot: PumpSnapshot?,
            val tbrOutcome: String? = null,
            val tbrPercentage: Int? = null,
            val tbrDurationMinutes: Int? = null,
            val bolus: BolusReceipt? = null
        ) : SessionResult

        /**
         * The outcome is known and it is not what was asked for. For a temporary basal, delivery
         * is unchanged. For a bolus, [bolus] says what the pump's history shows was delivered,
         * which is nothing when it is null and less than requested when delivery was cut short.
         */
        data class Failed(
            val reason: String,
            val snapshot: PumpSnapshot? = null,
            val bolus: BolusReceipt? = null
        ) : SessionResult

        /** The session ended without establishing what the pump did. */
        data class Unknown(val reason: String) : SessionResult
    }

    fun run(command: ComboCommand): SessionResult
}

/**
 * Runs one command at a time, with the journal written around every pump session.
 *
 * The invariant this class exists to hold: **for a given command id, the pump is touched at most
 * once, whatever happens to the link, the process or the battery.** Everything else here follows
 * from that - why a refusal is remembered, why the journal is written before the session starts,
 * and why an unclear ending blocks the next command instead of being retried. For a bolus that
 * invariant is the whole point: insulin that was delivered cannot be taken back, so "probably
 * not delivered" is never allowed to become "deliver it again".
 */
class ComboExecutor(
    private val gate: CommandGate,
    private val journal: CommandJournal,
    private val session: PumpSession,
    private val nowEpochMs: () -> Long
) {

    @Volatile
    private var busy = false

    /** The command whose session is running now; see [CommandJournal.unresolved]. */
    @Volatile
    private var inFlightId: String? = null

    val isBusy: Boolean get() = busy

    val awaitingReconciliation: Boolean get() = journal.unresolved(excludingId = inFlightId) != null

    fun execute(command: ComboCommand, lease: ControlLease?): ComboResult {
        when (val admission = gate.admit(command, lease, journal, busy, awaitingReconciliation)) {
            is CommandGate.Admission.AlreadyAnswered ->
                return ComboResult(command.id, admission.outcome, nowEpochMs(), reason = admission.reason)

            is CommandGate.Admission.Refused         -> {
                // Remembered, not merely returned. Once the phone has been told no, this id must
                // stay refused: a copy of it arriving late must not run after the phone has moved
                // on and issued a different command in its place.
                journal.markStarted(command.id, nowEpochMs(), command.kind)
                journal.markFinished(command.id, Outcome.REFUSED, admission.reason)
                return ComboResult(command.id, Outcome.REFUSED, nowEpochMs(), reason = admission.reason)
            }

            CommandGate.Admission.Run                -> Unit
        }

        // Written before the pump is touched, so a crash between these two lines is remembered as
        // "started, outcome unknown" rather than forgotten.
        journal.markStarted(
            command.id, nowEpochMs(), command.kind,
            bolusTenthsIU = command.bolusTenthsIU,
            tbrPercentage = when (command.kind) {
                CommandKind.SET_TBR    -> command.percentage
                CommandKind.CANCEL_TBR -> 100
                else                   -> null
            }
        )
        busy = true
        inFlightId = command.id
        val result = try {
            when (val sessionResult = session.run(command)) {
                is PumpSession.SessionResult.Done    -> {
                    journal.markFinished(command.id, Outcome.DONE, null)
                    ComboResult(
                        command.id, Outcome.DONE, nowEpochMs(), sessionResult.snapshot,
                        tbrOutcome = sessionResult.tbrOutcome,
                        tbrPercentage = sessionResult.tbrPercentage,
                        tbrDurationMinutes = sessionResult.tbrDurationMinutes,
                        bolus = sessionResult.bolus
                    )
                }

                is PumpSession.SessionResult.Failed  -> {
                    journal.markFinished(command.id, Outcome.FAILED, sessionResult.reason)
                    ComboResult(
                        command.id, Outcome.FAILED, nowEpochMs(), sessionResult.snapshot,
                        reason = sessionResult.reason, bolus = sessionResult.bolus
                    )
                }

                is PumpSession.SessionResult.Unknown -> {
                    journal.markFinished(command.id, Outcome.UNKNOWN, sessionResult.reason)
                    ComboResult(command.id, Outcome.UNKNOWN, nowEpochMs(), reason = sessionResult.reason)
                }
            }
        } catch (throwable: Throwable) {
            // An exception from the session says nothing about what the pump did with what it
            // had already been sent, so the unclear ending is the only honest record.
            val reason = throwable.message ?: throwable::class.simpleName ?: "session threw"
            journal.markFinished(command.id, Outcome.UNKNOWN, reason)
            ComboResult(command.id, Outcome.UNKNOWN, nowEpochMs(), reason = reason)
        } finally {
            inFlightId = null
            busy = false
        }
        return result
    }

    /**
     * Read the pump back on the executor's own initiative to settle an unresolved command.
     *
     * This is what makes an unclear ending heal by itself instead of waiting for somebody to ask:
     * the session reads the pump (and its bolus history) as it connects, and [reconcile] is fed
     * from that read. Nothing is changed on the pump. Returns true when nothing is left unresolved.
     */
    fun reconcileNow(): Boolean {
        if (busy) return false
        if (!awaitingReconciliation) return true
        busy = true
        try {
            val now = nowEpochMs()
            session.run(ComboCommand("reconcile-$now", 0L, CommandKind.STATUS, now, now + RECONCILE_VALID_MS))
        } catch (_: Throwable) {
            // Still unresolved; the caller tries again later.
        } finally {
            busy = false
        }
        return !awaitingReconciliation
    }

    /**
     * Close an unresolved command using what the pump itself says.
     *
     * This is the only way out of [Outcome.UNKNOWN], and it is deliberately a *read*: the executor
     * never decides what happened by reasoning about it. A temporary basal is judged against the
     * pump's screen, a bolus against the pump's history - [bolusesInHistory] are the boluses the
     * driver found there since it last looked, which is where a bolus that was delivered just as
     * the link died shows up.
     */
    fun reconcile(snapshot: PumpSnapshot, bolusesInHistory: List<BolusReceipt> = emptyList()) {
        val unresolved = journal.unresolved(excludingId = inFlightId) ?: return
        when (unresolved.kind) {
            CommandKind.DELIVER_BOLUS -> {
                val delivered = bolusesInHistory.firstOrNull {
                    it.timestampEpochMs >= unresolved.startedAtEpochMs - PUMP_CLOCK_TOLERANCE_MS
                }
                if (delivered != null)
                    journal.markFinished(
                        unresolved.id, Outcome.DONE,
                        "reconciled from pump history: ${delivered.tenthsIU} tenths delivered (bolus ${delivered.bolusId})"
                    )
                else
                    journal.markFinished(unresolved.id, Outcome.FAILED, "reconciled from pump history: no bolus recorded")
            }

            CommandKind.SET_TBR,
            CommandKind.CANCEL_TBR    -> {
                val wanted = unresolved.tbrPercentage
                val shown = if (snapshot.tbrRunning) snapshot.tbrPercentage else 100
                // Ending a TBR the AAPS way leaves a 90 % or 110 % one behind, so that counts too.
                val tookEffect = (shown == wanted) ||
                    ((unresolved.kind == CommandKind.CANCEL_TBR) && (shown != null) && (shown in 90..110))
                journal.markFinished(
                    unresolved.id,
                    if (tookEffect) Outcome.DONE else Outcome.FAILED,
                    "reconciled from pump: shows ${shown ?: "nothing"} %, wanted ${wanted ?: "?"} %"
                )
            }

            else                      ->
                journal.markFinished(unresolved.id, Outcome.FAILED, "reconciled from pump: tbr=${snapshot.tbrPercentage ?: "none"}")
        }
    }

    companion object {

        /**
         * How far the pump's clock may trail the watch's when matching a bolus in the pump's
         * history to the command that asked for it. The driver keeps the two within a couple of
         * minutes of each other and corrects the pump when they drift further.
         */
        const val PUMP_CLOCK_TOLERANCE_MS = 3 * 60_000L

        private const val RECONCILE_VALID_MS = 10 * 60_000L
    }
}
