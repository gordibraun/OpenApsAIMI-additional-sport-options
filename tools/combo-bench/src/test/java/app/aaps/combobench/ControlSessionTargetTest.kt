package app.aaps.combobench

import org.junit.Assert.assertThrows
import org.junit.Test

class ControlSessionTargetTest {
    @Test fun eachSandboxHasExactlyOneTestPump() {
        ControlSessionTarget.validate("watch", "PUMP_41056642", "00:0E:2F:25:24:BC")
        ControlSessionTarget.validate("manual", "PUMP_10392647", "00:0E:2F:E7:D1:95")
    }

    @Test fun wrongSandboxOrAddressCannotUsePairingState() {
        for (flavor in listOf("watch", "manual", "phone")) {
            for ((pump, address) in listOf("PUMP_41056642" to "00:0E:2F:25:24:BC",
                "PUMP_10392647" to "00:0E:2F:E7:D1:95")) {
                if ((flavor == "watch" && pump == "PUMP_41056642") ||
                    (flavor == "manual" && pump == "PUMP_10392647")) continue
                assertThrows(IllegalStateException::class.java) { ControlSessionTarget.validate(flavor, pump, address) }
            }
        }
        assertThrows(IllegalStateException::class.java) {
            ControlSessionTarget.validate("manual", "PUMP_10392647", "00:0E:2F:25:24:BC")
        }
    }
}
