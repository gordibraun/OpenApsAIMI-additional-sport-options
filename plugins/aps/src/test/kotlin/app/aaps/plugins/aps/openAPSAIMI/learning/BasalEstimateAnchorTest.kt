package app.aaps.plugins.aps.openAPSAIMI.learning

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The owner's numbers of 16.11.2026: daily dose 50.4 U, weight 92 kg, so 0.548 U/h from dose over weight; the
 * learner frozen at x1.99 since 28.10; the profile's basal 26.47 U a day (mean 1.103 U/h), and 24.63 U a day
 * (1.026 U/h) with the night and morning hours lowered.
 */
class BasalEstimateAnchorTest {

    private val fromDose = 50.4 / 92.0
    private val frozen = 1.99
    private val lastLearned = 1_793_163_111_290L // 28.10.2026 07:51
    private val now = 1_794_826_800_000L         // 16.11.2026 14:00

    private fun decide(anchor: Boolean = true, profileMean: Double? = 26.47 / 24, dose: Double = fromDose) =
        BasalEstimateAnchor.decide(anchor, dose, profileMean, frozen, lastLearned, now)

    @Test
    fun `with the profile as it was the figure stays where the frozen learner had it`() {
        val decision = decide()
        assertThat(decision.anchored).isTrue()
        assertThat(fromDose * decision.multiplier).isWithin(0.001).of(1.103)
        // The frozen number gave 1.09: nothing jumps on the day the anchor goes in.
        assertThat(fromDose * decision.multiplier).isWithin(0.02).of(fromDose * frozen)
    }

    @Test
    fun `when the profile's basal is lowered the figure follows it`() {
        val decision = decide(profileMean = 24.63 / 24)
        assertThat(fromDose * decision.multiplier).isWithin(0.001).of(1.026)
        assertThat(decision.multiplier).isLessThan(frozen)
    }

    @Test
    fun `switched off the learner's number is used exactly as before`() {
        val decision = decide(anchor = false)
        assertThat(decision.anchored).isFalse()
        assertThat(decision.multiplier).isEqualTo(frozen)
        assertThat(decision.line).startsWith("Basal adjusted by learner (x1.99)")
    }

    @Test
    fun `without a profile to hold to the learner's number is used and the line says why`() {
        val decision = decide(profileMean = null)
        assertThat(decision.anchored).isFalse()
        assertThat(decision.multiplier).isEqualTo(frozen)
        assertThat(decision.line).contains("профиль неизвестен")
    }

    @Test
    fun `the multiplier keeps to the bounds the learner's own had`() {
        assertThat(decide(profileMean = 5.0).multiplier).isEqualTo(BasalEstimateAnchor.MAX_MULTIPLIER)
        assertThat(decide(profileMean = 0.2).multiplier).isEqualTo(BasalEstimateAnchor.MIN_MULTIPLIER)
    }

    @Test
    fun `the line tells the figure, where it comes from and how long the learner has been idle`() {
        val line = decide(profileMean = 24.63 / 24).line
        assertThat(line).contains("1.03 ЕД/ч")
        assertThat(line).contains("не учился 19 дн.")
        assertThat(line).contains("не используется")
    }
}
