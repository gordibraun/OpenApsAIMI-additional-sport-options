package app.aaps.combobench

import org.junit.Assert.*
import org.junit.Test

class ProbeDeadlineTest {
    @Test fun lateConnectionTimerCannotInterruptHold() {
        val deadline = ProbeDeadline()
        assertTrue(deadline.opened())
        assertFalse(deadline.expireConnection())
        assertTrue(deadline.finish())
        assertFalse(deadline.expireHold())
        assertFalse(deadline.expired)
    }

    @Test fun connectionTimeoutCannotBecomeSuccess() {
        val deadline = ProbeDeadline()
        assertTrue(deadline.expireConnection())
        assertFalse(deadline.opened())
        assertFalse(deadline.finish())
        assertTrue(deadline.expired)
        assertEquals(ProbeDeadline.Phase.CONNECT_TIMEOUT, deadline.phase)
    }

    @Test fun holdWatchdogCannotActBeforeOpening() {
        val deadline = ProbeDeadline()
        assertFalse(deadline.expireHold())
        assertTrue(deadline.opened())
        assertTrue(deadline.expireHold())
        assertFalse(deadline.finish())
        assertTrue(deadline.expired)
        assertEquals(ProbeDeadline.Phase.HOLD_TIMEOUT, deadline.phase)
    }

    @Test fun finishOnlyAllowedAfterOpeningAndOnlyOnce() {
        val deadline = ProbeDeadline()
        assertFalse(deadline.finish())
        assertTrue(deadline.opened())
        assertFalse(deadline.opened())
        assertTrue(deadline.finish())
        assertFalse(deadline.finish())
    }
}
