package app.aaps.pump.combowatch.regulation

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The watch's grams must be the phone's grams: AIMI's «Углеводы по финальному прогнозу» rerun here. */
class CarbsNeededTest {

    // ISF 80 over a carb ratio of 10: 8 mg/dL per gram, the owner's figures rounded.
    private val isf = 80.0
    private val cr = 10.0
    private val target = 117.0

    private fun falling(to: Int, atIndex: Int, length: Int = 49): List<Int> = List(length) { i ->
        if (i <= atIndex) (140 - (140 - to) * i / atIndex.coerceAtLeast(1)) else to + (i - atIndex) * 3
    }

    @Test fun `the phone's own example - lowest 77 at two hours asks five grams within 120 minutes`() {
        val ask = CarbsNeeded.likeThePhone(falling(77, 24), target, isf, cr)
        assertThat(ask).isEqualTo(CarbsNeeded.PhoneLikeAsk(5, 120))
    }

    @Test fun `a deeper dip earlier asks more, sooner`() {
        val ask = CarbsNeeded.likeThePhone(falling(45, 23), target, isf, cr)
        assertThat(ask).isEqualTo(CarbsNeeded.PhoneLikeAsk(9, 115))
    }

    @Test fun `the first twenty minutes and anything past two hours are outside the window`() {
        val early = List(49) { i -> if (i == 2) 50 else 130 }
        assertThat(CarbsNeeded.likeThePhone(early, target, isf, cr)).isEqualTo(CarbsNeeded.PhoneLikeAsk(0, 20))
        val late = List(49) { i -> if (i == 40) 50 else 130 }
        assertThat(CarbsNeeded.likeThePhone(late, target, isf, cr)).isEqualTo(CarbsNeeded.PhoneLikeAsk(0, 20))
        assertThat(CarbsNeeded.likeThePhone(listOf(100, 90, 80), target, isf, cr)).isNull()
    }

    @Test fun `zeros are gaps, not lows, and the buffer lifts the figure`() {
        val withGap = List(49) { i -> if (i == 10) 0 else if (i == 20) 97 else 130 }
        assertThat(CarbsNeeded.likeThePhone(withGap, target, isf, cr)).isEqualTo(CarbsNeeded.PhoneLikeAsk(3, 100))
        assertThat(CarbsNeeded.likeThePhone(withGap, target, isf, cr, bufferMgdl = 10.0)).isEqualTo(CarbsNeeded.PhoneLikeAsk(4, 100))
    }

    @Test fun `the pressure buffer follows the phone's steps`() {
        assertThat(CarbsNeeded.pressureBufferMgdl(0.0, 1.0, nightNoMeal = false)).isEqualTo(0.0)
        assertThat(CarbsNeeded.pressureBufferMgdl(0.8, 1.0, nightNoMeal = false)).isEqualTo(5.0)
        assertThat(CarbsNeeded.pressureBufferMgdl(0.0, 3.0, nightNoMeal = false)).isEqualTo(5.0)
        assertThat(CarbsNeeded.pressureBufferMgdl(1.5, 1.0, nightNoMeal = false)).isEqualTo(10.0)
        assertThat(CarbsNeeded.pressureBufferMgdl(0.0, 1.5, nightNoMeal = true)).isEqualTo(8.0)
    }
}
