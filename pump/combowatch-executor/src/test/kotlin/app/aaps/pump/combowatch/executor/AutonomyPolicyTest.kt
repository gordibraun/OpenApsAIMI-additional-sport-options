package app.aaps.pump.combowatch.executor

import app.aaps.pump.combowatch.executor.AutonomyPolicy.Standing
import app.aaps.pump.combowatch.protocol.CommandKind
import app.aaps.pump.combowatch.protocol.ControlLease
import app.aaps.pump.combowatch.protocol.RegulationSnapshot
import app.aaps.pump.combowatch.protocol.TbrKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The watch acts alone only when the phone, while in charge, went silent - and never otherwise. */
class AutonomyPolicyTest {

    private var now = 1_000_000_000L
    private val policy = AutonomyPolicy { now }
    private val pump = "PUMP_41056642"

    private fun lease(expiredAgoMs: Long = 60_000, pumpSerial: String = pump, controllerIsWatch: Boolean = true) =
        ControlLease(7L, now - 900_000, now - expiredAgoMs, pumpSerial, controllerIsWatch)

    private fun snapshot(pumpSerial: String = pump, validForMs: Long = 3_600_000) = RegulationSnapshot(
        madeAtEpochMs = now - 600_000, pumpSerial = pumpSerial, validUntilEpochMs = now + validForMs,
        targetMgdl = 117.0, hypoThresholdMgdl = 70.0, sensitivityMgdlPerU = 50.0, carbRatioGPerU = 10.0,
        cobG = 0.0, iobU = 0.0, insulinActivity = listOf(0.0), insulinRemaining = listOf(1.0, 0.0)
    )

    private fun standing(
        lease: ControlLease? = lease(), snapshot: RegulationSnapshot? = snapshot(), heldPump: String? = pump,
        busy: Boolean = false, awaiting: Boolean = false, heardAgoMs: Long = 11 * 60_000L
    ) = policy.standing(lease, snapshot, heldPump, busy, awaiting, now - heardAgoMs)

    @Test fun `the owner's bolus runs under the watch's own lease with the phone's limit on it`() {
        val own = policy.ownerBolus("b1", 32, pump, maxBolusTenthsIU = 70)
        assertEquals(CommandKind.DELIVER_BOLUS, own.command.kind)
        assertEquals(32, own.command.bolusTenthsIU)
        assertEquals(70, own.lease.maxBolusTenthsIU)
        val gate = CommandGate({ now }, heldPump = { pump })
        assertInstanceOf(CommandGate.Admission.Run::class.java, gate.admit(own.command, own.lease, SimpleCommandJournal(), false, false))
        val tooMuch = policy.ownerBolus("b2", 71, pump, maxBolusTenthsIU = 70)
        assertInstanceOf(CommandGate.Admission.Refused::class.java, gate.admit(tooMuch.command, tooMuch.lease, SimpleCommandJournal(), false, false))
        assertThrows(IllegalArgumentException::class.java) { policy.ownerBolus("b3", 0, pump, 70) }
    }

    @Test fun `the phone's lease ran out and it left a snapshot, so the watch is on its own`() {
        assertEquals(Standing.Alone, standing())
    }

    @Test fun `while the phone's lease is live the watch is an executor and nothing more`() {
        assertInstanceOf(Standing.NotAlone::class.java, standing(lease = lease(expiredAgoMs = -60_000)))
    }

    @Test fun `a phone heard from in the last five minutes is in charge, whatever its lease's clock says`() {
        // The lease reads as expired - say the phone's clock is behind - but the phone spoke three minutes ago.
        assertInstanceOf(Standing.NotAlone::class.java, standing(heardAgoMs = 3 * 60_000L))
        assertInstanceOf(Standing.NotAlone::class.java, standing(heardAgoMs = AutonomyPolicy.MIN_PHONE_SILENCE_MS - 1))
        assertEquals(Standing.Alone, standing(heardAgoMs = AutonomyPolicy.MIN_PHONE_SILENCE_MS))
    }

    @Test fun `a lease the phone took back does not turn into independence when it would have expired`() {
        val reason = assertInstanceOf(Standing.NotAlone::class.java, standing(lease = lease(controllerIsWatch = false)))
        assertTrue(reason.reason.contains("took control back"))
    }

    @Test fun `a watch the phone was never in charge of does nothing by itself`() {
        assertInstanceOf(Standing.NotAlone::class.java, standing(lease = null))
    }

    @Test fun `without a snapshot there is nothing to act on`() {
        assertInstanceOf(Standing.NotAlone::class.java, standing(snapshot = null))
    }

    @Test fun `a lease or a snapshot of another pump gives no authority over this one`() {
        assertInstanceOf(Standing.NotAlone::class.java, standing(lease = lease(pumpSerial = "PUMP_10392647")))
        assertInstanceOf(Standing.NotAlone::class.java, standing(snapshot = snapshot(pumpSerial = "PUMP_10392647")))
        assertInstanceOf(Standing.NotAlone::class.java, standing(heldPump = null))
    }

    @Test fun `the phone's permission ends when it said it would`() {
        assertInstanceOf(Standing.NotAlone::class.java, standing(snapshot = snapshot(validForMs = 0)))
    }

    @Test fun `nothing is decided over an unsettled command or a running one`() {
        assertInstanceOf(Standing.NotAlone::class.java, standing(awaiting = true))
        assertInstanceOf(Standing.NotAlone::class.java, standing(busy = true))
    }

    @Test fun `the watch's own command is a temporary basal for the pump it holds, and passes the gate`() {
        val own = policy.ownTemporaryBasal("auto-1", 0, 30, pump)
        assertEquals(CommandKind.SET_TBR, own.command.kind)
        assertEquals(0, own.command.percentage)
        assertEquals(30, own.command.durationMinutes)
        assertEquals(TbrKind.NORMAL, own.command.tbrKind)
        assertEquals(null, own.command.bolusTenthsIU)
        assertEquals(pump, own.lease.pumpSerial)

        val gate = CommandGate({ now }, heldPump = { pump })
        assertEquals(CommandGate.Admission.Run, gate.admit(own.command, own.lease, SimpleCommandJournal(), false, false))
        // The same command is not accepted for a pump the watch does not hold.
        val other = CommandGate({ now }, heldPump = { "PUMP_10392647" })
        assertInstanceOf(CommandGate.Admission.Refused::class.java, other.admit(own.command, own.lease, SimpleCommandJournal(), false, false))
    }

    @Test fun `the watch cannot give itself a rate above profile, an odd one, or an odd duration`() {
        assertThrows(IllegalArgumentException::class.java) { policy.ownTemporaryBasal("a", 100, 30, pump) }
        assertThrows(IllegalArgumentException::class.java) { policy.ownTemporaryBasal("a", 110, 30, pump) }
        assertThrows(IllegalArgumentException::class.java) { policy.ownTemporaryBasal("a", 500, 30, pump) }
        assertThrows(IllegalArgumentException::class.java) { policy.ownTemporaryBasal("a", -10, 30, pump) }
        assertThrows(IllegalArgumentException::class.java) { policy.ownTemporaryBasal("a", 45, 30, pump) }
        assertThrows(IllegalArgumentException::class.java) { policy.ownTemporaryBasal("a", 50, 60, pump) }
        assertThrows(IllegalArgumentException::class.java) { policy.ownTemporaryBasal("a", 50, 0, pump) }
    }

    @Test fun `the watch's own command goes stale within minutes`() {
        val own = policy.ownTemporaryBasal("auto-3", 0, 30, pump)
        now += AutonomyPolicy.OWN_COMMAND_VALID_MS
        val gate = CommandGate({ now }, heldPump = { pump })
        assertInstanceOf(CommandGate.Admission.Refused::class.java, gate.admit(own.command, own.lease, SimpleCommandJournal(), false, false))
    }
}
