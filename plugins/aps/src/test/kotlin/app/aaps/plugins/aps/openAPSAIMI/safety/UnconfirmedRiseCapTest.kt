package app.aaps.plugins.aps.openAPSAIMI.safety

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The first microbolus on a rise one reading alone shows is half a unit at most; a rise two readings show is left alone. */
class UnconfirmedRiseCapTest {

    private val now = 1_800_000_000_000L

    /** Readings as (minutes ago, mg/dL); the newest is taken twenty seconds before now unless said otherwise. */
    private fun readings(vararg points: Pair<Int, Int>) = points.map { (minutesAgo, mgdl) -> UnconfirmedRiseCap.Reading(now - 20_000L - minutesAgo * 60_000L, mgdl.toDouble()) }

    @Test
    fun `the night of 15 November - a jump across a missed reading gets half a unit, not 2_4`() {
        // 23:57 122, 00:02 120, 00:07 113, 00:12 112, [00:17 missed], 00:22 139; the loop asked for 2.4 U.
        val d = UnconfirmedRiseCap.evaluate(readings(25 to 122, 20 to 120, 15 to 113, 10 to 112, 0 to 139), now, 2.4)
        assertTrue(d.capped)
        assertEquals(0.5, d.smbU, 1e-9)
        assertTrue(d.acrossMissedReading)
        assertEquals(13.5, d.stepMgdlPer5Min, 0.01)
        assertEquals(10.0, d.overMinutes, 0.01)
    }

    @Test
    fun `the next reading falls back - nothing is cut because nothing rises`() {
        // 00:27: 130 after 139. Whatever the loop asks then is its own business.
        val d = UnconfirmedRiseCap.evaluate(readings(15 to 112, 5 to 139, 0 to 130), now, 0.3)
        assertFalse(d.capped)
        assertEquals(0.3, d.smbU, 1e-9)
    }

    @Test
    fun `a jump after a flat or falling stretch is one reading's word`() {
        val d = UnconfirmedRiseCap.evaluate(readings(10 to 121, 5 to 119, 0 to 140), now, 1.4)
        assertTrue(d.capped)
        assertEquals(0.5, d.smbU, 1e-9)
        assertFalse(d.acrossMissedReading)
        assertEquals(21.0, d.stepMgdlPer5Min, 0.01)
    }

    @Test
    fun `a rise the reading before already showed is confirmed and left alone`() {
        // 13.11.2026 style meal rise: +9 then +14.
        val d = UnconfirmedRiseCap.evaluate(readings(10 to 150, 5 to 159, 0 to 173), now, 2.0)
        assertFalse(d.capped)
        assertEquals(2.0, d.smbU, 1e-9)
    }

    @Test
    fun `a slow rise is not asked about, and a small microbolus is never raised`() {
        assertFalse(UnconfirmedRiseCap.evaluate(readings(10 to 120, 5 to 120, 0 to 127), now, 1.5).capped) // +7
        val small = UnconfirmedRiseCap.evaluate(readings(10 to 120, 5 to 120, 0 to 150), now, 0.3)
        assertFalse(small.capped)
        assertEquals(0.3, small.smbU, 1e-9)
    }

    @Test
    fun `exactly eight a five minutes counts, just under does not`() {
        assertTrue(UnconfirmedRiseCap.evaluate(readings(10 to 120, 5 to 120, 0 to 128), now, 1.0).capped)
        assertFalse(UnconfirmedRiseCap.evaluate(readings(10 to 120, 5 to 120, 0 to 127), now, 1.0).capped)
    }

    @Test
    fun `the step before must be more than two to confirm`() {
        assertTrue(UnconfirmedRiseCap.evaluate(readings(10 to 120, 5 to 122, 0 to 135), now, 1.0).capped)   // +2 then +13
        assertFalse(UnconfirmedRiseCap.evaluate(readings(10 to 120, 5 to 123, 0 to 136), now, 1.0).capped)  // +3 then +13
    }

    @Test
    fun `with no reading before the one before, the rise is one reading's word`() {
        // The reading before last is itself the first after a long silence.
        val d = UnconfirmedRiseCap.evaluate(readings(40 to 110, 5 to 118, 0 to 131), now, 1.2)
        assertTrue(d.capped)
        assertFalse(d.acrossMissedReading)
    }

    @Test
    fun `the reading's share is half a unit in all - what it has had already comes off`() {
        val readings = readings(10 to 112, 0 to 139)
        assertEquals(0.3, UnconfirmedRiseCap.evaluate(readings, now, 2.4, smbGivenSinceNewestU = 0.2).smbU, 1e-9)
        val spent = UnconfirmedRiseCap.evaluate(readings, now, 2.4, smbGivenSinceNewestU = 0.5)
        assertTrue(spent.capped)
        assertEquals(0.0, spent.smbU, 1e-9)
    }

    @Test
    fun `the same sample stored twice is one reading`() {
        val twice = listOf(
            UnconfirmedRiseCap.Reading(now - 620_000L, 112.0), UnconfirmedRiseCap.Reading(now - 20_500L, 139.0), UnconfirmedRiseCap.Reading(now - 20_000L, 139.0)
        )
        val d = UnconfirmedRiseCap.evaluate(twice, now, 2.4)
        assertTrue(d.capped)
        assertTrue(d.acrossMissedReading)
    }

    @Test
    fun `a decision made on an old reading, or without a microbolus, is not touched`() {
        assertFalse(UnconfirmedRiseCap.evaluate(readings(18 to 112, 8 to 139), now, 2.4).capped)
        assertFalse(UnconfirmedRiseCap.evaluate(readings(10 to 112, 0 to 139), now, 0.0).capped)
        assertFalse(UnconfirmedRiseCap.evaluate(readings(0 to 139), now, 2.4).capped)
    }
}
