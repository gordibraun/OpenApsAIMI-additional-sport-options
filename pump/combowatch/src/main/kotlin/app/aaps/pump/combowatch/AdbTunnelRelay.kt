package app.aaps.pump.combowatch

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Carries bytes both ways between two stream pairs - here a TCP client on the phone and a Wear
 * channel to the watch - until either side ends, then closes all four streams exactly once.
 * No protocol knowledge: adb's own handshake and authentication pass through untouched.
 */
class AdbTunnelRelay(
    private val name: String,
    private val aIn: InputStream,
    private val aOut: OutputStream,
    private val bIn: InputStream,
    private val bOut: OutputStream,
    private val onActivity: () -> Unit = {},
    private val onClosed: (AdbTunnelRelay) -> Unit = {}
) {

    private val closed = AtomicBoolean(false)
    private val aToB = AtomicLong()
    private val bToA = AtomicLong()

    val bytesAtoB: Long get() = aToB.get()
    val bytesBtoA: Long get() = bToA.get()
    val isClosed: Boolean get() = closed.get()

    fun start() {
        pump("$name a>b", aIn, bOut, aToB)
        pump("$name b>a", bIn, aOut, bToA)
    }

    private fun pump(label: String, from: InputStream, to: OutputStream, counter: AtomicLong) = thread(name = label, isDaemon = true) {
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                to.write(buffer, 0, n)
                to.flush()
                counter.addAndGet(n.toLong())
                onActivity()
            }
        } catch (_: IOException) {
            // The other end went away, or we were closed: either way this direction is over.
        } finally {
            close()
        }
    }

    /** Ends both directions; safe to call from any thread and more than once. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        listOf(aIn, aOut, bIn, bOut).forEach { runCatching { it.close() } }
        onClosed(this)
    }

    private companion object {

        const val BUFFER_BYTES = 32 * 1024
    }
}
