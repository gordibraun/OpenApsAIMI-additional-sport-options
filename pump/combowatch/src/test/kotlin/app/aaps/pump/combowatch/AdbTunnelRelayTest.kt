package app.aaps.pump.combowatch

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AdbTunnelRelayTest {

    /** One side of the relay as the test sees it: what we feed in, and what comes out. */
    private class Side {

        val feed = PipedOutputStream()
        val input = PipedInputStream(feed, 1 shl 16)
        val drain = PipedInputStream(1 shl 16)
        val output = PipedOutputStream(drain)

        fun read(n: Int): ByteArray {
            val out = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = drain.read(out, got, n - got)
                check(r >= 0) { "stream ended after $got of $n bytes" }
                got += r
            }
            return out
        }
    }

    @Test
    fun `bytes pass both ways untouched and in order`() {
        val a = Side()
        val b = Side()
        val relay = AdbTunnelRelay("t", a.input, a.output, b.input, b.output)
        relay.start()

        val toWatch = ByteArray(100_000) { (it * 7).toByte() }
        a.feed.write(toWatch)
        a.feed.flush()
        assertArrayEquals(toWatch, b.read(toWatch.size))

        val back = "OKAY\u0000\u0001".toByteArray()
        b.feed.write(back)
        b.feed.flush()
        assertArrayEquals(back, a.read(back.size))

        assertEquals(toWatch.size.toLong(), relay.bytesAtoB)
        assertEquals(back.size.toLong(), relay.bytesBtoA)
        relay.close()
    }

    @Test
    fun `end of either side closes everything exactly once`() {
        val a = Side()
        val b = Side()
        val closedCount = AtomicInteger()
        val closed = CountDownLatch(1)
        val relay = AdbTunnelRelay("t", a.input, a.output, b.input, b.output, onClosed = { closedCount.incrementAndGet(); closed.countDown() })
        relay.start()

        a.feed.close() // the TCP client hung up

        assertTrue(closed.await(5, TimeUnit.SECONDS), "relay did not close")
        assertTrue(relay.isClosed)
        relay.close() // a second close is a no-op
        Thread.sleep(100)
        assertEquals(1, closedCount.get())
        assertEquals(-1, b.drain.read(), "the watch side must see end of stream")
    }
}
