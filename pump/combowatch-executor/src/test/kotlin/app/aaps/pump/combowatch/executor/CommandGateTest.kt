package app.aaps.pump.combowatch.executor

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

    @Test fun `cancelling a temporary basal needs no quantities`() {
        assertInstanceOf(
            CommandGate.Admission.Run::class.java,
            admit(command = command(kind = CommandKind.CANCEL_TBR, percentage = null, duration = null))
        )
    }
}
