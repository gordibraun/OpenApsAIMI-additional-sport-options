package app.aaps.combobench

import org.junit.Assert.*
import org.junit.Test

class ProbePreparationTest {
    @Test fun cancelsEvenWhenDiscoveryWasNotStartedByUs() {
        var calls = 0
        val result = ProbePreparation.stopDiscovery({ false }, { calls++; true }, { 0 }, { error("No wait needed") })
        assertEquals(1, calls)
        assertFalse(result.wasActive)
        assertTrue(result.cancellationAccepted)
    }

    @Test fun waitsForConfirmedEndOfDiscovery() {
        var elapsed = 0L
        val result = ProbePreparation.stopDiscovery({ elapsed < 100 }, { true }, { elapsed }, { elapsed += it })
        assertTrue(result.wasActive)
        assertEquals(100L, result.elapsedMs)
    }

    @Test fun rejectedCancellationIsFineOnlyIfAlreadyStopped() {
        val result = ProbePreparation.stopDiscovery({ false }, { false }, { 0 }, { error("No wait needed") })
        assertFalse(result.cancellationAccepted)
    }

    @Test fun persistentDiscoveryStopsPreparationWithinBound() {
        var elapsed = 0L
        assertThrows(IllegalStateException::class.java) {
            ProbePreparation.stopDiscovery({ true }, { false }, { elapsed }, { elapsed += it })
        }
        assertEquals(2_000L, elapsed)
    }

    @Test fun permissionFailureIsNotIgnored() {
        assertThrows(SecurityException::class.java) {
            ProbePreparation.stopDiscovery({ false }, { throw SecurityException() }, { 0 }, { error("No wait needed") })
        }
    }
}
