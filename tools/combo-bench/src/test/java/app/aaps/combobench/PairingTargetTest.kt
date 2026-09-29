package app.aaps.combobench

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTargetTest {
    @Test fun onlyTheExactTargetMayPair() {
        assertTrue(PairingTarget.matches("00:0E:2F:25:24:BC", "00:0e:2f:25:24:bc"))
        assertFalse(PairingTarget.matches("00:0E:2F:25:24:BC", "00:0E:2F:25:24:BD"))
        assertFalse(PairingTarget.matches("", ""))
        assertFalse(PairingTarget.matches("pump", "pump"))
    }
}
