package app.aaps.pump.combowatch.executor

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

        data class Done(val snapshot: PumpSnapshot?) : SessionResult

        /** Reached the pump, the command did not take effect, delivery is unchanged. */
        data class Failed(val reason: String) : SessionResult

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
 * from that — why a refusal is remembered, why the journal is written before the session starts,
 * and why an unclear ending blocks the next command instead of being retried.
 */
class ComboExecutor(
    private val gate: CommandGate,
    private val journal: CommandJournal,
    private val session: PumpSession,
    private val nowEpochMs: () -> Long
) {

    @Volatile
    private var busy = false

    val awaitingReconciliation: Boolean get() = journal.hasUnresolved()

    fun execute(command: ComboCommand, lease: ControlLease?): ComboResult {
        when (val admission = gate.admit(command, lease, journal, busy, awaitingReconciliation)) {
            is CommandGate.Admission.AlreadyAnswered ->
                return ComboResult(command.id, admission.outcome, nowEpochMs(), reason = admission.reason)

            is CommandGate.Admission.Refused         -> {
                // Remembered, not merely returned. Once the phone has been told no, this id must
                // stay refused: a copy of it arriving late must not run after the phone has moved
                // on and issued a different command in its place.
                journal.markStarted(command.id, nowEpochMs())
                journal.markFinished(command.id, Outcome.REFUSED, admission.reason)
                return ComboResult(command.id, Outcome.REFUSED, nowEpochMs(), reason = admission.reason)
            }

            CommandGate.Admission.Run                -> Unit
        }

        // Written before the pump is touched, so a crash between these two lines is remembered as
        // "started, outcome unknown" rather than forgotten.
        journal.markStarted(command.id, nowEpochMs())
        busy = true
        val result = try {
            when (val sessionResult = session.run(command)) {
                is PumpSession.SessionResult.Done    -> {
                    journal.markFinished(command.id, Outcome.DONE, null)
                    ComboResult(command.id, Outcome.DONE, nowEpochMs(), sessionResult.snapshot)
                }

                is PumpSession.SessionResult.Failed  -> {
                    journal.markFinished(command.id, Outcome.FAILED, sessionResult.reason)
                    ComboResult(command.id, Outcome.FAILED, nowEpochMs(), reason = sessionResult.reason)
                }

                is PumpSession.SessionResult.Unknown -> {
                    journal.markFinished(command.id, Outcome.UNKNOWN, sessionResult.reason)
                    ComboResult(command.id, Outcome.UNKNOWN, nowEpochMs(), reason = sessionResult.reason)
                }
            }
        } catch (throwable: Throwable) {
            // An exception from the session says nothing about what the pump did with the keys it
            // had already been sent, so the unclear ending is the only honest record.
            val reason = throwable.message ?: throwable::class.simpleName ?: "session threw"
            journal.markFinished(command.id, Outcome.UNKNOWN, reason)
            ComboResult(command.id, Outcome.UNKNOWN, nowEpochMs(), reason = reason)
        } finally {
            busy = false
        }
        return result
    }

    /**
     * Close an unresolved command using what the pump was just read to be saying.
     *
     * This is the only way out of [Outcome.UNKNOWN], and it is deliberately a *read*: the executor
     * never decides what happened by reasoning about it, only by looking at the pump.
     */
    fun reconcile(snapshot: PumpSnapshot) {
        val unresolved = journal.unresolved() ?: return
        journal.markFinished(
            unresolved.id,
            Outcome.FAILED,
            "reconciled from pump: tbr=${snapshot.tbrPercentage ?: "none"} running=${snapshot.tbrRunning}"
        )
    }

    /** True when the next thing this executor should do is read the pump back. */
    fun needsReconciliationRead(): Boolean = awaitingReconciliation

    /** The kind of command a reconciliation needs. Reading is always allowed. */
    fun reconciliationCommandKind(): CommandKind = CommandKind.STATUS
}
