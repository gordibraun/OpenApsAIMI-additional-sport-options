package app.aaps.combobench

import org.junit.Assert.*
import org.junit.Test

class ManualPumpTargetTest {
    @Test fun onlyTheDeclaredSecondBenchIsAccepted() {
        assertEquals("PUMP_10392647", ManualPumpTarget(" 10392647 ").pump)
        for (value in listOf("", "41056642", "12345678", "PUMP_10392647", "103926470")) {
            assertThrows(IllegalArgumentException::class.java) { ManualPumpTarget(value) }
        }
    }

    @Test fun automaticDiscoveryRequiresRocheAddressAndPumpName() {
        val target = ManualPumpTarget("10392647")
        assertTrue(target.accepts("00:0E:2F:12:34:56", "SpiritCombo"))
        assertTrue(target.accepts("00:0e:2f:12:34:56", "PUMP_10392647"))
        for (name in listOf(null, "", "My phone", "PUMP_41056642", "SpiritCombo extra"))
            assertFalse(target.accepts("00:0E:2F:12:34:56", name))
        assertFalse(target.accepts("00:11:22:12:34:56", "SpiritCombo"))
        assertFalse(target.accepts("00:0E:2F:25:24:BC", "SpiritCombo"))
        assertFalse(target.accepts("00:0E:2F:12:34", "SpiritCombo"))
    }

    @Test fun manualAddressPinsOneDevice() {
        val target = ManualPumpTarget("10392647", " 00:0e:2f:12:34:56 ")
        assertEquals("00:0E:2F:12:34:56", target.address)
        assertTrue(target.accepts("00:0E:2F:12:34:56", null))
        assertFalse(target.accepts("00:0E:2F:12:34:57", "SpiritCombo"))
        for (address in listOf("00:0E:2F:25:24:BC", "00:11:22:12:34:56", "bad"))
            assertThrows(IllegalArgumentException::class.java) { ManualPumpTarget("10392647", address) }
    }
}
