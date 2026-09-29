package app.aaps.combobench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProbeOptionsTest {
    @Test fun defaultDeadlineRemainsEightSeconds() {
        assertEquals(8, ProbeOptions(ProbeTransport.SDP).timeoutSeconds)
        assertEquals(0, ProbeOptions(ProbeTransport.SDP).holdSeconds)
    }

    @Test fun longerDeadlineDoesNotChangeAuthentication() {
        val options = ProbeOptions(ProbeTransport.CHANNEL_ONE_AUTHENTICATED, 20)
        assertEquals(20, options.timeoutSeconds)
        assertEquals(true, options.transport.authenticated)
    }

    @Test fun unknownOrUnboundedDeadlinesAreRejected() {
        listOf(-1, 0, 7, 9, 19, 21, Int.MAX_VALUE).forEach { seconds ->
            assertThrows(IllegalArgumentException::class.java) { ProbeOptions(ProbeTransport.SDP, seconds) }
        }
    }

    @Test fun holdIsBoundedAndPermissionIncludesItsWatchdog() {
        val options = ProbeOptions(ProbeTransport.CHANNEL_ONE_AUTHENTICATED, holdSeconds = 5)
        assertEquals(8, options.timeoutSeconds)
        assertEquals(15000L, options.permissionBudgetMs)
        listOf(-1, 1, 4, 6, Int.MAX_VALUE).forEach { seconds ->
            assertThrows(IllegalArgumentException::class.java) {
                ProbeOptions(ProbeTransport.CHANNEL_ONE_AUTHENTICATED, holdSeconds = seconds)
            }
        }
    }

    @Test fun holdCannotMixWithLongTimeoutOrUnsecuredTransport() {
        assertThrows(IllegalArgumentException::class.java) { ProbeOptions(ProbeTransport.SDP, holdSeconds = 5) }
        assertThrows(IllegalArgumentException::class.java) { ProbeOptions(ProbeTransport.CHANNEL_ONE_AUTHENTICATED, 20, 5) }
    }
}
