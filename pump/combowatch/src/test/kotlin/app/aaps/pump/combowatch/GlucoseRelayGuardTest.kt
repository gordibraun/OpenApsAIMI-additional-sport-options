package app.aaps.pump.combowatch

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The relay is kicked only when the watch is in touch and the readings have stopped, and not more than once per ten minutes. */
class GlucoseRelayGuardTest {

    private val now = 1_800_000_000_000L
    private fun minutes(m: Int) = m * 60_000L

    @Test fun `stale readings with a fresh watch contact ask for a kick`() {
        assertTrue(GlucoseRelayGuard.shouldKick(now, now - minutes(13), now - minutes(1), 0L))
        assertTrue(GlucoseRelayGuard.shouldKick(now, null, now - minutes(1), 0L))
    }

    @Test fun `fresh readings, a watch that is away, or a recent kick hold back`() {
        assertFalse(GlucoseRelayGuard.shouldKick(now, now - minutes(5), now - minutes(1), 0L))
        assertFalse(GlucoseRelayGuard.shouldKick(now, now - minutes(13), now - minutes(5), 0L))
        assertFalse(GlucoseRelayGuard.shouldKick(now, now - minutes(13), now - minutes(1), now - minutes(4)))
        assertTrue(GlucoseRelayGuard.shouldKick(now, now - minutes(13), now - minutes(1), now - minutes(11)))
    }
}
