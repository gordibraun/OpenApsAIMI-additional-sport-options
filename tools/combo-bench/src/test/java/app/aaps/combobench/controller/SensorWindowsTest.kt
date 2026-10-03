package app.aaps.combobench.controller

import app.aaps.pump.combowatch.regulation.GlucoseReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorWindowsTest {

    private val t0 = 1_800_000_000_000L
    private fun readingsEndingAt(last: Long) = List(6) { GlucoseReading(last - it * SensorWindows.PERIOD_MS, 120.0) }

    @Test fun rightAfterAReadingThePumpMayBeUsed() {
        assertNull(SensorWindows.waitUntil(t0 + 2_000, readingsEndingAt(t0)))
        assertNull(SensorWindows.waitUntil(t0 + 120_000, readingsEndingAt(t0)))
    }

    @Test fun aSessionThatWouldRunIntoTheNextWindowWaitsUntilTheWindowIsOver() {
        val next = t0 + SensorWindows.PERIOD_MS
        // 50 seconds before the window: a minute-long session would overlap it.
        assertEquals(next + SensorWindows.WINDOW_MS, SensorWindows.waitUntil(next - 50_000, readingsEndingAt(t0)))
        // During the window too.
        assertEquals(next + SensorWindows.WINDOW_MS, SensorWindows.waitUntil(next + 10_000, readingsEndingAt(t0)))
        // Just after it: free.
        assertNull(SensorWindows.waitUntil(next + SensorWindows.WINDOW_MS + 1_000, readingsEndingAt(t0)))
    }

    @Test fun aMissedReadingDoesNotLoseTheRhythm() {
        // Last reading 40 minutes ago; the transmitter still speaks every five minutes.
        val last = t0 - 40 * 60_000L
        val next = t0 + 5 * 60_000L   // t0 is exactly on a window boundary (t0 - last = 8 periods)
        assertEquals(t0 + SensorWindows.WINDOW_MS, SensorWindows.waitUntil(t0 - 20_000, readingsEndingAt(last)))
        assertNull(SensorWindows.waitUntil(t0 + 60_000, readingsEndingAt(last)))
        assertEquals(next + SensorWindows.WINDOW_MS, SensorWindows.waitUntil(next - 30_000, readingsEndingAt(last)))
    }

    @Test fun withoutRecentReadingsNothingIsPredicted() {
        assertNull(SensorWindows.waitUntil(t0, emptyList()))
        assertNull(SensorWindows.waitUntil(t0, readingsEndingAt(t0 - 4 * 60 * 60_000L)))
    }
}
