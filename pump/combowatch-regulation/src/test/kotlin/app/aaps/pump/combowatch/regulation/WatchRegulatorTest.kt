package app.aaps.pump.combowatch.regulation

import app.aaps.pump.combowatch.protocol.RegulationSnapshot
import app.aaps.pump.combowatch.regulation.WatchRegulator.Action
import app.aaps.pump.combowatch.regulation.WatchRegulator.Rule
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * What the watch decides when it is alone. Every case is a situation a person could be in, with
 * the answer the rules are meant to give; the last group pins down what must never happen.
 */
class WatchRegulatorTest {

    // ---- nothing to do ---------------------------------------------------------------------------

    @Test fun `steady glucose above the target leaves the pump alone`() {
        val decision = Fixture().glucose(130.0).decide()
        assertThat(decision.action).isEqualTo(Action.Leave)
        assertThat(decision.rule).isEqualTo(Rule.ALL_CLEAR)
        assertThat(decision.wantedPercent).isEqualTo(100)
    }

    @Test fun `a slow glide towards the target is not a fast fall`() {
        // 3 mg/dL per five minutes is 36 an hour; the phone counts 65 an hour as fast.
        val decision = Fixture().glucose(135.0, per5 = -3.0).decide()
        assertThat(decision.rule).isEqualTo(Rule.ALL_CLEAR)
        assertThat(decision.action).isEqualTo(Action.Leave)
    }

    // ---- falling fast ----------------------------------------------------------------------------

    @Test fun `falling by eight from 150 stops basal at once, long before any low level`() {
        // 150 - 8 * 6 = 102, under the target of 117.
        val decision = Fixture().glucose(150.0, per5 = -8.0).decide()
        assertThat(decision.rule).isEqualTo(Rule.FAST_FALL)
        assertThat(decision.action).isEqualTo(Action.SetTbr(0, 30))
    }

    @Test fun `a fast fall that will still be above the target in half an hour changes nothing`() {
        // 220 - 8 * 6 = 172.
        val decision = Fixture().glucose(220.0, per5 = -8.0).decide()
        assertThat(decision.action).isEqualTo(Action.Leave)
    }

    @Test fun `a fall that has only just begun counts as fast by its last minutes`() {
        val fixture = Fixture()
        fixture.readings = listOf(
            GlucoseReading(fixture.minutesAgo(0.0), 140.0), GlucoseReading(fixture.minutesAgo(5.0), 148.0),
            GlucoseReading(fixture.minutesAgo(10.0), 155.0), GlucoseReading(fixture.minutesAgo(15.0), 156.0),
            GlucoseReading(fixture.minutesAgo(20.0), 156.0), GlucoseReading(fixture.minutesAgo(30.0), 155.0)
        )
        assertThat(fixture.decide().rule).isEqualTo(Rule.FAST_FALL)
    }

    @Test fun `one missed reading does not hide a fast fall`() {
        val fixture = Fixture()
        fixture.readings = listOf(
            GlucoseReading(fixture.minutesAgo(0.0), 140.0),
            GlucoseReading(fixture.minutesAgo(10.0), 156.0), GlucoseReading(fixture.minutesAgo(15.0), 164.0)
        )
        val decision = fixture.decide()
        assertThat(decision.trend!!.delta).isWithin(0.01).of(-8.0)
        assertThat(decision.rule).isEqualTo(Rule.FAST_FALL)
    }

    // ---- by level --------------------------------------------------------------------------------

    @Test fun `at 60 and below basal stops whatever the trend`() {
        val decision = Fixture().glucose(58.0, per5 = +2.0).decide()
        assertThat(decision.wantedPercent).isEqualTo(0)
        assertThat(decision.action).isEqualTo(Action.SetTbr(0, 30))
    }

    @Test fun `at 75 and below and falling basal stops`() {
        val decision = Fixture().glucose(73.0, per5 = -1.0).decide()
        assertThat(decision.action).isEqualTo(Action.SetTbr(0, 30))
    }

    @Test fun `under 80 basal stays off even when glucose has turned up again`() {
        // The phone would give half back here; the watch waits for 80.
        val decision = Fixture().glucose(72.0, per5 = +3.0).decide()
        assertThat(decision.rule).isEqualTo(Rule.LOW_LEVEL)
        assertThat(decision.action).isEqualTo(Action.SetTbr(0, 30))
    }

    @Test fun `coming out of a low, basal comes back at 80 and not before`() {
        val below = Fixture().glucose(78.0, per5 = +4.0)
        below.tbrs = listOf(TbrSegment(below.minutesAgo(15.0), 0, 30, byWatch = true))
        assertThat(below.decide().action).isEqualTo(Action.Leave)

        val above = Fixture().glucose(82.0, per5 = +4.0)
        above.tbrs = listOf(TbrSegment(above.minutesAgo(16.0), 0, 30, byWatch = true))
        val decision = above.decide()
        assertThat(decision.rule).isEqualTo(Rule.RESUME)
        assertThat(decision.action).isEqualTo(Action.SetTbr(50, 15))
    }

    @Test fun `under 80 basal stops`() {
        val decision = Fixture().glucose(78.0).decide()
        assertThat(decision.rule).isEqualTo(Rule.LOW_LEVEL)
        assertThat(decision.action).isEqualTo(Action.SetTbr(0, 30))
    }

    @Test fun `between 90 and 100 and falling basal is halved`() {
        val decision = Fixture().glucose(96.0, per5 = -1.0).decide()
        assertThat(decision.rule).isEqualTo(Rule.LOW_BAND)
        assertThat(decision.action).isEqualTo(Action.SetTbr(50, 30))
    }

    @Test fun `between 80 and 90 and falling slowly a fifth of the basal stays`() {
        // The phone's rule says a quarter; the pump takes tens, and the watch rounds down.
        val decision = Fixture().glucose(88.0, per5 = -1.0).decide()
        assertThat(decision.rule).isEqualTo(Rule.LOW_BAND)
        assertThat(decision.action).isEqualTo(Action.SetTbr(20, 30))
    }

    @Test fun `between 80 and 90 and falling faster basal stops`() {
        val decision = Fixture().glucose(84.0, per5 = -3.0).decide()
        assertThat(decision.wantedPercent).isEqualTo(0)
    }

    @Test fun `between 90 and 100 and steady nothing is cut`() {
        assertThat(Fixture().glucose(95.0).decide().action).isEqualTo(Action.Leave)
    }

    // ---- by forecast -----------------------------------------------------------------------------

    @Test fun `insulin on board that will take glucose under 80 cuts basal while glucose is still fine`() {
        val fixture = Fixture().glucose(125.0)
        fixture.snapshot = fixture.snapshot(bolusUnits = 1.5, bolusAgeAtSnapshot = 20.0)
        val decision = fixture.decide()
        assertThat(decision.rule).isEqualTo(Rule.FORECAST_LOW)
        assertThat(decision.forecastMinMgdl!!).isLessThan(80)
        // Half an hour without basal is enough here, so basal is cut, not stopped, and nobody is asked to eat.
        assertThat(decision.wantedPercent).isIn(10..90)
        assertThat(decision.action).isEqualTo(Action.SetTbr(decision.wantedPercent, 30))
        assertThat(decision.carbsHintG).isNull()
    }

    @Test fun `the same insulin with a little less of it leaves basal alone`() {
        val fixture = Fixture().glucose(125.0)
        fixture.snapshot = fixture.snapshot(bolusUnits = 1.0, bolusAgeAtSnapshot = 20.0)
        assertThat(fixture.decide().action).isEqualTo(Action.Leave)
    }

    @Test fun `when even no basal cannot hold the forecast up, basal stops and carbohydrates are asked for`() {
        val fixture = Fixture().glucose(95.0)
        fixture.snapshot = fixture.snapshot(bolusUnits = 3.0, bolusAgeAtSnapshot = 120.0)
        val decision = fixture.decide()
        assertThat(decision.action).isEqualTo(Action.SetTbr(0, 30))
        assertThat(decision.carbsHintG).isNotNull()
        assertThat(decision.carbsHintG!!).isIn(5..40)
        assertThat(decision.carbsHintG!! % 5).isEqualTo(0)
    }

    @Test fun `a low that is hours away stops basal but does not yet ask anyone to eat`() {
        val fixture = Fixture().glucose(110.0)
        fixture.snapshot = fixture.snapshot(bolusUnits = 3.0, bolusAgeAtSnapshot = 20.0)
        val decision = fixture.decide()
        assertThat(decision.action).isEqualTo(Action.SetTbr(0, 30))
        assertThat(decision.carbsHintG).isNull()
    }

    @Test fun `a little insulin on board with glucose high above the floor changes nothing`() {
        val fixture = Fixture().glucose(170.0)
        fixture.snapshot = fixture.snapshot(bolusUnits = 0.5)
        assertThat(fixture.decide().action).isEqualTo(Action.Leave)
    }

    @Test fun `a snapshot whose insulin curve has run out is not forecast from, but levels still work`() {
        val old = Fixture().glucose(125.0)
        old.snapshot = old.snapshot(ageMinutes = 5 * 60.0, bolusUnits = 3.0)
        assertThat(old.decide().forecastMinMgdl).isNull()
        assertThat(old.decide().action).isEqualTo(Action.Leave)

        val low = Fixture().glucose(73.0, per5 = -1.0)
        low.snapshot = low.snapshot(ageMinutes = 5 * 60.0)
        assertThat(low.decide().action).isEqualTo(Action.SetTbr(0, 30))
    }

    @Test fun `with no snapshot at all the level rules and the fast fall still work`() {
        val falling = Fixture().glucose(120.0, per5 = -8.0)
        falling.snapshot = null
        // The target is unknown, so 100 is taken: 120 - 48 = 72.
        assertThat(falling.decide().action).isEqualTo(Action.SetTbr(0, 30))
    }

    // ---- what is already running on the pump -----------------------------------------------------

    @Test fun `a stop that already runs with time to spare is not sent again`() {
        val fixture = Fixture().glucose(150.0, per5 = -8.0)
        fixture.tbrs = listOf(TbrSegment(fixture.minutesAgo(8.0), 0, 30, byWatch = true))
        assertThat(fixture.decide().action).isEqualTo(Action.Leave)
    }

    @Test fun `a stop about to run out is renewed while the reason holds`() {
        val fixture = Fixture().glucose(150.0, per5 = -8.0)
        fixture.tbrs = listOf(TbrSegment(fixture.minutesAgo(24.0), 0, 30, byWatch = true))
        assertThat(fixture.decide().action).isEqualTo(Action.SetTbr(0, 30))
    }

    @Test fun `after its own stop the watch gives back half the basal once glucose is rising again`() {
        val fixture = Fixture().glucose(125.0, per5 = +2.0)
        fixture.tbrs = listOf(TbrSegment(fixture.minutesAgo(16.0), 0, 30, byWatch = true))
        val decision = fixture.decide()
        assertThat(decision.rule).isEqualTo(Rule.RESUME)
        assertThat(decision.action).isEqualTo(Action.SetTbr(50, 15))
    }

    @Test fun `under the target and not yet rising, a stop is left to run out`() {
        val fixture = Fixture().glucose(105.0, per5 = 0.0)
        fixture.tbrs = listOf(TbrSegment(fixture.minutesAgo(12.0), 0, 30, byWatch = true))
        assertThat(fixture.decide().action).isEqualTo(Action.Leave)
    }

    // Glucose 95 and falling by 1.5 asks for 50 %; 88 and falling by 1 asks for 20 %.

    @Test fun `a step down of ten per cent waits for the renewal`() {
        val fixture = Fixture().glucose(95.0, per5 = -1.5)
        fixture.tbrs = listOf(TbrSegment(fixture.minutesAgo(10.0), 60, 30, byWatch = true))
        val decision = fixture.decide()
        assertThat(decision.wantedPercent).isEqualTo(50)
        assertThat(decision.action).isEqualTo(Action.Leave)

        // With the 60 % about to run out it is replaced, and by what is wanted now.
        fixture.tbrs = listOf(TbrSegment(fixture.minutesAgo(24.0), 60, 30, byWatch = true))
        assertThat(fixture.decide().action).isEqualTo(Action.SetTbr(50, 30))
    }

    @Test fun `a step down of twenty per cent goes at once`() {
        val fixture = Fixture().glucose(95.0, per5 = -1.5)
        fixture.tbrs = listOf(TbrSegment(fixture.minutesAgo(10.0), 70, 30, byWatch = true))
        assertThat(fixture.decide().action).isEqualTo(Action.SetTbr(50, 30))
    }

    @Test fun `a stop never waits, however small the step`() {
        val fixture = Fixture().glucose(78.0, per5 = -1.0)
        fixture.tbrs = listOf(TbrSegment(fixture.minutesAgo(10.0), 10, 30, byWatch = true))
        assertThat(fixture.decide().action).isEqualTo(Action.SetTbr(0, 30))
    }

    @Test fun `the first reduction never waits either`() {
        val fixture = Fixture().glucose(95.0, per5 = -1.5)
        assertThat(fixture.decide().action).isEqualTo(Action.SetTbr(50, 30))
        // Nor does it when what runs is a temporary basal above profile that the phone left.
        fixture.tbrs = listOf(TbrSegment(fixture.minutesAgo(10.0), 130, 30, byWatch = false))
        assertThat(fixture.decide().action).isEqualTo(Action.SetTbr(50, 30))
    }

    @Test fun `basal is raised only after the pump has been where it is for a quarter of an hour`() {
        val fixture = Fixture().glucose(95.0, per5 = -1.5)
        fixture.tbrs = listOf(TbrSegment(fixture.minutesAgo(11.0), 20, 30, byWatch = true))
        assertThat(fixture.decide().wantedPercent).isEqualTo(50)
        assertThat(fixture.decide().action).isEqualTo(Action.Leave)

        fixture.tbrs = listOf(TbrSegment(fixture.minutesAgo(16.0), 20, 30, byWatch = true))
        assertThat(fixture.decide().action).isEqualTo(Action.SetTbr(50, 30))
    }

    @Test fun `renewing a reduction does not make the pump wait again before basal is raised`() {
        val fixture = Fixture().glucose(95.0, per5 = -1.5)
        fixture.tbrs = listOf(
            TbrSegment(fixture.minutesAgo(29.0), 20, 30, endedEpochMs = fixture.minutesAgo(4.0), byWatch = true),
            TbrSegment(fixture.minutesAgo(4.0), 20, 30, byWatch = true)
        )
        assertThat(fixture.decide().action).isEqualTo(Action.SetTbr(50, 30))
    }

    @Test fun `basal is not raised by less than thirty per cent`() {
        val fixture = Fixture().glucose(95.0, per5 = -1.5)
        fixture.tbrs = listOf(TbrSegment(fixture.minutesAgo(15.0), 30, 30, byWatch = true))
        assertThat(fixture.decide().action).isEqualTo(Action.Leave)
    }

    @Test fun `what is about to run out is replaced by what is wanted, whoever set it and whichever way it goes`() {
        // Left alone, the pump would go from 20 % to full basal in five minutes; 50 % is what the rules ask for.
        val fixture = Fixture().glucose(95.0, per5 = -1.5)
        fixture.tbrs = listOf(TbrSegment(fixture.minutesAgo(25.0), 20, 30, byWatch = false))
        assertThat(fixture.decide().action).isEqualTo(Action.SetTbr(50, 30))
    }

    @Test fun `ninety per cent is not worth waking the pump for when it runs at profile`() {
        val atProfile = emptyList<TbrSegment>()
        fun aboutToRunOut(fixture: Fixture) = listOf(TbrSegment(fixture.minutesAgo(25.0), 50, 30, byWatch = true))
        fun deepWithTimeLeft(fixture: Fixture) = listOf(TbrSegment(fixture.minutesAgo(16.0), 20, 30, byWatch = true))

        // Just enough insulin on board for the forecast to ask for a tenth less basal, whichever of the three runs.
        val fixture = Fixture().glucose(125.0)
        val units = generateSequence(1.0) { it + 0.01 }.take(120).firstOrNull { units ->
            fixture.snapshot = fixture.snapshot(bolusUnits = units)
            listOf(atProfile, aboutToRunOut(fixture), deepWithTimeLeft(fixture)).all { fixture.tbrs = it; fixture.decide().wantedPercent == 90 }
        }
        assertThat(units).isNotNull()

        fixture.tbrs = atProfile
        assertThat(fixture.decide().rule).isEqualTo(Rule.FORECAST_LOW)
        assertThat(fixture.decide().action).isEqualTo(Action.Leave)

        // The same when a reduction is about to run out: the pump goes to profile by itself.
        fixture.tbrs = aboutToRunOut(fixture)
        assertThat(fixture.decide().action).isEqualTo(Action.Leave)

        // But a deep reduction with time left is ended by it.
        fixture.tbrs = deepWithTimeLeft(fixture)
        assertThat(fixture.decide().action).isEqualTo(Action.SetTbr(90, 30))
    }

    @Test fun `when the pump is left as it is, the journal is told which rule held the watch back`() {
        // 4 Oct 12:18: 92 and falling asked for 50 %, the phone's 40 % ran on; the owner saw "LEAVE" and no why.
        val fixture = Fixture().glucose(95.0, per5 = -1.5)
        fixture.tbrs = listOf(TbrSegment(fixture.minutesAgo(8.0), 40, 30, byWatch = false))
        val decision = fixture.decide()
        assertThat(decision.wantedPercent).isEqualTo(50)
        assertThat(decision.action).isEqualTo(Action.Leave)
        assertThat(decision.holdText).contains("40 %")
        assertThat(decision.holdText).contains("от телефона")

        fixture.tbrs = listOf(TbrSegment(fixture.minutesAgo(8.0), 20, 30, byWatch = true))
        assertThat(fixture.decide().holdText).contains("меньше 15 мин")
    }

    @Test fun `a walk entered on the watch keeps basal at eighty per cent, sport at seventy, from an hour before`() {
        val fixture = Fixture().glucose(140.0, per5 = 0.0)
        assertThat(fixture.decide().action).isEqualTo(Action.Leave)
        fixture.activities = listOf(ActivityRecord(fixture.now + 20 * 60_000L, 30, "WALK"))
        val walk = fixture.decide()
        assertThat(walk.rule).isEqualTo(Rule.ACTIVITY)
        assertThat(walk.action).isEqualTo(Action.SetTbr(80, 30))
        assertThat(walk.explanation).contains("прогулка")
        fixture.activities = listOf(ActivityRecord(fixture.now, 50, "SPORT"))
        assertThat(fixture.decide().action).isEqualTo(Action.SetTbr(70, 30))
        // Two hours after a half-hour walk nothing is left of it.
        fixture.activities = listOf(ActivityRecord(fixture.now - 150 * 60_000L, 30, "WALK"))
        assertThat(fixture.decide().action).isEqualTo(Action.Leave)
    }

    @Test fun `a reduction the phone left behind is never raised by the watch`() {
        val fixture = Fixture().glucose(125.0, per5 = +2.0)
        fixture.tbrs = listOf(TbrSegment(fixture.minutesAgo(12.0), 0, 30, byWatch = false))
        assertThat(fixture.decide().action).isEqualTo(Action.Leave)
    }

    @Test fun `a reduction the phone left behind is lowered further when the rules ask for less`() {
        val fixture = Fixture().glucose(150.0, per5 = -8.0)
        fixture.tbrs = listOf(TbrSegment(fixture.minutesAgo(12.0), 50, 30, byWatch = false))
        assertThat(fixture.decide().action).isEqualTo(Action.SetTbr(0, 30))
    }

    @Test fun `a high temporary basal from the phone that the forecast cannot bear is brought down to just under profile`() {
        val fixture = Fixture().glucose(105.0)
        fixture.tbrs = listOf(TbrSegment(fixture.minutesAgo(12.0), 300, 30, byWatch = false))
        fixture.snapshot = fixture.snapshot(
            ageMinutes = 13.0,
            assumedTbr = RegulationSnapshot.AssumedTbr(rateUph = 1.2, endsAtEpochMs = fixture.minutesAgo(13.0))
        )
        // 1.2 U/h * 300 % for the remaining 18 minutes is about 0.7 U above profile at 50 mg/dL per unit.
        val decision = fixture.decide()
        assertThat(decision.action).isEqualTo(Action.SetTbr(90, 15))
    }

    @Test fun `a high temporary basal from the phone that the forecast bears is left to finish`() {
        val fixture = Fixture().glucose(190.0)
        fixture.tbrs = listOf(TbrSegment(fixture.minutesAgo(12.0), 150, 30, byWatch = false))
        assertThat(fixture.decide().action).isEqualTo(Action.Leave)
    }

    @Test fun `an hour at zero with glucose no longer low and no longer falling brings half the basal back`() {
        val fixture = Fixture().glucose(100.0, per5 = +1.0)
        // Still enough insulin in the forecast to ask for zero.
        fixture.snapshot = fixture.snapshot(ageMinutes = 70.0, bolusUnits = 4.0, bolusAgeAtSnapshot = 10.0)
        fixture.tbrs = listOf(
            TbrSegment(fixture.minutesAgo(66.0), 0, 30, byWatch = true),
            TbrSegment(fixture.minutesAgo(36.0), 0, 30, byWatch = true),
            TbrSegment(fixture.minutesAgo(6.0), 0, 30, byWatch = true)
        )
        val decision = fixture.decide()
        assertThat(decision.rule).isEqualTo(Rule.ZERO_LIMIT)
        assertThat(decision.action).isEqualTo(Action.SetTbr(50, 15))
    }

    // ---- when there is nothing to go on ----------------------------------------------------------

    @Test fun `without a reading nothing is done`() {
        val decision = Fixture().decide()
        assertThat(decision.rule).isEqualTo(Rule.NO_GLUCOSE)
        assertThat(decision.action).isEqualTo(Action.Leave)
    }

    @Test fun `an old reading is not acted on, however low`() {
        val fixture = Fixture()
        fixture.readings = listOf(GlucoseReading(fixture.minutesAgo(9.0), 55.0), GlucoseReading(fixture.minutesAgo(14.0), 60.0))
        val decision = fixture.decide()
        assertThat(decision.rule).isEqualTo(Rule.NO_GLUCOSE)
        assertThat(decision.action).isEqualTo(Action.Leave)
    }

    @Test fun `without the pump's basal profile nothing is done`() {
        val fixture = Fixture().glucose(60.0)
        fixture.basal = emptyList()
        assertThat(fixture.decide().rule).isEqualTo(Rule.NO_BASAL_PROFILE)
    }

    @Test fun `a single reading gives no trend, so only the level counts`() {
        val high = Fixture()
        high.readings = listOf(GlucoseReading(high.now, 150.0))
        assertThat(high.decide().action).isEqualTo(Action.Leave)

        val low = Fixture()
        low.readings = listOf(GlucoseReading(low.now, 70.0))
        assertThat(low.decide().action).isEqualTo(Action.SetTbr(0, 30))
    }

    @Test fun `a drop no body can make in five minutes is not taken for a trend`() {
        val fixture = Fixture()
        fixture.readings = listOf(GlucoseReading(fixture.now, 150.0), GlucoseReading(fixture.minutesAgo(5.0), 215.0), GlucoseReading(fixture.minutesAgo(10.0), 216.0))
        val decision = fixture.decide()
        assertThat(decision.trend!!.known).isFalse()
        assertThat(decision.action).isEqualTo(Action.Leave)
    }

    // ---- what must never happen ------------------------------------------------------------------

    @Test fun `whatever the inputs, the watch never asks for more than 90 percent and never for a bolus`() {
        var checked = 0
        for (level in listOf(45.0, 62.0, 74.0, 79.0, 86.0, 95.0, 110.0, 140.0, 200.0, 320.0))
            for (per5 in listOf(-15.0, -8.0, -3.0, -1.0, 0.0, 1.0, 4.0, 12.0))
                for (bolus in listOf(0.0, 1.0, 5.0))
                    for (running in listOf(null, 0, 50, 100 + 50))
                        for (own in listOf(true, false)) {
                            val fixture = Fixture().glucose(level, per5)
                            fixture.snapshot = fixture.snapshot(bolusUnits = bolus)
                            if (running != null) fixture.tbrs = listOf(TbrSegment(fixture.minutesAgo(11.0), running, 30, byWatch = own))
                            val decision = fixture.decide()
                            assertThat(decision.wantedPercent).isIn(0..100)
                            when (val action = decision.action) {
                                is Action.SetTbr -> {
                                    assertThat(action.percent).isIn(0..90)
                                    assertThat(action.percent % 10).isEqualTo(0)
                                    assertThat(action.durationMinutes).isIn(listOf(15, 30))
                                }

                                Action.Leave     -> Unit
                            }
                            checked++
                        }
        assertThat(checked).isEqualTo(10 * 8 * 3 * 4 * 2)
    }

    @Test fun `rising glucose is never a reason to send anything`() {
        for (level in listOf(110.0, 150.0, 250.0)) for (per5 in listOf(1.0, 5.0, 12.0)) {
            val decision = Fixture().glucose(level, per5).decide()
            assertThat(decision.action).isEqualTo(Action.Leave)
        }
    }
}
