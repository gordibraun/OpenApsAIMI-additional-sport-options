package app.aaps.pump.combowatch.executor

import app.aaps.pump.combowatch.protocol.BolusKind
import app.aaps.pump.combowatch.protocol.ComboCommand
import app.aaps.pump.combowatch.protocol.CommandKind
import app.aaps.pump.combowatch.protocol.ControlLease
import app.aaps.pump.combowatch.protocol.Outcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The gate is the only thing standing between a message arriving and insulin delivery changing,
 * so each rule is pinned down here, including the ones that refuse.
 */
class CommandGateTest {

    private var now = 1_000_000L
    private val gate = CommandGate({ now })

    private fun lease(
        generation: Long = 7L,
        expiresAt: Long = now + 60_000,
        controllerIsWatch: Boolean = true
    ) = ControlLease(generation, now - 1_000, expiresAt, "10392647", controllerIsWatch)

    private fun command(
        id: String = "c1",
        kind: CommandKind = CommandKind.SET_TBR,
        generation: Long = 7L,
        expiresAt: Long = now + 30_000,
        percentage: Int? = 0,
        duration: Int? = 30
    ) = ComboCommand(id, generation, kind, now, expiresAt, percentage, duration)

    private fun admit(
        command: ComboCommand = command(),
        lease: ControlLease? = lease(),
        journal: CommandJournal = SimpleCommandJournal(),
        busy: Boolean = false,
        awaiting: Boolean = false
    ) = gate.admit(command, lease, journal, busy, awaiting)

    @Test fun `a well formed stop under a live lease runs`() {
        assertInstanceOf(CommandGate.Admission.Run::class.java, admit())
    }

    @Test fun `without a lease nothing reaches the pump`() {
        val refused = assertInstanceOf(CommandGate.Admission.Refused::class.java, admit(lease = null))
        assertTrue(refused.reason.contains("lease"))
    }

    @Test fun `an expired lease refuses, so silence stands the watch down`() {
        assertInstanceOf(
            CommandGate.Admission.Refused::class.java,
            admit(lease = lease(expiresAt = now - 1))
        )
    }

    @Test fun `a revoked lease refuses even before it would expire`() {
        assertInstanceOf(
            CommandGate.Admission.Refused::class.java,
            admit(lease = lease(controllerIsWatch = false))
        )
    }

    @Test fun `a command from an older lease generation is not honoured`() {
        assertInstanceOf(
            CommandGate.Admission.Refused::class.java,
            admit(command = command(generation = 6L))
        )
    }

    @Test fun `a late command is dropped rather than applied`() {
        val refused = assertInstanceOf(
            CommandGate.Admission.Refused::class.java,
            admit(command = command(expiresAt = now - 1))
        )
        assertTrue(refused.reason.contains("expired"))
    }

    @Test fun `an id that already ran is answered from the journal, not run again`() {
        val journal = SimpleCommandJournal()
        journal.markStarted("c1", now)
        journal.markFinished("c1", Outcome.DONE, null)
        val answered = assertInstanceOf(CommandGate.Admission.AlreadyAnswered::class.java, admit(journal = journal))
        assertEquals(Outcome.DONE, answered.outcome)
    }

    @Test fun `a resend while the first attempt is still unresolved does not start a second one`() {
        val journal = SimpleCommandJournal()
        journal.markStarted("c1", now)
        assertInstanceOf(
            CommandGate.Admission.Refused::class.java,
            admit(journal = journal, awaiting = true)
        )
    }

    @Test fun `an unresolved earlier command blocks therapy but not reading the pump`() {
        assertInstanceOf(CommandGate.Admission.Refused::class.java, admit(awaiting = true))
        assertInstanceOf(
            CommandGate.Admission.Run::class.java,
            admit(command = command(kind = CommandKind.STATUS), awaiting = true)
        )
    }

    @Test fun `a busy executor refuses instead of queueing`() {
        assertInstanceOf(CommandGate.Admission.Refused::class.java, admit(busy = true))
    }

    @Test fun `quantities the pump cannot take are refused without a bluetooth session`() {
        assertInstanceOf(CommandGate.Admission.Refused::class.java, admit(command = command(percentage = 5)))
        assertInstanceOf(CommandGate.Admission.Refused::class.java, admit(command = command(percentage = 510)))
        assertInstanceOf(CommandGate.Admission.Refused::class.java, admit(command = command(duration = 20)))
        assertInstanceOf(CommandGate.Admission.Refused::class.java, admit(command = command(duration = 0)))
        assertInstanceOf(CommandGate.Admission.Refused::class.java, admit(command = command(percentage = null)))
        assertInstanceOf(CommandGate.Admission.Refused::class.java, admit(command = command(duration = null)))
    }

    // ---- how large a bolus may be ----------------------------------------------------------------

    private fun bolus(tenths: Int) =
        ComboCommand("b1", 7L, CommandKind.DELIVER_BOLUS, now, now + 30_000, bolusTenthsIU = tenths, bolusKind = BolusKind.NORMAL)

    private fun leaseAllowing(maxBolusTenthsIU: Int?) = ControlLease(7L, now - 1_000, now + 60_000, "10392647", true, maxBolusTenthsIU)

    @Test fun `with no limit from the phone the watch's own small one holds`() {
        assertInstanceOf(CommandGate.Admission.Run::class.java, admit(command = bolus(CommandGate.DEFAULT_MAX_BOLUS_TENTHS_IU)))
        val refused = assertInstanceOf(
            CommandGate.Admission.Refused::class.java, admit(command = bolus(CommandGate.DEFAULT_MAX_BOLUS_TENTHS_IU + 1))
        )
        assertTrue(refused.reason.contains("watch's limit"))
    }

    @Test fun `the owner's max bolus from the phone travels in the lease and is what counts`() {
        assertInstanceOf(CommandGate.Admission.Run::class.java, admit(command = bolus(70), lease = leaseAllowing(70)))
        val refused = assertInstanceOf(CommandGate.Admission.Refused::class.java, admit(command = bolus(71), lease = leaseAllowing(70)))
        assertTrue(refused.reason.contains("phone's limit of 70"))
        // Downwards as well: an owner who allows less on the phone than the watch's own default is obeyed.
        assertInstanceOf(CommandGate.Admission.Refused::class.java, admit(command = bolus(20), lease = leaseAllowing(10)))
    }

    @Test fun `no lease raises the limit past the pump's own largest bolus`() {
        assertInstanceOf(CommandGate.Admission.Run::class.java, admit(command = bolus(250), lease = leaseAllowing(10_000)))
        assertInstanceOf(CommandGate.Admission.Refused::class.java, admit(command = bolus(251), lease = leaseAllowing(10_000)))
    }

    // ---- the lease has to name the pump this watch holds ---------------------------------------

    private fun holding(pump: String?) = CommandGate({ now }, heldPump = { pump })

    private fun admitHolding(pump: String?, command: ComboCommand = command(), leasedPump: String = "PUMP_10392647") =
        holding(pump).admit(
            command, ControlLease(7L, now - 1_000, now + 60_000, leasedPump, true), SimpleCommandJournal(), false, false
        )

    @Test fun `a command for the pump this watch holds runs`() {
        assertInstanceOf(CommandGate.Admission.Run::class.java, admitHolding("PUMP_10392647"))
    }

    @Test fun `a command decided for another pump never reaches the one this watch holds`() {
        val refused = assertInstanceOf(CommandGate.Admission.Refused::class.java, admitHolding("PUMP_41056642"))
        assertTrue(refused.reason.contains("PUMP_10392647") && refused.reason.contains("PUMP_41056642"))
        // The same for every kind that changes delivery.
        assertInstanceOf(
            CommandGate.Admission.Refused::class.java,
            admitHolding("PUMP_41056642", command(kind = CommandKind.CANCEL_TBR, percentage = null, duration = null))
        )
        assertInstanceOf(
            CommandGate.Admission.Refused::class.java,
            admitHolding(
                "PUMP_41056642",
                ComboCommand("b1", 7L, CommandKind.DELIVER_BOLUS, now, now + 30_000, bolusTenthsIU = 1, bolusKind = BolusKind.SMB)
            )
        )
    }

    @Test fun `with no pump paired nothing that changes delivery is attempted`() {
        val refused = assertInstanceOf(CommandGate.Admission.Refused::class.java, admitHolding(null))
        assertTrue(refused.reason.contains("no pump"))
    }

    @Test fun `reading the pump needs no match, that is how the phone learns which pump is held`() {
        assertInstanceOf(
            CommandGate.Admission.Run::class.java,
            admitHolding("PUMP_41056642", command(kind = CommandKind.STATUS))
        )
    }

    @Test fun `cancelling a temporary basal needs no quantities`() {
        assertInstanceOf(
            CommandGate.Admission.Run::class.java,
            admit(command = command(kind = CommandKind.CANCEL_TBR, percentage = null, duration = null))
        )
    }
}
