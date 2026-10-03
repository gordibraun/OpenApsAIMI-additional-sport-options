package app.aaps.pump.combowatch.regulation

import app.aaps.pump.combowatch.protocol.RegulationSnapshot
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The forecast is the phone's formula; what is tested here is what the watch feeds it - above all
 * that everything the pump did after the snapshot is counted once, and nothing twice.
 */
class WatchForecastTest {

    private fun forecast(fixture: Fixture): WatchForecast {
        val trend = GlucoseTrend.from(fixture.readings, fixture.now)!!
        return WatchForecast(fixture.snapshot!!, trend, DeliveryLog(fixture.tbrs, fixture.boluses), { 1.2 }, fixture.now)
    }

    @Test fun `with nothing on board and nothing done, glucose is forecast to stay where it is`() {
        val series = forecast(Fixture().glucose(120.0)).series(1.2)
        assertThat(series).hasSize(49)
        assertThat(series.toSet()).containsExactly(120)
    }

    @Test fun `less basal for half an hour raises the forecast, and only later`() {
        val forecast = forecast(Fixture().glucose(120.0))
        val normal = forecast.series(1.2)
        val stopped = forecast.series(0.0)
        // 0.6 U less at 50 mg/dL per unit is 30 mg/dL once it has all acted; most of it within four hours.
        assertThat(stopped.last() - normal.last()).isIn(15..30)
        assertThat(stopped[1] - normal[1]).isAtMost(1)
        for (step in 1 until stopped.size) assertThat(stopped[step]).isAtLeast(normal[step])
    }

    @Test fun `basal the watch already held back since the snapshot is in the forecast`() {
        val untouched = Fixture().glucose(120.0)
        untouched.snapshot = untouched.snapshot(ageMinutes = 40.0)
        val stopped = Fixture().glucose(120.0)
        stopped.snapshot = stopped.snapshot(ageMinutes = 40.0)
        stopped.tbrs = listOf(
            TbrSegment(stopped.minutesAgo(40.0), 0, 30, byWatch = true),
            TbrSegment(stopped.minutesAgo(10.0), 0, 30, byWatch = true)
        )
        val a = forecast(untouched).series(1.2)
        val b = forecast(stopped).series(1.2)
        // 40 minutes without 1.2 U/h is 0.8 U that will not act: up to 40 mg/dL at 50 per unit.
        assertThat(b.last() - a.last()).isIn(20..40)
    }

    @Test fun `a bolus given after the snapshot lowers the forecast, one given before it is already in the snapshot`() {
        val base = Fixture().glucose(160.0)
        base.snapshot = base.snapshot(ageMinutes = 20.0)
        val after = Fixture().glucose(160.0)
        after.snapshot = after.snapshot(ageMinutes = 20.0)
        after.boluses = listOf(BolusRecord(after.minutesAgo(10.0), 1.0))
        val before = Fixture().glucose(160.0)
        before.snapshot = before.snapshot(ageMinutes = 20.0)
        before.boluses = listOf(BolusRecord(before.minutesAgo(40.0), 1.0))

        val plain = forecast(base).series(1.2)
        val lowered = forecast(after).series(1.2)
        assertThat(plain.last() - lowered.last()).isIn(30..50)
        assertThat(forecast(before).series(1.2)).isEqualTo(plain)
    }

    @Test fun `a bolus in the minutes just before the snapshot is counted rather than risked`() {
        // The pump's clock and the phone's may differ by a couple of minutes either way.
        val base = Fixture().glucose(160.0)
        base.snapshot = base.snapshot(ageMinutes = 20.0)
        val close = Fixture().glucose(160.0)
        close.snapshot = close.snapshot(ageMinutes = 20.0)
        close.boluses = listOf(BolusRecord(close.minutesAgo(21.0), 1.0))
        assertThat(forecast(close).series(1.2).last()).isLessThan(forecast(base).series(1.2).last())
    }

    @Test fun `the rest of a temporary basal the snapshot counted on is taken out when a candidate replaces it`() {
        val counting = Fixture().glucose(140.0)
        counting.snapshot = counting.snapshot(
            ageMinutes = 2.0,
            assumedTbr = RegulationSnapshot.AssumedTbr(rateUph = 2.4, endsAtEpochMs = counting.now + 20 * 60_000L)
        )
        // The pump did run at 200 % since the snapshot, exactly as it assumed.
        counting.tbrs = listOf(TbrSegment(counting.minutesAgo(10.0), 200, 30, byWatch = false))
        val plain = Fixture().glucose(140.0)

        val replaced = forecast(counting).series(1.2)
        val reference = forecast(plain).series(1.2)
        // 1.2 U/h above profile for the 20 minutes that will not happen is 0.4 U: up to 20 mg/dL higher.
        assertThat(replaced.last() - reference.last()).isIn(8..20)
    }

    @Test fun `carbohydrates left at the snapshot go on being absorbed with time`() {
        val fresh = Fixture().glucose(120.0)
        fresh.snapshot = fresh.snapshot(ageMinutes = 2.0, cob = 30.0)
        val later = Fixture().glucose(120.0)
        later.snapshot = later.snapshot(ageMinutes = 90.0, cob = 30.0)
        assertThat(forecast(fresh).cobNowG).isWithin(1.5).of(30.0)
        assertThat(forecast(later).cobNowG).isLessThan(20.0)
        assertThat(forecast(fresh).series(1.2).last()).isGreaterThan(120)
    }

    @Test fun `a snapshot is used for a forecast only while it can support one`() {
        val fixture = Fixture()
        assertThat(WatchForecast.usable(fixture.snapshot(ageMinutes = 2.0), fixture.now)).isTrue()
        assertThat(WatchForecast.usable(fixture.snapshot(ageMinutes = 239.0), fixture.now)).isTrue()
        assertThat(WatchForecast.usable(fixture.snapshot(ageMinutes = 241.0), fixture.now)).isFalse()
        assertThat(WatchForecast.usable(fixture.snapshot(sensitivity = 0.0), fixture.now)).isFalse()
        assertThat(WatchForecast.usable(fixture.snapshot(carbRatio = 0.0), fixture.now)).isFalse()
        assertThat(WatchForecast.usable(fixture.snapshot().copy(insulinRemaining = Fixture.CURVE.take(97)), fixture.now)).isFalse()
        assertThat(WatchForecast.usable(fixture.snapshot().copy(insulinActivity = emptyList()), fixture.now)).isFalse()
        assertThat(WatchForecast.usable(fixture.snapshot().copy(insulinActivity = listOf(Double.NaN)), fixture.now)).isFalse()
    }

    @Test fun `carbohydrates entered on the watch after the snapshot lift the forecast`() {
        val fixture = Fixture().glucose(100.0)
        fixture.snapshot = fixture.snapshot(bolusUnits = 2.0, bolusAgeAtSnapshot = 10.0)
        val without = WatchForecast(fixture.snapshot!!, GlucoseTrend.from(fixture.readings, fixture.now)!!, DeliveryLog(), { 1.2 }, fixture.now)
        val with = WatchForecast(
            fixture.snapshot!!, GlucoseTrend.from(fixture.readings, fixture.now)!!,
            DeliveryLog(carbs = listOf(CarbsRecord(fixture.minutesAgo(1.0), 30))), { 1.2 }, fixture.now
        )
        assertThat(with.cobNowG).isGreaterThan(without.cobNowG + 25.0)
        assertThat(with.series(1.2).minOrNull()!!).isGreaterThan(without.series(1.2).minOrNull()!!)

        // Entered before the snapshot: the phone's figure already holds them.
        val earlier = WatchForecast(
            fixture.snapshot!!, GlucoseTrend.from(fixture.readings, fixture.now)!!,
            DeliveryLog(carbs = listOf(CarbsRecord(fixture.minutesAgo(30.0), 30))), { 1.2 }, fixture.now
        )
        assertThat(earlier.cobNowG).isEqualTo(without.cobNowG)
    }
}
