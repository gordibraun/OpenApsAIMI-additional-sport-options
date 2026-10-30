package app.aaps.plugins.aps.openAPSAIMI.activity

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.objects.activity.ActivityBolusContextCalculator
import app.aaps.core.objects.activity.ActivityPlanCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The loop's activity context, the wizard's factor and the shared tail rule say the same thing at
 * every hour of a session and its tail. Prints the timeline the owner asked to see (30.10.2026).
 */
class ActivityManagerTailTest {

    private val start = 1_800_000_000_000L
    private fun minutes(m: Int) = m * 60_000L

    private fun session(mode: String, duration: Int): TE = TE(
        timestamp = start, type = TE.Type.EXERCISE, duration = minutes(duration), glucoseUnit = GlucoseUnit.MGDL,
        note = ActivityPlanCalculator.note(mode, ActivityPlanCalculator.effectPercent(mode), 0, duration, ActivityPlanCalculator.tailMinutes(mode, duration), 0, "fast")
    )

    private fun context(event: TE, at: Long) = ActivityManager().process(
        steps5min = 0, steps10min = 0, avgHr = 60.0, avgHrResting = 60.0, therapyEvents = listOf(event), now = at,
        profileBasalUph = 1.0, profileIsfMgdl = 45.0
    )

    @Test
    fun `sport of fifty minutes - the loop, the wizard and the rule agree through an eight hour tail`() {
        val event = session("SPORT", 50)
        val tail = ActivityPlanCalculator.tailMinutes("SPORT", 50)
        assertEquals(480, tail)
        println("SPORT 50 min: minute | phase | ISF x | basal/new insulin x | wizard x | SMB capped | glucose-use tail left")
        for (minute in listOf(-70, -30, 0, 25, 50, 80, 110, 170, 230, 290, 350, 410, 470, 530, 560)) {
            val at = start + minutes(minute)
            val ctx = context(event, at)
            val wizard = ActivityBolusContextCalculator.calculate(listOf(event), at)?.factor ?: 1.0
            val expectedPhase = when {
                minute < 0    -> 0.0
                minute <= 50  -> 1.0
                else          -> ActivityPlanCalculator.tailPhase("SPORT", tail, (minute - 50).toDouble())
            }
            println("  %4d | %.2f | %.2f | %.2f | %.2f | %s | %d".format(minute, ctx.currentPhase, ctx.isfMultiplier, ctx.newInsulinFactor, wizard, ctx.protectionMode, ctx.useTailRemainingMinutes))
            assertEquals(expectedPhase, ctx.currentPhase, 1e-9, "phase at $minute")
            assertEquals(1.0 + 0.30 * expectedPhase, ctx.isfMultiplier, 1e-9, "ISF at $minute")
            if (minute in 0..530) assertEquals(1.0 - 0.30 * (if (minute < 0) 1.0 else expectedPhase), ctx.newInsulinFactor, 1e-9, "new insulin at $minute")
            if (minute in 0..529) assertEquals(ctx.newInsulinFactor, wizard, 1e-9, "wizard at $minute")
            assertEquals(expectedPhase > 0.0, ctx.protectionMode, "protection at $minute")
            // Activity *state* (shorter DIA, basal kept off zero) only while the session runs; the tail is recovery.
            assertEquals(if (minute in 0..50) ActivityState.MODERATE else ActivityState.REST, ctx.state, "state at $minute")
            assertEquals(minute > 50 && expectedPhase > 0.0, ctx.isRecovery, "recovery at $minute")
            // The movement's glucose use ends two hours after the session, long before the insulin tail does.
            if (minute > 50 + 120) assertEquals(0, ctx.useTailRemainingMinutes, "glucose use at $minute")
            if (minute in 51..169) assertTrue(ctx.useTailRemainingMinutes > 0, "glucose use still on at $minute")
        }
        // Before the start the hour-before rule holds the new insulin down without any tail phase.
        assertEquals(0.7, context(event, start - minutes(30)).newInsulinFactor, 1e-9)
        assertEquals(0.0, context(event, start - minutes(30)).currentPhase, 1e-9)
        // Night after sport is the loop's own switch; here only that recovery is flagged through the whole tail.
        assertTrue(context(event, start + minutes(400)).isRecovery)
        assertFalse(context(event, start + minutes(540)).isRecovery)
    }

    @Test
    fun `a half-hour walk - two hours of tail, half an hour of it held, glucose use only for the walk itself`() {
        val event = session("WALK", 30)
        assertEquals(120, ActivityPlanCalculator.tailMinutes("WALK", 30))
        println("WALK 30 min: minute | phase | ISF x | basal/new insulin x | wizard x")
        for (minute in listOf(0, 15, 30, 45, 60, 75, 105, 135, 150, 160)) {
            val at = start + minutes(minute)
            val ctx = context(event, at)
            val wizard = ActivityBolusContextCalculator.calculate(listOf(event), at)?.factor ?: 1.0
            val expectedPhase = if (minute <= 30) 1.0 else ActivityPlanCalculator.tailPhase("WALK", 120, (minute - 30).toDouble())
            println("  %4d | %.2f | %.2f | %.2f | %.2f".format(minute, ctx.currentPhase, ctx.isfMultiplier, ctx.newInsulinFactor, wizard))
            assertEquals(expectedPhase, ctx.currentPhase, 1e-9, "phase at $minute")
            assertEquals(1.0 - 0.20 * expectedPhase, ctx.newInsulinFactor, 1e-9, "new insulin at $minute")
            if (minute < 150) assertEquals(ctx.newInsulinFactor, wizard, 1e-9, "wizard at $minute")
            assertEquals(0, ctx.useTailRemainingMinutes.takeIf { minute > 30 } ?: 0, "no glucose-use tail for a short walk at $minute")
        }
    }
}
