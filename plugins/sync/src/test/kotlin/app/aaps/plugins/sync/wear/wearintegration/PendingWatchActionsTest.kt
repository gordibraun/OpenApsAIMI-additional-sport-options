package app.aaps.plugins.sync.wear.wearintegration

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PendingWatchActionsTest {
    @Test fun confirmationIsUsedOnce() {
        val gate = PendingWatchActions<String>()
        val token = gate.issue("watch", "request", 1000, "carbs")!!
        assertEquals("carbs", gate.consume("watch", token, 2000))
        assertNull(gate.consume("watch", token, 2001))
        assertNull(gate.issue("watch", "request", 2002, "carbs"))
    }

    @Test fun otherWatchCannotConfirm() {
        val gate = PendingWatchActions<String>()
        val token = gate.issue("watch", "request", 1000, "insulin")!!
        assertNull(gate.consume("other", token, 2000))
        assertEquals("insulin", gate.consume("watch", token, 2000))
    }

    @Test fun expiredAndClockRollbackConfirmationsAreRejected() {
        val gate = PendingWatchActions<String>()
        val expired = gate.issue("watch", "a", 1000, "insulin")!!
        assertNull(gate.consume("watch", expired, 61_001))
        val rollback = gate.issue("watch", "b", 1000, "insulin")!!
        assertNull(gate.consume("watch", rollback, 999))
    }

    @Test fun newPreviewInvalidatesOldPreview() {
        val gate = PendingWatchActions<String>()
        val old = gate.issue("watch", "a", 1000, "first")!!
        val current = gate.issue("watch", "b", 2000, "second")!!
        assertNull(gate.consume("watch", old, 3000))
        assertEquals("second", gate.consume("watch", current, 3000))
    }

    @Test fun processRestartCannotReplayPendingAction() {
        val token = PendingWatchActions<String>().issue("watch", "a", 1000, "insulin")!!
        assertNull(PendingWatchActions<String>().consume("watch", token, 2000))
    }

    @Test fun concurrentConfirmationHasOneWinner() {
        val gate = PendingWatchActions<String>()
        val token = gate.issue("watch", "a", 1000, "insulin")!!
        val winners = java.util.concurrent.atomic.AtomicInteger()
        val threads = List(8) { Thread { if (gate.consume("watch", token, 2000) != null) winners.incrementAndGet() } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(1, winners.get())
    }
}
