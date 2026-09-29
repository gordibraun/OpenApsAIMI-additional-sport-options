package app.aaps.combobench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProbeBondGuardTest {
    @Test fun authenticatedProbeRejectsMissingBond() {
        assertThrows(IllegalStateException::class.java) { ProbeBondGuard(false).requireExistingBond() }
    }

    @Test fun existingBondCanBeCheckedWithoutChangingIt() {
        val guard = ProbeBondGuard(true)
        guard.requireExistingBond()
        guard.observe(true)
        assertNull(guard.stopReason)
    }

    @Test fun requestStopsEvenWhenSystemStillReportsBonded() {
        val guard = ProbeBondGuard(true)
        guard.observe(true, pairingRequested = true)
        assertEquals("PAIRING_REQUESTED", guard.stopReason)
    }

    @Test fun bondLossCannotBeClearedByLaterBondedEvent() {
        val guard = ProbeBondGuard(true)
        guard.observe(false)
        guard.observe(true)
        assertEquals("BOND_CHANGED", guard.stopReason)
    }

    @Test fun observedTransitionStopsEvenWhenFinalStateIsBonded() {
        val guard = ProbeBondGuard(true)
        guard.observe(true, bondChanged = true)
        assertEquals("BOND_CHANGED", guard.stopReason)
    }

    @Test fun firstStopReasonIsRetained() {
        val guard = ProbeBondGuard(true)
        guard.observe(true, pairingRequested = true)
        guard.observe(false)
        assertEquals("PAIRING_REQUESTED", guard.stopReason)
    }
}
