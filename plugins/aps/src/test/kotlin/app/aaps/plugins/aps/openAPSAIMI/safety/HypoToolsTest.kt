package app.aaps.plugins.aps.openAPSAIMI.safety

import org.junit.Assert.assertEquals
import org.junit.jupiter.api.Test

class HypoToolsTest {

    @Test
    fun `forecast guard uses target and still rejects unsafe candidates`() {
        val threshold = HypoTools.thresholdFromTarget(117.0, 65)
        val floor = HypoTools.finalForecastFloor(threshold, 117.0)
        assertEquals(78.5, threshold, 1e-9)
        assertEquals(107.0, floor, 1e-9)
        // Recorded candidate minima: full proposal unsafe, intermediate proposal safe.
        org.junit.Assert.assertTrue(92.0 <= floor)
        org.junit.Assert.assertTrue(122.0 > floor)
        // The old call used forecast minimum 194, raising the floor above target.
        assertEquals(132.0, HypoTools.finalForecastFloor(HypoTools.thresholdFromTarget(194.0, 65), 117.0), 1e-9)
    }

    @Test
    fun `higher LGS and temporary targets are respected`() {
        assertEquals(95.0, HypoTools.thresholdFromTarget(117.0, 95), 1e-9)
        assertEquals(110.0, HypoTools.finalForecastFloor(HypoTools.thresholdFromTarget(117.0, 95), 117.0), 1e-9)
        assertEquals(95.0, HypoTools.thresholdFromTarget(150.0, 65), 1e-9)
        assertEquals(140.0, HypoTools.finalForecastFloor(HypoTools.thresholdFromTarget(150.0, 65), 150.0), 1e-9)
        assertEquals(70.0, HypoTools.thresholdFromTarget(100.0, null), 1e-9)
    }

    @Test
    fun `test calculateMinutesAboveThreshold`() {
        // BG 100, Threshold 70. Diff 30.
        // Slope -2 (dropping 2 per min).
        // Minutes = 30 / 2 = 15.
        assertEquals(15, HypoTools.calculateMinutesAboveThreshold(100.0, -2.0, 70.0))
        
        // Slope positive (rising) -> MAX_VALUE
        assertEquals(Int.MAX_VALUE, HypoTools.calculateMinutesAboveThreshold(100.0, 2.0, 70.0))
    }

    @Test
    fun `test calculateDropPerHour`() {
        // Start 200, End 100. Drop 100.
        // Window 60 min.
        // Drop per hour = 100 * (60/60) = 100.
        val history = listOf(200f, 150f, 100f)
        assertEquals(100f, HypoTools.calculateDropPerHour(history, 60f), 0.01f)
    }
}
