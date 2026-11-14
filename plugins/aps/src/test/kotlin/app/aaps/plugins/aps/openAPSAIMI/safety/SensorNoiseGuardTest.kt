package app.aaps.plugins.aps.openAPSAIMI.safety

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SensorNoiseGuardTest {

    private val now = 1_800_000_000_000L
    private val profile = 1.0

    /** Readings five minutes apart, the last one twenty seconds old. */
    private fun readings(vararg mgdl: Int, stepMin: Int = 5) =
        mgdl.mapIndexed { i, v -> SensorNoiseGuard.Reading(now - 20_000L - (mgdl.size - 1 - i) * stepMin * 60_000L, v.toDouble()) }

    private fun decide(readings: List<SensorNoiseGuard.Reading>, smb: Double = 0.2, rate: Double? = 4.65) =
        SensorNoiseGuard.evaluate(SensorNoiseGuard.Input(readings, now, smb, rate, profile))

    @Test
    fun `one reading that jumps after a quiet stretch waits for the next`() {
        // 14.11.2026 00:28: 111, 118, then 137 - answered with a 500 % temporary basal and 0.2 U.
        val d = decide(readings(106, 108, 109, 111, 118, 137))
        assertTrue(d.held)
        assertEquals(0.0, d.smbU, 0.0)
        assertEquals(profile, d.rateUh!!, 0.0)
        assertTrue(d.reason!!.contains("не подтверждён"), d.reason)
    }

    @Test
    fun `a rise that builds up reading by reading is acted on`() {
        val d = decide(readings(110, 113, 119, 128, 140))
        assertFalse(d.held)
        assertEquals(0.2, d.smbU, 0.0)
        assertEquals(4.65, d.rateUh!!, 0.0)
    }

    @Test
    fun `a rise faster than glucose makes without fast carbs waits even when the reading before rose too`() {
        // 14.11.2026 01:07-01:12: 96, 120, 149 - fifty-three mg/dL in ten minutes at night.
        assertTrue(decide(readings(98, 96, 120)).held)
        assertTrue(decide(readings(98, 96, 120, 149)).held)
    }

    @Test
    fun `a level the next reading confirms is acted on`() {
        // 01:17: 149 again. Two readings at the new level are a level, whatever brought it.
        assertFalse(decide(readings(98, 96, 120, 149, 149)).held)
    }

    @Test
    fun `readings that go up and straight back hold extra insulin for the next three quarters of an hour`() {
        // 00:27-01:02 of that night: 137, 118, 90, 88, 94, 97, 98, 96.
        val d = decide(readings(118, 137, 118, 90, 88, 94, 97, 98, 96), smb = 0.1, rate = 1.3)
        assertTrue(d.held)
        assertTrue(d.zigzagMgdl >= SensorNoiseGuard.ZIGZAG_NOISY_MGDL)
        assertEquals(0.0, d.smbU, 0.0)
        assertEquals(profile, d.rateUh!!, 0.0)
        assertTrue(d.reason!!.contains("Сенсор шумит"), d.reason)
    }

    @Test
    fun `a clearly high level is corrected whatever the noise`() {
        val d = decide(readings(200, 182, 206, 186, 212), smb = 0.5, rate = 3.0)
        assertFalse(d.held)
        assertEquals(0.5, d.smbU, 0.0)
        assertEquals(3.0, d.rateUh!!, 0.0)
    }

    @Test
    fun `a jump above a level that is already high is acted on`() {
        assertFalse(decide(readings(176, 181, 205)).held)
        // The same jump from a level that is not high yet waits.
        assertTrue(decide(readings(146, 151, 175)).held)
    }

    @Test
    fun `the gentle turn of a real peak is not noise`() {
        val d = decide(readings(120, 135, 150, 165, 175, 178, 176, 170, 160), smb = 0.1, rate = 1.5)
        assertFalse(d.held)
        assertTrue(d.zigzagMgdl < 5.0)
    }

    @Test
    fun `lowering or stopping basal is never touched`() {
        val noisy = readings(118, 137, 118, 90, 88, 94, 97, 98, 96)
        val stop = decide(noisy, smb = 0.0, rate = 0.0)
        assertFalse(stop.held)
        assertEquals(0.0, stop.rateUh!!, 0.0)
        val less = decide(noisy, smb = 0.0, rate = 0.5)
        assertFalse(less.held)
        assertEquals(0.5, less.rateUh!!, 0.0)
        val none = decide(noisy, smb = 0.0, rate = null)
        assertFalse(none.held)
        assertNull(none.rateUh)
    }

    @Test
    fun `a microbolus is held even when the basal asked for is below the profile`() {
        val d = decide(readings(106, 108, 109, 111, 118, 137), smb = 0.3, rate = 0.4)
        assertTrue(d.held)
        assertEquals(0.0, d.smbU, 0.0)
        assertEquals(0.4, d.rateUh!!, 0.0)
    }

    @Test
    fun `without enough fresh readings the guard keeps out`() {
        assertFalse(decide(readings(118, 137)).held)
        val old = readings(106, 108, 109, 111, 118, 137).map { it.copy(atEpochMs = it.atEpochMs - 10 * 60_000L) }
        assertFalse(decide(old).held)
        assertFalse(decide(emptyList()).held)
    }

    @Test
    fun `a gap in the readings is not counted as a jump or as noise`() {
        // 105 at -15 min, nothing at -10 and -5, then 137: the step is fifteen minutes, not five.
        val gapped = listOf(
            SensorNoiseGuard.Reading(now - 25 * 60_000L, 104.0),
            SensorNoiseGuard.Reading(now - 20 * 60_000L, 106.0),
            SensorNoiseGuard.Reading(now - 15 * 60_000L, 105.0),
            SensorNoiseGuard.Reading(now - 20_000L, 137.0)
        )
        assertFalse(decide(gapped).held)
        // Only the one-point wobble before the gap counts; the step across the gap does not.
        assertEquals(1.0, SensorNoiseGuard.zigzag(gapped.sortedBy { it.atEpochMs }), 0.0)
    }

    @Test
    fun `the whole night of 14 November loses its noise-driven insulin`() {
        // Readings 00:17-01:32 and the loop's own decisions at the moments it added insulin.
        val night = intArrayOf(111, 118, 137, 118, 90, 88, 94, 97, 98, 96, 120, 149, 149, 131, 143)
        fun at(index: Int, smb: Double, rate: Double): SensorNoiseGuard.Decision {
            val upTo = night.take(index + 1)
            val shifted = upTo.mapIndexed { i, v -> SensorNoiseGuard.Reading(now - 20_000L - (upTo.size - 1 - i) * 5 * 60_000L, v.toDouble()) }
            return SensorNoiseGuard.evaluate(SensorNoiseGuard.Input(shifted, now, smb, rate, 1.23))
        }
        assertTrue(at(2, 0.2, 4.65).held)      // 00:27, 137 after 118
        assertTrue(at(10, 0.0, 1.5).held)      // 01:07, 120 after 96
        assertTrue(at(11, 0.2, 6.15).held)     // 01:12, 149 after 120
        assertFalse(at(12, 0.16, 3.0).held)    // 01:17, 149 confirmed
        assertTrue(at(14, 0.0, 2.73).held)     // 01:27, 143 after 131 after 149: back and forth
    }
}
