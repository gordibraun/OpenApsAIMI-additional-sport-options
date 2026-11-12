package app.aaps.core.objects.wizard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EarlierMealInsulinTest {

    private fun credit(
        newCarbs: Int = 15,
        handled: Double = 3.0,
        iob: Double = 3.0,
        cobInsulin: Double = 0.0,
        other: Double = 0.0,
        bg: Double = 120.0,
        delta: Double = 0.0,
        activity: Double = 1.0
    ) = EarlierMealInsulin.credit(newCarbs, handled, iob, cobInsulin, other, bg, delta, activity)

    @Test
    fun `a second helping right after a meal bolus keeps its whole dose`() {
        // 30 g covered with 3 U a minute ago: the 3 U on board belong to those 30 g.
        assertEquals(3.0, credit(), 1e-9)
    }

    @Test
    fun `insulin beyond what the earlier meal needs is still held against the new carbs`() {
        // 4.5 U on board, the earlier meal's carbs need 3 U: 1.5 U stay against the new carbs.
        assertEquals(3.0, credit(iob = 4.5), 1e-9)
    }

    @Test
    fun `an earlier meal never adds insulin of its own`() {
        // Its carbs need 3 U but only 1.2 U are on board: the credit stops at what is on board.
        assertEquals(1.2, credit(iob = 1.2), 1e-9)
    }

    @Test
    fun `the credit never buys a correction on top of the new carbs`() {
        // Sugar above target is worth +0.7 U: the hole insulin on board makes is 3.0 - 0.7.
        assertEquals(2.3, credit(other = 0.7), 1e-9)
    }

    @Test
    fun `a sugar below target keeps its own reduction`() {
        // Sugar below target is worth -0.4 U: the credit covers the insulin on board, not that.
        assertEquals(3.0, credit(other = -0.4), 1e-9)
    }

    @Test
    fun `carbs the wizard still doses have the first claim on insulin on board`() {
        // 1 U goes to carbs on board the wizard adds insulin for anyway; 2 U are left for the earlier meal.
        assertEquals(2.0, credit(cobInsulin = 1.0), 1e-9)
        assertEquals(0.0, credit(cobInsulin = 3.5), 1e-9)
    }

    @Test
    fun `no credit when the sugar is low`() {
        assertEquals(0.0, credit(bg = 79.0), 1e-9)
        assertEquals(3.0, credit(bg = 80.0), 1e-9)
    }

    @Test
    fun `no credit when the present fall reaches the low within a quarter of an hour`() {
        // 11.11.2026 21:41: 103 falling 11.5 per reading - the insulin on board is winning.
        assertEquals(0.0, credit(bg = 103.0, delta = -11.5), 1e-9)
        // 99 falling 5.5 stays above 80: the credit holds.
        assertEquals(3.0, credit(bg = 99.0, delta = -5.5), 1e-9)
        // A rise is never counted as a reason for more.
        assertEquals(0.0, credit(bg = 78.0, delta = +9.0), 1e-9)
    }

    @Test
    fun `an activity that trims new insulin trims what the earlier meal needs`() {
        assertEquals(2.4, credit(activity = 0.8), 1e-9)
    }

    @Test
    fun `nothing without new carbs, covered carbs, glucose or insulin on board`() {
        assertEquals(0.0, credit(newCarbs = 0), 1e-9)
        assertEquals(0.0, credit(handled = 0.0), 1e-9)
        assertEquals(0.0, credit(bg = 0.0), 1e-9)
        assertEquals(0.0, credit(iob = 0.0), 1e-9)
        assertEquals(0.0, credit(iob = -0.6), 1e-9)
        assertEquals(0.0, credit(handled = Double.NaN), 1e-9)
        assertEquals(0.0, credit(iob = Double.NaN), 1e-9)
    }

    @Test
    fun `an unknown trend counts as flat`() {
        assertEquals(3.0, credit(delta = Double.NaN), 1e-9)
    }
}
