package app.aaps.pump.combowatch.regulation

import app.aaps.core.data.iob.InMemoryGlucoseValue
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** The phone's three-point mean on the watch: inner readings smoothed, the ends and uneven gaps left alone. */
class GlucoseSmoothingTest {

    private val now = 1_800_000_000_000L
    private fun series(vararg newestFirst: Double, stepMs: Long = 300_000L) =
        newestFirst.mapIndexed { i, v -> InMemoryGlucoseValue(timestamp = now - i * stepMs, value = v) }.toMutableList()

    @Test fun `inner readings become the mean of three, the newest and the oldest stay as read`() {
        val data = GlucoseSmoothing.smooth(series(150.0, 130.0, 150.0, 130.0, 150.0, 130.0))
        assertThat(data[0].smoothed).isNull()
        assertThat(data[1].smoothed).isWithin(0.01).of((150.0 + 130.0 + 150.0) / 3)
        assertThat(data[4].smoothed).isWithin(0.01).of((130.0 + 150.0 + 130.0) / 3)
        assertThat(data[5].smoothed).isNull()
        assertThat(data[1].recalculated).isWithin(0.01).of(143.33)
        assertThat(data[0].recalculated).isEqualTo(150.0)
    }

    @Test fun `a gap in the readings leaves its neighbours unsmoothed`() {
        val data = series(150.0, 130.0, 150.0, 130.0, 150.0, 130.0)
        data[2] = InMemoryGlucoseValue(timestamp = data[2].timestamp - 240_000L, value = 150.0)
        GlucoseSmoothing.smooth(data)
        assertThat(data[1].smoothed).isNull()
        assertThat(data[3].smoothed).isNull()
        assertThat(data[4].smoothed).isNotNull()
    }

    @Test fun `fewer than five readings are left alone`() {
        val data = GlucoseSmoothing.smooth(series(150.0, 130.0, 150.0, 130.0))
        assertThat(data.all { it.smoothed == null }).isTrue()
    }

    @Test fun `the watch's rate of change comes from the readings as given`() {
        // Since 11.11.2026, by the owner's word: smoothing is for the screen only, the watch's decisions take the
        // readings as they are. (Before, the newest reading was set against a smoothed neighbour: about +7 here.)
        val readings = (0 until 9).map { GlucoseReading(now - it * 300_000L, if (it % 2 == 0) 150.0 else 130.0) }
        val trend = GlucoseTrend.from(readings, now)!!
        assertThat(trend.mgdl).isEqualTo(150.0)
        assertThat(abs(trend.delta)).isWithin(0.01).of(20.0)
        assertThat(trend.known).isTrue()
    }
}
