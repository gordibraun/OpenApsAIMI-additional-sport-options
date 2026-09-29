package app.aaps.rfcommpeer;

import org.junit.Test;
import static org.junit.Assert.*;

public class PeerPolicyTest {
    @Test public void serviceSelectionDoesNotChangeTheAllowedPeer() {
        assertNotEquals(PeerPolicy.UUID, PeerPolicy.SERIAL_UUID);
        assertEquals("00001101-0000-1000-8000-00805f9b34fb", java.util.UUID.fromString(PeerPolicy.SERIAL_UUID).toString());
        assertThrows(IllegalArgumentException.class, () -> PeerPolicy.validate("OPWWE251", "00:0E:2F:25:24:BC", "spp", 0));
    }
    @Test public void recognizesOnlyTheTestDevices() {
        assertEquals(PeerPolicy.PHONE, PeerPolicy.peer("OPWWE251"));
        assertEquals(PeerPolicy.WATCH, PeerPolicy.peer("SM-G955F"));
        assertThrows(IllegalArgumentException.class, () -> PeerPolicy.peer("SM-S918B"));
    }
    @Test public void pumpAddressAndCompanionPhoneAreRejected() {
        for (String target : new String[]{"00:0E:2F:25:24:BC", "BC:B2:CC:26:0D:79", PeerPolicy.WATCH}) {
            assertThrows(IllegalArgumentException.class, () -> PeerPolicy.validate("OPWWE251", target, "test", 0));
        }
    }
    @Test public void validatesIdsAndDelays() {
        PeerPolicy.validate("OPWWE251", PeerPolicy.PHONE, "test-1", 180000);
        assertThrows(IllegalArgumentException.class, () -> PeerPolicy.validate("OPWWE251", PeerPolicy.PHONE, "../file", 0));
        assertThrows(IllegalArgumentException.class, () -> PeerPolicy.validate("OPWWE251", PeerPolicy.PHONE, "test", -1));
        assertThrows(IllegalArgumentException.class, () -> PeerPolicy.validate("OPWWE251", PeerPolicy.PHONE, "test", 180001));
    }
}
