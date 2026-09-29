package app.aaps.combobench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeTransportTest {
    @Test fun knownModesAreExplicit() {
        assertEquals(ProbeTransport.SDP, ProbeTransport.parse("sdp"))
        assertEquals(ProbeTransport.CHANNEL_ONE, ProbeTransport.parse("channel1"))
        assertEquals(ProbeTransport.SDP_AUTHENTICATED, ProbeTransport.parse("sdp-authenticated"))
        assertEquals(ProbeTransport.CHANNEL_ONE_AUTHENTICATED, ProbeTransport.parse("channel1-authenticated"))
    }

    @Test fun authenticationCannotBeEnabledByAnInsecureMode() {
        assertTrue(ProbeTransport.SDP_AUTHENTICATED.authenticated)
        assertTrue(ProbeTransport.CHANNEL_ONE_AUTHENTICATED.authenticated)
        assertFalse(ProbeTransport.SDP.authenticated)
        assertFalse(ProbeTransport.CHANNEL_ONE.authenticated)
    }

    @Test fun unknownModeDoesNotFallBack() {
        listOf("", "secure", "channel2", "SDP").forEach {
            assertThrows(IllegalArgumentException::class.java) { ProbeTransport.parse(it) }
        }
    }
}
