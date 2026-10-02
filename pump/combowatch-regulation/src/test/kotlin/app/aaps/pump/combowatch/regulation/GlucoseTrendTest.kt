package app.aaps.pump.combowatch.regulation

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GlucoseTrendTest {

    private val now = 1_800_000_000_000L
    private fun at(minutesAgo: Double, mgdl: Double) = GlucoseReading(now - (minutesAgo * 60_000).toLong(), mgdl)

    @Test fun `no readings, no trend`() {
        assertThat(GlucoseTrend.from(emptyList(), now)).isNull()
    }

    @Test fun `a steady fall is reported per five minutes and per hour`() {
        val trend = GlucoseTrend.from(List(9) { at(it * 5.0, 100.0 + 5.0 * it) }, now)!!
        assertThat(trend.mgdl).isEqualTo(100.0)
        assertThat(trend.known).isTrue()
        assertThat(trend.delta).isWithin(0.01).of(-5.0)
        assertThat(trend.shortAvgDelta).isWithin(0.01).of(-5.0)
        assertThat(trend.longAvgDelta).isWithin(0.01).of(-5.0)
        assertThat(trend.fallPerHour!!).isWithin(0.01).of(60.0)
    }

    @Test fun `readings in any order, and the same reading twice, give the same answer`() {
        val ordered = List(6) { at(it * 5.0, 100.0 + 2.0 * it) }
        val shuffled = (ordered + ordered[2]).reversed()
        assertThat(GlucoseTrend.from(shuffled, now)).isEqualTo(GlucoseTrend.from(ordered, now))
    }

    @Test fun `what the sensor reports outside its range is not a glucose value`() {
        val trend = GlucoseTrend.from(listOf(at(0.0, 39.0), at(5.0, 110.0), at(10.0, 112.0), at(15.0, 401.0)), now)!!
        assertThat(trend.mgdl).isEqualTo(110.0)
        assertThat(trend.delta).isWithin(0.01).of(-2.0)
    }

    @Test fun `a reading stamped in the future is not the present`() {
        val trend = GlucoseTrend.from(listOf(at(-10.0, 200.0), at(0.0, 110.0), at(5.0, 110.0)), now)!!
        assertThat(trend.mgdl).isEqualTo(110.0)
    }

    @Test fun `with the previous reading too long ago the direction is unknown`() {
        val trend = GlucoseTrend.from(listOf(at(0.0, 110.0), at(25.0, 150.0)), now)!!
        assertThat(trend.known).isFalse()
        assertThat(trend.delta).isEqualTo(0.0)
        // The half-hour fall is still there to be seen.
        assertThat(trend.fallPerHour!!).isWithin(0.01).of(96.0)
    }
}
