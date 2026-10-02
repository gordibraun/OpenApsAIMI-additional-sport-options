package app.aaps.pump.combowatch.regulation

import app.aaps.pump.combowatch.regulation.WatchRegulator.Action
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

/**
 * Whole stretches of time without the phone, reading by reading, with the pump doing what the
 * watch tells it. What matters over a stretch is not one decision but the sequence: that basal is
 * stopped early, comes back, and that the pump is not woken for nothing.
 */
class AfternoonAloneTest {

    private class Step(val minute: Int, val glucose: Double, val decision: WatchRegulator.Decision, val percentAfter: Int)

    /** Run [glucose] (one value per five minutes) past the regulator, applying every command to the pump record. */
    private fun live(glucose: List<Double>, bolusUnits: Double = 0.0, print: String? = null): List<Step> {
        val start = 1_800_000_000_000L
        val base = Fixture(start)
        val snapshot = base.snapshot(ageMinutes = 10.0, bolusUnits = bolusUnits)
        val readings = mutableListOf<GlucoseReading>()
        // Half an hour of history before the phone went quiet, at the first value.
        for (back in 6 downTo 1) readings += GlucoseReading(start - back * 300_000L, glucose.first())
        var tbrs = listOf<TbrSegment>()
        val steps = mutableListOf<Step>()
        glucose.forEachIndexed { index, value ->
            val now = start + index * 300_000L
            readings += GlucoseReading(now, value)
            val decision = WatchRegulator().decide(
                WatchRegulator.Inputs(now, readings.toList(), snapshot, base.basal, DeliveryLog(tbrs), hourOfDay = { 12 })
            )
            (decision.action as? Action.SetTbr)?.let { command ->
                tbrs = tbrs.map { if (it.runsAt(now)) it.copy(endedEpochMs = now) else it } +
                    TbrSegment(now, command.percent, command.durationMinutes, byWatch = true)
            }
            steps += Step(index * 5, value, decision, DeliveryLog(tbrs).percentAt(now + 1_000))
        }
        if (print != null) {
            println("---- $print")
            steps.forEach {
                val what = (it.decision.action as? Action.SetTbr)?.let { c -> "-> TBR ${c.percent} % ${c.durationMinutes} мин" } ?: ""
                println("%4d мин  сахар %3.0f  базал %3d %%  %-13s %s".format(it.minute, it.glucose, it.percentAfter, it.decision.rule, what))
            }
        }
        return steps
    }

    private fun commands(steps: List<Step>) = steps.mapNotNull { step -> (step.decision.action as? Action.SetTbr)?.let { step to it } }

    /**
     * Basal that was lowered is not given back at the next reading or the one after: it stays
     * where it was put for a quarter of an hour. The other way round there is no such rule -
     * basal given back can be taken away again at once.
     */
    private fun assertNothingGivenBackInHaste(steps: List<Step>) {
        var loweredAt: Int? = null
        var before = 100
        for (step in steps) {
            if (step.percentAfter > before && step.decision.action is Action.SetTbr)
                loweredAt?.let { assertWithMessage("lowered at $it min, raised at ${step.minute} min").that(step.minute - it).isAtLeast(15) }
            if (step.percentAfter < before) loweredAt = step.minute
            before = step.percentAfter
        }
    }

    private fun ramp(from: Double, to: Double, minutes: Int): List<Double> {
        val count = minutes / 5
        return List(count) { from + (to - from) * (it + 1) / count }
    }

    @Test fun `a quiet afternoon sends the pump nothing`() {
        val steps = live(List(36) { 112.0 + (it % 3) }, print = "ровный сахар 3 часа")
        assertThat(commands(steps)).isEmpty()
    }

    @Test fun `a fast fall is stopped well above any low level, and basal comes back as glucose recovers`() {
        val glucose = List(4) { 185.0 } + ramp(185.0, 72.0, 60) + ramp(72.0, 70.0, 15) + ramp(70.0, 115.0, 60) + List(8) { 115.0 }
        val steps = live(glucose, print = "быстрое падение с 185 до 70 и восстановление")
        val sent = commands(steps)

        // The first command is a stop, and it comes while glucose is still far from low.
        val (firstStep, first) = sent.first()
        assertThat(first).isEqualTo(Action.SetTbr(0, 30))
        assertThat(firstStep.glucose).isGreaterThan(130.0)

        // Basal stays off through the whole fall and the low.
        val lowest = steps.minByOrNull { it.glucose }!!
        for (step in steps.filter { it.minute in firstStep.minute..lowest.minute }) assertThat(step.percentAfter).isEqualTo(0)

        // Under 80 there is no basal at all, rising or not.
        for (step in steps.filter { it.minute > firstStep.minute && it.glucose < 80.0 }) assertThat(step.percentAfter).isEqualTo(0)

        // It comes back once glucose is over 80 and rising, half of it first, and ends at profile.
        val (backStep, back) = sent.first { it.second.percent > 0 }
        assertThat(back).isEqualTo(Action.SetTbr(50, 15))
        assertThat(backStep.glucose).isAtLeast(80.0)
        assertThat(steps.last().percentAfter).isEqualTo(100)

        // And the pump was not woken at every reading: a stop, its renewals every 25 minutes, one command to come back.
        assertThat(sent.size).isAtMost(6)
        for ((_, command) in sent) assertThat(command.percent).isAtMost(90)
        assertNothingGivenBackInHaste(steps)
    }

    @Test fun `a slow descent into the low nineties is met with graded reductions, not a stop`() {
        val glucose = List(3) { 128.0 } + ramp(128.0, 92.0, 120) + List(12) { 93.0 }
        val steps = live(glucose, print = "медленное снижение со 128 до 92")
        val sent = commands(steps)
        assertThat(sent).isNotEmpty()
        assertThat(sent.none { it.second.percent == 0 }).isTrue()
        assertThat(sent.first().first.glucose).isAtMost(100.0)
        assertThat(steps.last().percentAfter).isEqualTo(100)
        assertThat(sent.size).isAtMost(3)
        assertNothingGivenBackInHaste(steps)
    }

    @Test fun `insulin on board with steady glucose cuts basal ahead of the fall it predicts`() {
        val glucose = List(6) { 125.0 } + ramp(125.0, 95.0, 90) + List(6) { 95.0 }
        val steps = live(glucose, bolusUnits = 1.8, print = "ровный сахар 125, но 1.8 ЕД на борту")
        val sent = commands(steps)
        // Cut at the very first reading, on the forecast alone, with glucose still at 125.
        assertThat(sent.first().first.minute).isEqualTo(0)
        assertThat(sent.first().first.decision.rule).isEqualTo(WatchRegulator.Rule.FORECAST_LOW)
        // The forecast changes a little with every reading; the pump is not told about every little.
        // Two and a half hours of it take a command every twenty minutes or so, each a real step or a renewal.
        assertThat(sent.size).isAtMost(8)
        assertNothingGivenBackInHaste(steps)
        for ((_, command) in sent) assertThat(command.percent).isAtMost(90)
    }
}
