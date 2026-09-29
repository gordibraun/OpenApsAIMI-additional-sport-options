package app.aaps.plugins.aps.openAPSAIMI.learning

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.utils.DateUtil
import io.mockk.every
import io.mockk.mockk
import io.reactivex.rxjava3.core.Single
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LearningDataRegressionTest {
    private val now = 1_800_000_000_000L
    private val persistence = mockk<PersistenceLayer>()
    private val dateUtil = mockk<DateUtil> { every { now() } returns this@LearningDataRegressionTest.now }

    private fun reading(time: Long, bg: Double) = GV(
        timestamp = time, raw = null, value = bg, trendArrow = TrendArrow.NONE,
        noise = null, sourceSensor = SourceSensor.UNKNOWN
    )

    private fun analyze(readings: List<GV>): UnifiedReactivityLearner.GlycemicPerformance? {
        every { persistence.getBgReadingsDataFromTime(any(), false) } returns Single.just(readings)
        return UnifiedReactivityLearner(persistence, dateUtil, mockk(relaxed = true), mockk(relaxed = true)).analyzeLast24h()
    }

    @Test
    fun `140 belongs to one range only`() {
        val result = analyze((0..287).map { reading(now - it * 300_000L, 140.0) })
        assertNotNull(result)
        assertEquals(100.0, result!!.tir70_180, 1e-9)
    }

    @Test
    fun `duplicate readings do not change the sample count`() {
        val readings = (0..287).map { reading(now - it * 300_000L, 110.0) }
        val result = analyze(readings + readings)
        assertNotNull(result)
        assertEquals(288, result!!.total_readings)
    }

    @Test
    fun `time in range is not biased by one minute sampling during high glucose`() {
        val start = now - 120 * 60_000L
        val normal = (0..11).map { reading(start + it * 300_000L, 110.0) }
        val high = (60..119).map { reading(start + it * 60_000L, 200.0) }
        val result = GlycemicStats.calculate((normal + high).reversed(), start, now, 6)!!
        assertEquals(50.0, result.tir70_180, 1e-9)
        assertEquals(50.0, result.tir_above_180, 1e-9)
        assertEquals(100.0, result.coveragePercent, 1e-9)
    }

    @Test
    fun `sensor gaps are not glucose oscillations or observed time`() {
        val start = now - 24 * 3_600_000L
        val beforeGap = (0..11).map { reading(start + it * 300_000L, 110.0) }
        val afterGap = (0..11).map { reading(now - 3_600_000L + it * 300_000L, 140.0) }
        val result = GlycemicStats.calculate(beforeGap + afterGap, start, now, 12)!!
        assertEquals(0, result.crossing_count)
        assertEquals(120.0, result.observedMinutes, 1e-9)
        assertEquals(100.0 / 12, result.coveragePercent, 1e-9)
    }

    @Test
    fun `known bad data are excluded but genuine low values are retained`() {
        val normal = (0..287).map { reading(now - it * 300_000L, 110.0) }
        val extra = listOf(
            reading(now - 30_000L, 30.0),
            reading(now - 60_000L, 38.0),
            reading(now - 90_000L, Double.NaN),
            reading(now - 120_000L, 60.0).copy(isValid = false),
            reading(now - 150_000L, 60.0).copy(noise = 3.0),
            reading(now + 300_000L, 200.0)
        )
        val result = analyze(normal + extra)!!
        assertEquals(289, result.total_readings)
        assertEquals(1, result.hypo_count)
    }

    @Test
    fun `fast duplicate-like samples cannot pretend to span an hour`() {
        val short = (0..11).map { reading(now - it * 60_000L, 110.0) }
        assertNull(analyze(short))
    }

    @Test
    fun `two hour analysis uses the same disjoint ranges`() {
        val samples = (0..23).map { reading(now - it * 300_000L, 180.0) }
        every { persistence.getBgReadingsDataFromTime(any(), false) } returns Single.just(samples)
        val result = UnifiedReactivityLearner(persistence, dateUtil, mockk(relaxed = true), mockk(relaxed = true)).analyzeLast2h()!!
        assertEquals(100.0, result.tir70_180, 1e-9)
        assertEquals(0.0, result.tir180_250, 1e-9)
    }
}
