package app.aaps.pump.combowatch.regulation

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The morning of 16.11.2026, in minutes from the stop of basal the phone set at 08:09: it was due to run out at
 * 08:39, the phone's next stop (sent at 08:33) never reached the watch, and glucose went 63, 59, 56, 52, 49, 47.
 */
class LowGlucoseHoldTest {

    private val setAt = 1_800_000_000_000L
    private fun at(minutes: Int) = setAt + minutes * 60_000L
    private val stop = TbrSegment(setAt, percent = 0, durationMinutes = 30)

    private fun readings(now: Long, newest: Double) = (5 downTo 0).map { GlucoseReading(now - it * 5 * 60_000L - 30_000L, newest + it * 3.0) }

    private fun decide(nowMinutes: Int, glucose: Double, vararg tbrs: TbrSegment) =
        LowGlucoseHold.decide(at(nowMinutes), readings(at(nowMinutes), glucose), DeliveryLog(tbrs.toList()))

    @Test fun `a stop about to run out with glucose low and nothing in its place is renewed`() {
        val hold = decide(25, 59.0, stop)!!
        assertThat(hold.glucoseMgdl).isEqualTo(59)
        assertThat(hold.running).isEqualTo(stop)
        assertThat(hold.text).contains("сахар 59, остановка базала на помпе истекает в ")
        assertThat(hold.text).contains("часы сами остановили базал ещё на 30 мин")
    }

    @Test fun `a stop that still covers the time to the next reading is left alone`() {
        assertThat(decide(10, 59.0, stop)).isNull()
        assertThat(decide(22, 59.0, stop)).isNull()
        // Seven minutes left: the next reading would find it gone.
        assertThat(decide(23, 59.0, stop)).isNotNull()
    }

    @Test fun `a stop that has just run out is renewed too, for as long as it is the same story`() {
        val hold = decide(34, 52.0, stop)!!
        assertThat(hold.text).contains("истекла в ")
        assertThat(decide(49, 47.0, stop)).isNotNull()
        // Twenty minutes on, what the pump runs is no longer a lapse the watch can mend with a stop.
        assertThat(decide(51, 47.0, stop)).isNull()
    }

    @Test fun `with glucose at seventy or above the stop runs out as it was meant to`() {
        assertThat(decide(25, 70.0, stop)).isNull()
        assertThat(decide(34, 88.0, stop)).isNull()
        assertThat(decide(25, 69.0, stop)).isNotNull()
    }

    @Test fun `a reading that is old says nothing about now`() {
        val now = at(25)
        val old = listOf(GlucoseReading(now - 8 * 60_000L, 55.0), GlucoseReading(now - 13 * 60_000L, 58.0))
        assertThat(LowGlucoseHold.decide(now, old, DeliveryLog(listOf(stop)))).isNull()
        assertThat(LowGlucoseHold.decide(now, emptyList(), DeliveryLog(listOf(stop)))).isNull()
    }

    @Test fun `a stop that somebody replaced or cancelled was a decision, not a lapse`() {
        // Cancelled at minute 20.
        assertThat(decide(25, 59.0, stop.copy(endedEpochMs = at(20)))).isNull()
        // Replaced by basal above the reduced range: the last word was to deliver.
        assertThat(decide(25, 59.0, stop.copy(endedEpochMs = at(20)), TbrSegment(at(20), 90, 30))).isNull()
        // Replaced by another stop, which runs well past the next reading.
        assertThat(decide(25, 59.0, stop.copy(endedEpochMs = at(20)), TbrSegment(at(20), 0, 30))).isNull()
    }

    @Test fun `with no basal reduced at all the watch does not start a stop of its own`() {
        assertThat(decide(25, 59.0)).isNull()
        assertThat(decide(25, 59.0, TbrSegment(setAt, 120, 30))).isNull()
        assertThat(decide(25, 59.0, TbrSegment(setAt, 60, 30))).isNull()
    }

    @Test fun `a basal reduced to half or less is renewed as a stop`() {
        val hold = decide(25, 61.0, TbrSegment(setAt, 40, 30))!!
        assertThat(hold.text).contains("базал 40 % на помпе истекает")
        assertThat(decide(25, 61.0, TbrSegment(setAt, 50, 30))).isNotNull()
    }

    @Test fun `after the watch's own stop there is nothing more to do until that one runs out`() {
        val own = TbrSegment(at(25), 0, 30, byWatch = true)
        assertThat(decide(30, 52.0, stop.copy(endedEpochMs = at(25)), own)).isNull()
        assertThat(decide(45, 49.0, stop.copy(endedEpochMs = at(25)), own)).isNull()
        // Half an hour later, still low and still nobody: again.
        assertThat(decide(50, 58.0, stop.copy(endedEpochMs = at(25)), own)).isNotNull()
    }
}
