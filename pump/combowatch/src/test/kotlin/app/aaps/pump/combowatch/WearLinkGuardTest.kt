package app.aaps.pump.combowatch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Bluetooth is restarted only for a watch that is alive and near but has stopped getting our leases, and not twice in twenty minutes. */
class WearLinkGuardTest {

    private val now = 1_800_000_000_000L
    private fun minutes(m: Int) = m * 60_000L

    @Test fun `three unanswered leases with a watch heard just now ask for a restart`() {
        val verdict = WearLinkGuard.check(now, 3, now - minutes(1), now - minutes(2), true, 0L)
        assertTrue(verdict is WearLinkGuard.Verdict.Restart)
        assertTrue((verdict as WearLinkGuard.Verdict.Restart).why.contains("3 аренд"))
    }

    @Test fun `glucose through the relay counts as the watch being alive`() {
        assertTrue(WearLinkGuard.check(now, 4, now - minutes(40), now - minutes(3), true, 0L) is WearLinkGuard.Verdict.Restart)
    }

    @Test fun `fewer unanswered leases, a watch away, no node, or a recent restart hold back`() {
        assertEquals(WearLinkGuard.Verdict.Fine, WearLinkGuard.check(now, 2, now - minutes(1), now - minutes(1), true, 0L))
        assertEquals(WearLinkGuard.Verdict.Fine, WearLinkGuard.check(now, 5, now - minutes(25), null, true, 0L))
        assertEquals(WearLinkGuard.Verdict.Fine, WearLinkGuard.check(now, 5, now - minutes(1), now - minutes(1), false, 0L))
        assertEquals(WearLinkGuard.Verdict.Fine, WearLinkGuard.check(now, 5, now - minutes(1), now - minutes(1), true, now - minutes(10)))
        assertTrue(WearLinkGuard.check(now, 5, now - minutes(1), now - minutes(1), true, now - minutes(21)) is WearLinkGuard.Verdict.Restart)
    }
}
