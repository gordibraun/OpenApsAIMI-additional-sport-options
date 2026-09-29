package app.aaps.combobench

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlSessionCleanupTest {
    @Test fun completeExchangeAndLinkClosureCanSettle() {
        assertTrue(ControlSessionCleanup.settled(true, true, true, 4, true, false))
    }

    @Test fun failureBeforeAnyProtocolWriteCanSettleOnlyWithClosedLink() {
        assertTrue(ControlSessionCleanup.settled(true, true, false, 0, true, false))
        assertFalse(ControlSessionCleanup.settled(true, true, false, 0, false, false))
    }

    @Test fun uncertainWritesCannotBeRetried() {
        assertFalse(ControlSessionCleanup.settled(true, true, false, 1, true, false))
    }

    @Test fun successfulProtocolDoesNotHideCleanupOrBondFailure() {
        assertFalse(ControlSessionCleanup.settled(false, true, true, 4, true, false))
        assertFalse(ControlSessionCleanup.settled(true, false, true, 4, true, false))
        assertFalse(ControlSessionCleanup.settled(true, true, true, 4, false, false))
        assertFalse(ControlSessionCleanup.settled(true, true, true, 4, true, true))
    }
}
