package app.aaps.combobench

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundProbePolicyTest {
    private fun allowed(stage: String = "WAITING", id: String = "one", received: String = "one",
                        due: Long = 1_000, now: Long = 1_000, service: Boolean = true, pairing: Boolean = true) =
        BackgroundProbePolicy.mayRun(stage, id, received, due, now, service, pairing)

    @Test fun onlyCurrentWaitingRequestMayRun() { assertTrue(allowed()) }
    @Test fun duplicateAndFinishedDeliveryCannotRepeatHandshake() {
        for (stage in listOf("RUNNING", "COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED"))
            assertFalse(allowed(stage = stage))
    }
    @Test fun staleAndEmptyTokensAreRejected() {
        assertFalse(allowed(received = "old"))
        assertFalse(allowed(id = "", received = ""))
    }
    @Test fun earlyAndExpiredAlarmsDoNotTouchThePump() {
        assertFalse(allowed(now = 999))
        assertFalse(allowed(due = 0))
        assertFalse(allowed(now = 1_001 + BackgroundProbePolicy.LATENESS_MS))
        assertTrue(allowed(now = 1_000 + BackgroundProbePolicy.LATENESS_MS))
    }
    @Test fun processOrPairingChangeFailsClosed() {
        assertFalse(allowed(service = false))
        assertFalse(allowed(pairing = false))
    }
}
