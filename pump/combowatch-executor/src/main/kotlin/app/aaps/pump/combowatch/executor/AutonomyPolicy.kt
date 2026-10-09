package app.aaps.pump.combowatch.executor

import app.aaps.pump.combowatch.protocol.BolusKind
import app.aaps.pump.combowatch.protocol.ComboCommand
import app.aaps.pump.combowatch.protocol.CommandKind
import app.aaps.pump.combowatch.protocol.ControlLease
import app.aaps.pump.combowatch.protocol.RegulationSnapshot
import app.aaps.pump.combowatch.protocol.TbrKind

/**
 * When the watch may act on the pump by itself, and what it may then ask of it.
 *
 * The order of authority does not change: the phone decides, the watch executes. The watch steps
 * in only when the phone has gone silent while in charge - its lease ran out, it was not taken
 * back - and only on the strength of what the phone left behind for that case: a snapshot of its
 * last loop run, naming the pump the watch holds and still within its time. As soon as a lease
 * arrives again, the watch is an executor again.
 */
class AutonomyPolicy(private val nowEpochMs: () -> Long) {

    /** Chosen by the owner on the watch. */
    enum class Mode {

        /** Never act alone and never compute. */
        OFF,

        /** When alone, work out what would be done and write it down, but leave the pump untouched. */
        OBSERVE,

        /** When alone, do it. */
        ACTIVE
    }

    sealed interface Standing {

        /** The phone is away and left what the watch needs: it may regulate basal by itself. */
        data object Alone : Standing

        /** The watch is not on its own, or lacks what it would need to be. [reason] is for the journal. */
        data class NotAlone(val reason: String) : Standing
    }

    /**
     * @param phoneLastHeardEpochMs when anything last arrived from the phone, by the watch's own
     *   clock; zero if nothing ever did.
     */
    fun standing(
        lease: ControlLease?,
        snapshot: RegulationSnapshot?,
        heldPump: String?,
        busy: Boolean,
        awaitingReconciliation: Boolean,
        phoneLastHeardEpochMs: Long
    ): Standing {
        val now = nowEpochMs()
        if (heldPump == null) return Standing.NotAlone("no pump is paired with this watch")
        if (lease == null) return Standing.NotAlone("the phone has never been in charge of this watch")
        // The lease's own expiry is stamped by the phone's clock. Should the two clocks disagree,
        // a phone that is plainly still talking must not be taken for one that has gone: the
        // watch counts the silence on its own clock as well, and needs both.
        if (now - phoneLastHeardEpochMs < MIN_PHONE_SILENCE_MS) return Standing.NotAlone("the phone is in charge")
        // Taken back, not run out: the owner switched the phone to driving the pump itself, or to
        // another driver. That is the opposite of the phone being away.
        if (!lease.controllerIsWatch) return Standing.NotAlone("the phone took control back")
        if (lease.pumpSerial != heldPump) return Standing.NotAlone("the phone's lease was for another pump")
        if (lease.liveAt(now)) return Standing.NotAlone("the phone is in charge")
        if (snapshot == null) return Standing.NotAlone("the phone left no snapshot")
        if (snapshot.pumpSerial != heldPump) return Standing.NotAlone("the phone's snapshot is of another pump")
        if (now >= snapshot.validUntilEpochMs) return Standing.NotAlone("the phone's permission has run out")
        // Nothing is decided on top of a command whose outcome is not known, or in the middle of one.
        if (awaitingReconciliation) return Standing.NotAlone("an earlier command is not settled")
        if (busy) return Standing.NotAlone("the pump is in use")
        return Standing.Alone
    }

    /** A command the watch issues to itself, with the lease that lets it through the gate. */
    class OwnCommand(val command: ComboCommand, val lease: ControlLease)

    /**
     * A bolus the owner asked for on the watch with the phone away. Not the watch's own decision -
     * the regulator never gives insulin - but the owner's, carried out under the watch's own lease,
     * with the owner's bolus limit from the phone's last lease on it so that the gate holds the
     * watch to the same limit the phone would.
     */
    fun ownerBolus(id: String, tenthsIU: Int, heldPump: String, maxBolusTenthsIU: Int?): OwnCommand {
        require(tenthsIU >= 1) { "a bolus of $tenthsIU tenths is not a deliverable amount" }
        val now = nowEpochMs()
        val lease = ControlLease(
            generation = OWN_GENERATION, issuedAtEpochMs = now, expiresAtEpochMs = now + OWNER_BOLUS_VALID_MS,
            pumpSerial = heldPump, controllerIsWatch = true, maxBolusTenthsIU = maxBolusTenthsIU
        )
        val command = ComboCommand(
            id = id, leaseGeneration = OWN_GENERATION, kind = CommandKind.DELIVER_BOLUS,
            issuedAtEpochMs = now, expiresAtEpochMs = now + OWNER_BOLUS_VALID_MS,
            bolusTenthsIU = tenthsIU, bolusKind = BolusKind.NORMAL
        )
        return OwnCommand(command, lease)
    }

    /** A reading of the pump the watch gives itself: to learn of boluses given on the pump while the phone is away. */
    fun ownRead(id: String, heldPump: String): OwnCommand {
        val now = nowEpochMs()
        val lease = ControlLease(OWN_GENERATION, now, now + OWN_COMMAND_VALID_MS, heldPump, true)
        val command = ComboCommand(id, OWN_GENERATION, CommandKind.STATUS, now, now + OWN_COMMAND_VALID_MS)
        return OwnCommand(command, lease)
    }

    /**
     * The only command the watch ever gives itself: a temporary basal at or under profile.
     *
     * Not a bolus, not a rate above profile, not a cancellation that the pump would turn into
     * 110 %. The limits are checked here as well as where the decision is made, so that no fault
     * upstream can come out of this function as more insulin.
     */
    fun ownTemporaryBasal(id: String, percent: Int, durationMinutes: Int, heldPump: String): OwnCommand {
        require(percent in 0..MAX_OWN_PERCENT && percent % 10 == 0) { "the watch only sets 0..$MAX_OWN_PERCENT % in tens, not $percent" }
        require(durationMinutes in ALLOWED_OWN_MINUTES) { "the watch only sets temporary basals of $ALLOWED_OWN_MINUTES minutes, not $durationMinutes" }
        val now = nowEpochMs()
        val lease = ControlLease(
            generation = OWN_GENERATION, issuedAtEpochMs = now, expiresAtEpochMs = now + OWN_COMMAND_VALID_MS,
            pumpSerial = heldPump, controllerIsWatch = true
        )
        val command = ComboCommand(
            id = id, leaseGeneration = OWN_GENERATION, kind = CommandKind.SET_TBR,
            issuedAtEpochMs = now, expiresAtEpochMs = now + OWN_COMMAND_VALID_MS,
            percentage = percent, durationMinutes = durationMinutes,
            // An ordinary temporary basal, also at 0 %: that is what the loop's own zero is. The
            // other kind means the owner suspended the pump, which nobody did.
            tbrKind = TbrKind.NORMAL
        )
        return OwnCommand(command, lease)
    }

    companion object {

        /**
         * How long the phone has to have been silent before the watch counts itself alone. The
         * phone's lease lasts as long, so normally the two run out together. Five minutes: one
         * sensor reading without the phone, and the second is the watch's to act on.
         */
        const val MIN_PHONE_SILENCE_MS = 5 * 60_000L

        /** Just under profile; the pump has no "100 %" temporary basal. */
        const val MAX_OWN_PERCENT = 90
        val ALLOWED_OWN_MINUTES = listOf(15, 30)

        /** A generation no phone ever uses: theirs are wall-clock timestamps. */
        const val OWN_GENERATION = -1L

        /** Decided and done within the minute; if the pump session cannot start by then, the reading is old. */
        const val OWN_COMMAND_VALID_MS = 2 * 60_000L

        /** The owner is waiting at the screen; the sensor's window may make the pump wait two minutes first. */
        const val OWNER_BOLUS_VALID_MS = 4 * 60_000L
    }
}
