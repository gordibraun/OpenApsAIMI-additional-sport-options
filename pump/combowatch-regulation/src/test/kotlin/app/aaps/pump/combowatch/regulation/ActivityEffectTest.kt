package app.aaps.pump.combowatch.regulation

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The watch treats a walk or a sport session the way the phone's Activity v2 does. */
class ActivityEffectTest {

    private val now = 1_800_000_000_000L
    private fun minutes(m: Int) = m * 60_000L

    @Test fun `a walk is a fifth, sport a third less insulin need, with the phone's tails`() {
        val walk = ActivityRecord(now, 30, "WALK")
        val sport = ActivityRecord(now, 90, "SPORT")
        assertThat(walk.effectFraction).isWithin(1e-9).of(0.20)
        assertThat(sport.effectFraction).isWithin(1e-9).of(0.30)
        assertThat(walk.tailMinutes).isEqualTo(0)
        assertThat(ActivityRecord(now, 90, "WALK").tailMinutes).isEqualTo(30)
        assertThat(sport.tailMinutes).isEqualTo(180)
        assertThat(ActivityRecord(now, 30, "SPORT").tailMinutes).isEqualTo(60)
    }

    @Test fun `the pump is kept down from an hour before the start to the end, fading with the tail`() {
        val sport = ActivityRecord(now + minutes(30), 50, "SPORT")   // starts in half an hour, 50 min, tail 120
        assertThat(ActivityEffect.newInsulinFactor(sport, now)).isWithin(1e-9).of(0.7)                 // upcoming within 75 min
        assertThat(ActivityEffect.newInsulinFactor(sport, now - minutes(60))).isWithin(1e-9).of(0.8)   // 90 min before: two thirds of the cut already
        assertThat(ActivityEffect.newInsulinFactor(sport, now - minutes(100))).isWithin(1e-9).of(1.0)  // 130 min before: nothing yet
        assertThat(ActivityEffect.newInsulinFactor(sport, now + minutes(60))).isWithin(1e-9).of(0.7)   // during
        assertThat(ActivityEffect.phaseAt(sport, now + minutes(80 + 60))).isWithin(1e-9).of(0.5)        // half-way through the tail
        assertThat(ActivityEffect.newInsulinFactor(sport, now + minutes(80 + 60))).isWithin(1e-9).of(0.85)
        assertThat(ActivityEffect.phaseAt(sport, now + minutes(80 + 121))).isEqualTo(0.0)
    }

    @Test fun `glucose use per five minutes is the phone's insulin equivalent plus the movement, within its caps`() {
        val walk = ActivityRecord(now, 30, "WALK")
        // 1.2 U/h × 50 mg/dL/U × 0.2 / 12 = 1.0, plus 0.8 of movement.
        assertThat(ActivityEffect.glucoseUsePer5m(walk, 1.2, 50.0)).isWithin(1e-9).of(1.8)
        assertThat(ActivityEffect.glucoseUsePer5m(walk, 10.0, 200.0)).isWithin(1e-9).of(3.5)
        assertThat(ActivityEffect.glucoseUsePer5m(ActivityRecord(now, 30, "SPORT"), 10.0, 200.0)).isWithin(1e-9).of(5.0)
    }

    @Test fun `the forecast loses the glucose used, step by step, from the start of the activity`() {
        val walk = ActivityRecord(now + minutes(10), 30, "WALK")
        val flat = List(13) { 120 }   // now and the next hour
        val adjusted = ActivityEffect.adjust(flat, walk, now, 1.2, 50.0)
        assertThat(adjusted[0]).isEqualTo(120)   // now is what the sensor says
        assertThat(adjusted[1]).isEqualTo(120)   // +5 min: not started
        assertThat(adjusted[2]).isEqualTo(118)   // +10: first five minutes of walking, 1.8 off
        assertThat(adjusted[8]).isEqualTo(107)   // +40: end of the walk, 7 × 1.8 = 12.6 off
        assertThat(adjusted[12]).isEqualTo(107)  // no tail for a 30-minute walk
    }

    @Test fun `the activity that counts is the latest one not yet over`() {
        val old = ActivityRecord(now - minutes(300), 30, "WALK")
        val running = ActivityRecord(now - minutes(10), 50, "SPORT")
        val later = ActivityRecord(now + minutes(7 * 60), 30, "WALK")
        assertThat(ActivityEffect.current(listOf(old, running, later), now)).isEqualTo(running)
        assertThat(ActivityEffect.current(listOf(old), now)).isNull()
    }
}
