package app.aaps.combobench

import org.junit.Assert.*
import org.junit.Test

class ManualPairingRecoveryTest {
    private val waiting = listOf("STARTING", "WAITING_VISIBILITY", "BLUETOOTH_EVENT", "DISCOVERABLE")

    @Test fun interruptionBeforeDiscoveringAnyPumpCanBeRetried() {
        assertTrue(ManualPairingRecovery.canRetry("INTERRUPTED", false, false, waiting))
    }

    @Test fun anyPumpContactOrPersistedIdentityKeepsTheLock() {
        assertFalse(ManualPairingRecovery.canRetry("INTERRUPTED", true, false, waiting))
        assertFalse(ManualPairingRecovery.canRetry("INTERRUPTED", false, true, waiting))
        for (stage in listOf("TARGET_FOUND", "PAIRING_REQUEST", "BONDED", "TX_ATTEMPT", "PIN_REQUIRED", "UNKNOWN"))
            assertFalse(ManualPairingRecovery.canRetry("INTERRUPTED", false, false, waiting + stage))
    }

    @Test fun missingOrTruncatedHistoryDoesNotClearTheLock() {
        assertFalse(ManualPairingRecovery.canRetry("INTERRUPTED", false, false, emptyList()))
        assertFalse(ManualPairingRecovery.canRetry("INTERRUPTED", false, false, listOf("DISCOVERABLE")))
        assertFalse(ManualPairingRecovery.canRetry("PIN_REQUIRED", false, false, waiting))
    }
}
