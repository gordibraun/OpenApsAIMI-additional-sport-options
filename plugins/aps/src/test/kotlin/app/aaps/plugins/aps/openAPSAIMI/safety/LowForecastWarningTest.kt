package app.aaps.plugins.aps.openAPSAIMI.safety

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** A forecast below 55 on two readings in a row is said once; the same reading twice, or one low forecast alone, is not. */
class LowForecastWarningTest {

    private val t0 = 1_800_000_000_000L
    private fun minutes(m: Int) = m * 60_000L

    /** A forecast falling from [from] by [per5] every five minutes down to [floor]. */
    private fun falling(from: Int, per5: Int, floor: Int) = List(49) { maxOf(floor, from - it * per5) }

    private fun run(atMin: Int, readingMin: Int, forecast: List<Int>) = LowForecastWarning.Run(t0 + minutes(atMin), t0 + minutes(readingMin), forecast)

    @Test
    fun `the night of 15 November - said at the second reading, at a glucose of 125`() {
        var state = LowForecastWarning.State()
        // 00:23 the run that gave 2.4 U: forecast up to 209, nothing to say.
        LowForecastWarning.evaluate(state, run(0, 0, List(49) { 139 + it })).also { state = it.state; assertNull(it.warning) }
        // 00:28 on the reading of 130: 130 … 46. First low forecast: remembered, not said.
        LowForecastWarning.evaluate(state, run(5, 5, falling(130, 3, 46))).also { state = it.state; assertNull(it.warning) }
        // 00:30 the same reading looked at again: nothing new.
        LowForecastWarning.evaluate(state, run(7, 5, falling(130, 3, 49))).also { state = it.state; assertNull(it.warning) }
        // 00:33 on the reading of 125: 125 … 40. Second reading in a row: said.
        val second = LowForecastWarning.evaluate(state, run(10, 10, falling(125, 4, 40)))
        assertNotNull(second.warning)
        assertEquals(40, second.warning!!.minMgdl)
        assertEquals(70, second.warning!!.below70InMinutes)
        state = second.state
        // 00:38 still low: the same episode, not said again.
        assertNull(LowForecastWarning.evaluate(state, run(15, 15, falling(121, 4, 41))).warning)
    }

    @Test
    fun `one low forecast between two that are not is not a warning`() {
        var state = LowForecastWarning.State()
        LowForecastWarning.evaluate(state, run(0, 0, falling(150, 4, 50))).also { state = it.state; assertNull(it.warning) }
        LowForecastWarning.evaluate(state, run(5, 5, falling(150, 2, 90))).also { state = it.state; assertNull(it.warning) }
        assertNull(LowForecastWarning.evaluate(state, run(10, 10, falling(150, 4, 50))).warning)
    }

    @Test
    fun `a low only beyond three hours, or a floor of 55 itself, does not count`() {
        val lateLow = List(49) { if (it <= 36) 100 else 40 }
        var state = LowForecastWarning.State()
        LowForecastWarning.evaluate(state, run(0, 0, lateLow)).also { state = it.state }
        assertNull(LowForecastWarning.evaluate(state, run(5, 5, lateLow)).warning)
        state = LowForecastWarning.State()
        LowForecastWarning.evaluate(state, run(0, 0, falling(120, 5, 55))).also { state = it.state }
        assertNull(LowForecastWarning.evaluate(state, run(5, 5, falling(118, 5, 55))).warning)
    }

    @Test
    fun `readings too far apart are not in a row`() {
        var state = LowForecastWarning.State()
        LowForecastWarning.evaluate(state, run(0, 0, falling(130, 4, 45))).also { state = it.state }
        assertNull(LowForecastWarning.evaluate(state, run(20, 20, falling(120, 4, 45))).warning)
        // …but that one starts a new row.
        LowForecastWarning.evaluate(state, run(20, 20, falling(120, 4, 45))).also { state = it.state }
        assertNotNull(LowForecastWarning.evaluate(state, run(25, 25, falling(115, 4, 45))).warning)
    }

    @Test
    fun `after half an hour without, a low forecast twice in a row is a new warning`() {
        var state = LowForecastWarning.State()
        LowForecastWarning.evaluate(state, run(0, 0, falling(130, 4, 45))).also { state = it.state }
        LowForecastWarning.evaluate(state, run(5, 5, falling(126, 4, 45))).also { state = it.state; assertNotNull(it.warning) }
        // Forty minutes of forecasts that are fine.
        for (m in 10..45 step 5) LowForecastWarning.evaluate(state, run(m, m, falling(120, 1, 95))).also { state = it.state }
        LowForecastWarning.evaluate(state, run(50, 50, falling(110, 4, 45))).also { state = it.state; assertNull(it.warning) }
        assertNotNull(LowForecastWarning.evaluate(state, run(55, 55, falling(105, 4, 45))).warning)
    }
}
