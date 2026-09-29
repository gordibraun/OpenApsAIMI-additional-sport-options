package app.aaps.combobench

import org.junit.Assert.assertThrows
import org.junit.Test

class ManualReconnectPolicyTest {
    private fun check(config: String? = ManualReconnectPolicy.PUMP, pump: String = ManualReconnectPolicy.PUMP,
                      address: String = ManualReconnectPolicy.ADDRESS, stage: String = "PAIRED", active: Boolean = false,
                      completed: Boolean = true, cleanup: Boolean = true, stored: Boolean = true) =
        ManualReconnectPolicy.validate(config, pump, address, stage, active, completed, cleanup, stored)

    @Test fun exactOwnCompletedPairingIsRequired() { check() }

    @Test fun oldPumpAndUnverifiedPairingAreRejected() {
        assertThrows(IllegalStateException::class.java) { check(config = null) }
        assertThrows(IllegalStateException::class.java) { check(pump = "PUMP_41056642") }
        assertThrows(IllegalStateException::class.java) { check(address = "00:0E:2F:25:24:BC") }
        assertThrows(IllegalStateException::class.java) { check(stage = "BONDED") }
        assertThrows(IllegalStateException::class.java) { check(active = true) }
        assertThrows(IllegalStateException::class.java) { check(completed = false) }
        assertThrows(IllegalStateException::class.java) { check(cleanup = false) }
        assertThrows(IllegalStateException::class.java) { check(stored = false) }
    }
}
