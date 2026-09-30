package app.aaps.pump.combowatch.executor

import app.aaps.pump.combowatch.protocol.ComboCommand
import app.aaps.pump.combowatch.protocol.CommandKind
import app.aaps.pump.combowatch.protocol.ControlLease
import app.aaps.pump.combowatch.protocol.Outcome

/**
 * Decides whether a command from the phone may reach the pump at all.
 *
 * Every rule here is written so that missing information refuses rather than allows: no lease,
 * an unreadable clock, an unresolved earlier command and a busy executor all end in
 * [Admission.Refused]. A watch that has lost track of its situation must do nothing, because the
 * pump it holds may be the only one the user has.
 */
class CommandGate(private val nowEpochMs: () -> Long) {

    sealed interface Admission {

        /** Run it, then record the outcome under [ComboCommand.id]. */
        data object Run : Admission

        /** Do not touch the pump. [reason] goes back to the phone unchanged. */
        data class Refused(val reason: String) : Admission

        /** This id already ran. Send the recorded answer again instead of running anything. */
        data class AlreadyAnswered(val outcome: Outcome, val reason: String?) : Admission
    }

    fun admit(
        command: ComboCommand,
        lease: ControlLease?,
        journal: CommandJournal,
        busy: Boolean,
        awaitingReconciliation: Boolean
    ): Admission {
        // Asked before: the phone resent, or the answer was lost on the way back. Either way the
        // pump was already touched once, and touching it again is the one thing that must not happen.
        journal.recordedOutcome(command.id)?.let { recorded ->
            recorded.outcome?.let { return Admission.AlreadyAnswered(it, recorded.reason) }
        }

        val now = nowEpochMs()

        if (lease == null || !lease.liveAt(now))
            return Admission.Refused("no live control lease")

        // A lease from an older generation belongs to a phone session that has since been
        // replaced; honouring it would let a stale decision through after the user switched modes.
        if (command.leaseGeneration != lease.generation)
            return Admission.Refused("lease generation mismatch")

        if (now >= command.expiresAtEpochMs)
            return Admission.Refused("command expired ${now - command.expiresAtEpochMs} ms ago")

        // Reading the pump is how an unresolved command gets resolved, so it stays allowed while
        // everything that changes delivery is held back.
        if (awaitingReconciliation && command.kind != CommandKind.STATUS)
            return Admission.Refused("awaiting reconciliation of an earlier command")

        if (busy)
            return Admission.Refused("executor busy")

        return when (command.kind) {
            CommandKind.STATUS     -> Admission.Run
            CommandKind.CANCEL_TBR -> Admission.Run
            CommandKind.SET_TBR    -> admitTbr(command)
        }
    }

    private fun admitTbr(command: ComboCommand): Admission {
        val percentage = command.percentage
            ?: return Admission.Refused("SET_TBR without a percentage")
        val duration = command.durationMinutes
            ?: return Admission.Refused("SET_TBR without a duration")
        if (percentage !in ALLOWED_PERCENTAGE_RANGE || percentage % PERCENTAGE_STEP != 0)
            return Admission.Refused("percentage $percentage outside what the pump accepts")
        if (duration !in ALLOWED_DURATION_RANGE || duration % DURATION_STEP_MINUTES != 0)
            return Admission.Refused("duration $duration outside what the pump accepts")
        return Admission.Run
    }

    companion object {

        // The Combo's own limits for a temporary basal. Checking them here means an impossible
        // request is answered without a Bluetooth session, and never becomes a half-finished
        // navigation the pump has to be rescued from.
        private val ALLOWED_PERCENTAGE_RANGE = 0..500
        private const val PERCENTAGE_STEP = 10
        private val ALLOWED_DURATION_RANGE = 15..(24 * 60)
        private const val DURATION_STEP_MINUTES = 15
    }
}
