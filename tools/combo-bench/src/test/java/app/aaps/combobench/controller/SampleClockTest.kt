package app.aaps.combobench.controller

import org.junit.Assert.assertEquals
import org.junit.Test

/** A sample time is read against both boot clocks; the plausible one nearest to now wins. */
class SampleClockTest {

    private val now = 1_800_000_000_000L
    private fun h(x: Double) = (x * 3_600_000L).toLong()

    @Test fun `durations on the realtime clock give the sample time when deep sleep would push the other reading into the future`() {
        val elapsed = h(26.0); val uptime = h(17.0)      // nine hours of deep sleep since boot
        val duration = elapsed - 60_000L                  // a sample taken a minute ago, realtime semantics
        assertEquals(now - 60_000L, SampleClock.resolve(now, elapsed, uptime, duration))
    }

    @Test fun `durations on the awake-time clock are not taken nine hours back`() {
        val elapsed = h(26.0); val uptime = h(17.0)
        val duration = uptime - 60_000L                   // a sample taken a minute ago, uptime semantics (03.11.2026)
        assertEquals(now - 60_000L, SampleClock.resolve(now, elapsed, uptime, duration))
    }

    @Test fun `with little deep sleep both readings are plausible and the later one, nearest to now, is kept`() {
        val elapsed = h(5.0); val uptime = h(4.5)
        val duration = uptime - 120_000L
        assertEquals(now - 120_000L, SampleClock.resolve(now, elapsed, uptime, duration))
    }

    @Test fun `a reading that lands in the future or hours ago falls back to now`() {
        val elapsed = h(26.0); val uptime = h(17.0)
        assertEquals(now, SampleClock.resolve(now, elapsed, uptime, elapsed + h(1.0)))
        assertEquals(now, SampleClock.resolve(now, elapsed, uptime, h(1.0)))
    }
}
