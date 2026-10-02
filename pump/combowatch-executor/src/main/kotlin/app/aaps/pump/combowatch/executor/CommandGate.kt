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
class CommandGate(
    private val nowEpochMs: () -> Long,
    /**
     * The largest single bolus this watch will pass on, in tenths of a unit. The phone applies
     * its own constraints before it asks; this one is held on the watch so that no message,
     * however it came to be, can make the watch deliver more than its owner allowed it to.
     */
    private val maxBolusTenthsIU: () -> Int = { DEFAULT_MAX_BOLUS_TENTHS_IU },
    /**
     * The pump this watch is paired with, as the driver names it, or null when it holds none.
     * The controller always supplies this; left out, the rule below is not applied, which is
     * only meant for tests of the other rules.
     *
     * The phone decides for one particular pump - the one its glucose, its insulin on board and
     * its records belong to. A watch that has since been paired with a different pump must not
     * carry those decisions out on it, so every command that changes delivery has to come under
     * a lease naming the very pump the watch holds. Reading the pump needs no such match: that
     * is how the phone finds out which pump is there.
     */
    private val heldPump: (() -> String?)? = null
) {

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

        if ((heldPump != null) && (command.kind != CommandKind.STATUS)) {
            val held = heldPump.invoke() ?: return Admission.Refused("no pump is paired with this watch")
            if (lease.pumpSerial != held)
                return Admission.Refused("the phone asked for pump ${lease.pumpSerial}, this watch holds $held")
        }

        // Reading the pump is how an unresolved command gets resolved, so it stays allowed while
        // everything that changes delivery is held back.
        if (awaitingReconciliation && command.kind != CommandKind.STATUS)
            return Admission.Refused("awaiting reconciliation of an earlier command")

        if (busy)
            return Admission.Refused("executor busy")

        return when (command.kind) {
            CommandKind.STATUS        -> Admission.Run
            CommandKind.CANCEL_TBR    -> Admission.Run
            CommandKind.SET_TBR       -> admitTbr(command)
            CommandKind.DELIVER_BOLUS -> admitBolus(command)
        }
    }

    private fun admitBolus(command: ComboCommand): Admission {
        val amount = command.bolusTenthsIU
            ?: return Admission.Refused("DELIVER_BOLUS without an amount")
        if (command.bolusKind == null)
            return Admission.Refused("DELIVER_BOLUS without a kind")
        if (amount < 1)
            return Admission.Refused("bolus of $amount tenths is not a deliverable amount")
        val limit = maxBolusTenthsIU()
        if (amount > limit)
            return Admission.Refused("bolus of $amount tenths exceeds the watch's limit of $limit")
        return Admission.Run
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

        /** 3.0 U: room for the loop's microboluses, and deliberately not for a meal bolus. */
        const val DEFAULT_MAX_BOLUS_TENTHS_IU = 30
    }
}
