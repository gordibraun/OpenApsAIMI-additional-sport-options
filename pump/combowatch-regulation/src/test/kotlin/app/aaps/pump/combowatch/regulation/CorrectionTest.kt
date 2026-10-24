package app.aaps.pump.combowatch.regulation

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The watch adding insulin on its own: the phone's arithmetic on the watch's forecast, under the
 * watch's caps. The forecast engine is the phone's; these check what the watch makes of it.
 */
class CorrectionTest {

    /** A meal entered on the watch a few minutes ago, glucose on its way up, nothing much on board. */
    private fun meal(bg: Double = 140.0, per5: Double = 6.0, cobG: Double = 40.0) = Fixture().apply {
        glucose(bg, per5)
        snapshot = snapshot(cob = cobG)
    }

    private fun Fixture.inputs(
        hour: Int = 12,
        carbs: List<CarbsRecord> = listOf(CarbsRecord(minutesAgo(10.0), 40)),
        activities: List<ActivityRecord> = emptyList(),
        smbGivenLastHourU: Double = 0.0,
        boluses: List<BolusRecord> = this.boluses
    ) = WatchRegulator.Inputs(
        nowEpochMs = now, readings = readings, snapshot = snapshot, pumpBasalUph = basal,
        delivery = DeliveryLog(tbrs, boluses, carbs, activities), hourOfDay = { hour }, smbGivenLastHourU = smbGivenLastHourU
    )

    @Test fun `a meal under way with the forecast above the target gets a microbolus and basal above profile`() {
        val d = WatchRegulator().decide(meal().inputs())
        assertThat(d.rule).isEqualTo(WatchRegulator.Rule.CORRECTION)
        assertThat(d.insulinReqU).isGreaterThan(0.0)
        assertThat(d.smbTenthsIU).isIn(1..5)
        assertThat(d.wantedPercent).isIn(110..150)
        assertThat(d.action).isEqualTo(WatchRegulator.Action.SetTbr(d.wantedPercent, WatchRegulator.REDUCTION_MINUTES))
        assertThat(d.explanation).contains("микродоза")
    }

    @Test fun `at night the same meal gets basal above profile but no microbolus`() {
        val d = WatchRegulator().decide(meal().inputs(hour = 2))
        assertThat(d.rule).isEqualTo(WatchRegulator.Rule.CORRECTION)
        assertThat(d.smbTenthsIU).isEqualTo(0)
        assertThat(d.wantedPercent).isGreaterThan(100)
    }

    @Test fun `the hourly cap on microboluses holds while basal still goes up`() {
        val d = WatchRegulator().decide(meal().inputs(smbGivenLastHourU = WatchRegulator.MAX_SMB_PER_HOUR_U))
        assertThat(d.smbTenthsIU).isEqualTo(0)
        assertThat(d.wantedPercent).isGreaterThan(100)
    }

    @Test fun `a bolus given minutes ago postpones the microbolus`() {
        val f = meal()
        val d = WatchRegulator().decide(f.inputs(boluses = listOf(BolusRecord(f.minutesAgo(2.0), 0.3))))
        assertThat(d.smbTenthsIU).isEqualTo(0)
    }

    @Test fun `without a meal, high and rising glucose gets a microbolus, high and steady only basal`() {
        val rising = WatchRegulator().decide(Fixture().apply { glucose(170.0, 5.0) }.inputs(carbs = emptyList()))
        assertThat(rising.rule).isEqualTo(WatchRegulator.Rule.CORRECTION)
        assertThat(rising.smbTenthsIU).isGreaterThan(0)
        val steady = WatchRegulator().decide(Fixture().apply { glucose(170.0, 0.0) }.inputs(carbs = emptyList()))
        assertThat(steady.smbTenthsIU).isEqualTo(0)
        assertThat(steady.wantedPercent).isGreaterThan(100)
    }

    @Test fun `a fast fall gets no correction`() {
        val d = WatchRegulator().decide(meal(bg = 180.0, per5 = -10.0).inputs())
        assertThat(d.smbTenthsIU).isEqualTo(0)
        assertThat(d.wantedPercent).isAtMost(100)
    }

    @Test fun `a walk under way holds basal back and adds nothing`() {
        val f = meal()
        val d = WatchRegulator().decide(f.inputs(activities = listOf(ActivityRecord(f.minutesAgo(10.0), 30, "WALK"))))
        assertThat(d.smbTenthsIU).isEqualTo(0)
        assertThat(d.wantedPercent).isAtMost(80)
    }

    @Test fun `insulin on board up to the cap leaves nothing to add`() {
        val f = Fixture().apply {
            glucose(150.0, 3.0)
            snapshot = snapshot(cob = 40.0, bolusUnits = 7.0, bolusAgeAtSnapshot = 5.0)
        }
        val d = WatchRegulator().decide(f.inputs())
        assertThat(d.smbTenthsIU).isEqualTo(0)
        assertThat(d.wantedPercent).isAtMost(100)
    }

    @Test fun `the owner's resistance mode widens the caps, never past the ceilings`() {
        val f = Fixture().apply { glucose(250.0, 5.0) }
        fun with(factor: Double) = WatchRegulator().decide(
            WatchRegulator.Inputs(
                nowEpochMs = f.now, readings = f.readings, snapshot = f.snapshot, pumpBasalUph = f.basal,
                delivery = DeliveryLog(), hourOfDay = { 12 }, resistanceFactor = factor
            )
        )
        val plain = with(1.0)
        assertThat(plain.wantedPercent).isEqualTo(WatchRegulator.MAX_RAISE_PERCENT)
        assertThat(plain.smbTenthsIU).isEqualTo(5)
        val resistant = with(1.3)
        assertThat(resistant.wantedPercent).isEqualTo(190)
        assertThat(resistant.smbTenthsIU).isEqualTo(6)
        assertThat(resistant.explanation).contains("сопротивление +30 %")
        // The factor is clamped: nothing beyond the ceilings however large it is.
        val absurd = with(5.0)
        assertThat(absurd.wantedPercent).isAtMost(WatchRegulator.MAX_RAISE_RESISTANT_PERCENT)
        assertThat(absurd.smbTenthsIU).isAtMost((WatchRegulator.MAX_SMB_RESISTANT_U * 10).toInt())
    }

    @Test fun `a planned microbolus lowers the forecast the plan is checked against`() {
        val f = meal()
        val forecast = WatchForecast(f.snapshot!!, GlucoseTrend.from(f.readings, f.now)!!, DeliveryLog(), { 1.2 }, f.now)
        assertThat(forecast.series(1.2, 30, 0.5).last()).isLessThan(forecast.series(1.2, 30, 0.0).last())
    }
}
