package app.aaps.combobench

import org.junit.Assert.*
import org.junit.Test

class PeerControlPolicyTest {
    @Test fun knownDevicesCanTestOnlyEachOther() {
        for (flavor in listOf("phone", "watch")) for (mode in listOf("server", "client"))
            PeerControlPolicy.validate(flavor, mode, PeerControlPolicy.expectedPeer(flavor), "s", "s", 1000, true)
    }

    @Test fun pumpAndArbitraryDevicesAreAlwaysRejected() {
        for (flavor in listOf("phone", "watch")) for (address in listOf("00:0E:2F:25:24:BC", "00:11:22:33:44:55"))
            assertThrows(IllegalArgumentException::class.java) {
                PeerControlPolicy.validate(flavor, "client", address, "s", "s", 1000, true)
            }
    }

    @Test fun permitsCannotCrossSessionsOrOutliveDeadline() {
        val address = PeerControlPolicy.expectedPeer("watch")
        for (remaining in listOf(-1L, 0L, 120001L)) assertThrows(IllegalArgumentException::class.java) {
            PeerControlPolicy.validate("watch", "client", address, "s", "s", remaining, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeerControlPolicy.validate("watch", "client", address, "old", "new", 1000, true)
        }
    }

    @Test fun unknownModeOrMissingNoPumpConfirmationFailsClosed() {
        val address = PeerControlPolicy.expectedPeer("phone")
        assertThrows(IllegalArgumentException::class.java) {
            PeerControlPolicy.validate("phone", "probe", address, "s", "s", 1000, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeerControlPolicy.validate("phone", "server", address, "s", "s", 1000, false)
        }
    }

    @Test fun controlServiceDoesNotAdvertisePumpSerialPortService() {
        assertNotEquals("00001101-0000-1000-8000-00805f9b34fb", PeerControlPolicy.SERVICE_UUID)
        assertNotEquals(PeerControlPolicy.expectedPeer("watch"), PeerControlPolicy.expectedPeer("phone"))
    }
}
