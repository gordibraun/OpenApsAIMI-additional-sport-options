package app.aaps.wear.interaction.utils

import kotlin.math.abs

/**
 * Turns the crown's stream of scroll events into amount steps. The watch may split one detent of
 * the crown into many small motion events; here they are added up, and one step is taken per
 * [unitPx] of travel (one detent on a standard crown), so the amount cannot run away under the
 * finger. A larger step comes only after a long uninterrupted turn in one direction: two per
 * detent after [SLOW_STEPS], five after [MEDIUM_STEPS]. A pause or a reversal starts the slow pace again.
 */
class RotaryStepper(private val unitPx: Float, private val idleResetMs: Long = IDLE_RESET_MS) {

    private var travel = 0f
    private var run = 0
    private var lastSign = 0
    private var lastAt = Long.MIN_VALUE / 2

    /** The signed amount change for this motion; 0 while the crown has not yet turned a full unit. */
    fun onScroll(deltaPx: Float, now: Long): Int {
        if (unitPx <= 0f || deltaPx.isNaN()) return 0
        if (now - lastAt > idleResetMs) {
            travel = 0f
            run = 0
        }
        lastAt = now
        travel += deltaPx
        var steps = 0
        while (abs(travel) >= unitPx) {
            val sign = if (travel > 0f) 1 else -1
            if (sign != lastSign) {
                run = 0
                lastSign = sign
            }
            travel -= sign * unitPx
            steps += sign * stepSize(run)
            run++
        }
        return steps
    }

    private fun stepSize(run: Int): Int = when {
        run < SLOW_STEPS   -> 1
        run < MEDIUM_STEPS -> 2
        else               -> 5
    }

    companion object {

        const val IDLE_RESET_MS = 400L
        const val SLOW_STEPS = 15
        const val MEDIUM_STEPS = 40
    }
}
