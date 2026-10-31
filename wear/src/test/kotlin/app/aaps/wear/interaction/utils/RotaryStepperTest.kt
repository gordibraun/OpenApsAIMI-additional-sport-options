package app.aaps.wear.interaction.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RotaryStepperTest {

    private val unit = 64f

    @Test fun smallMotionsAddUpToOneStepPerDetent() {
        val stepper = RotaryStepper(unit)
        var t = 1_000L
        var total = 0
        repeat(10) { total += stepper.onScroll(unit / 5, t); t += 20 }
        assertEquals(2, total)
    }

    @Test fun oneEventPerDetentStillGivesOneStep() {
        val stepper = RotaryStepper(unit)
        assertEquals(1, stepper.onScroll(unit, 1_000L))
        assertEquals(-1, stepper.onScroll(-unit, 1_050L))
    }

    @Test fun acceleratesOnlyAfterALongTurn() {
        val stepper = RotaryStepper(unit)
        var t = 1_000L
        var total = 0
        repeat(RotaryStepper.SLOW_STEPS) { total += stepper.onScroll(unit, t); t += 30 }
        assertEquals(RotaryStepper.SLOW_STEPS, total)
        assertEquals(2, stepper.onScroll(unit, t)); t += 30
        repeat(RotaryStepper.MEDIUM_STEPS - RotaryStepper.SLOW_STEPS - 1) { stepper.onScroll(unit, t); t += 30 }
        assertEquals(5, stepper.onScroll(unit, t))
    }

    @Test fun aPauseOrAReversalReturnsToTheSlowPace() {
        val stepper = RotaryStepper(unit)
        var t = 1_000L
        repeat(RotaryStepper.SLOW_STEPS + 3) { stepper.onScroll(unit, t); t += 30 }
        assertEquals(2, stepper.onScroll(unit, t)); t += 30
        assertEquals(-1, stepper.onScroll(-unit, t)); t += 30
        repeat(RotaryStepper.SLOW_STEPS + 3) { stepper.onScroll(unit, t); t += 30 }
        t += RotaryStepper.IDLE_RESET_MS + 1
        assertEquals(1, stepper.onScroll(unit, t))
    }

    @Test fun partialTravelIsForgottenAfterAPause() {
        val stepper = RotaryStepper(unit)
        assertEquals(0, stepper.onScroll(unit * 0.9f, 1_000L))
        assertEquals(0, stepper.onScroll(unit * 0.9f, 1_000L + RotaryStepper.IDLE_RESET_MS + 1))
        assertEquals(1, stepper.onScroll(unit * 0.1f, 1_000L + RotaryStepper.IDLE_RESET_MS + 20))
    }
}
