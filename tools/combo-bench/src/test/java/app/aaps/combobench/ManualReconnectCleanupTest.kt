package app.aaps.combobench

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualReconnectCleanupTest {
    private fun check(stage: String = "SOCKET_OPENED_ZERO_BYTES", active: Boolean = false,
                      closed: Boolean = true, unregistered: Boolean = true,
                      bonded: Boolean = true, changed: Boolean = false) =
        ManualReconnectCleanup.canRecheck(stage, active, closed, unregistered, bonded, changed)

    @Test fun completedSocketCanBeCheckedWithoutAnotherConnection() {
        assertTrue(check())
        assertTrue(check(stage = "TIMEOUT"))
    }

    @Test fun uncertainOrChangedSessionCannotBeUnlocked() {
        for (stage in listOf("INTERRUPTED", "SAVE_FAILED", "START_FAILED", "CONNECTING", "FAILED"))
            assertFalse(check(stage = stage))
        assertFalse(check(active = true))
        assertFalse(check(closed = false))
        assertFalse(check(unregistered = false))
        assertFalse(check(bonded = false))
        assertFalse(check(changed = true))
    }

    @Test fun unknownOrConnectedStateNeverMeansDisconnected() {
        assertTrue(ManualReconnectCleanup.disconnected(listOf(false, false, false)))
        for (samples in listOf(emptyList(), listOf(false), listOf(false, false),
            listOf(false, null, false), listOf(false, true, false), listOf(true, true, true)))
            assertFalse(ManualReconnectCleanup.disconnected(samples))
    }
}
