package app.aaps.pump.combowatch.regulation

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DeliveryLogTest {

    private val now = 1_800_000_000_000L
    private fun ago(minutes: Int) = now - minutes * 60_000L

    @Test fun `with nothing recorded the pump runs at profile`() {
        assertThat(DeliveryLog().percentAt(now)).isEqualTo(100)
        assertThat(DeliveryLog().zeroMinutesUpTo(now)).isEqualTo(0)
    }

    @Test fun `a temporary basal counts from its start to its end, or to when it was cut short`() {
        val log = DeliveryLog(listOf(TbrSegment(ago(40), 50, 30), TbrSegment(ago(20), 0, 30, endedEpochMs = ago(5))))
        assertThat(log.percentAt(ago(41))).isEqualTo(100)
        assertThat(log.percentAt(ago(30))).isEqualTo(50)
        // The later one replaced the earlier, whatever the earlier one's planned end.
        assertThat(log.percentAt(ago(15))).isEqualTo(0)
        assertThat(log.percentAt(ago(4))).isEqualTo(100)
    }

    @Test fun `stops renewed one after another count as one stop`() {
        val log = DeliveryLog(listOf(
            TbrSegment(ago(100), 50, 30),
            TbrSegment(ago(70), 0, 30, endedEpochMs = ago(41)),
            TbrSegment(ago(40), 0, 30, endedEpochMs = ago(11)),
            TbrSegment(ago(10), 0, 30)
        ))
        assertThat(log.zeroMinutesUpTo(now)).isEqualTo(70)
    }

    @Test fun `a stop that ended a while ago is over`() {
        val log = DeliveryLog(listOf(TbrSegment(ago(60), 0, 30)))
        assertThat(log.zeroMinutesUpTo(now)).isEqualTo(0)
    }

    @Test fun `renewing at the same percentage does not restart the count of how long it has been held`() {
        val log = DeliveryLog(listOf(
            TbrSegment(ago(80), 0, 30, endedEpochMs = ago(51)),
            TbrSegment(ago(50), 40, 30, endedEpochMs = ago(26)),
            TbrSegment(ago(25), 40, 30)
        ))
        assertThat(log.heldMinutesUpTo(now)).isEqualTo(50)
        assertThat(log.zeroMinutesUpTo(now)).isEqualTo(0)
        // Back when the stop was running it had been held since its own start.
        assertThat(log.heldMinutesUpTo(ago(60))).isEqualTo(20)
    }

    @Test fun `nothing is held when no temporary basal runs`() {
        assertThat(DeliveryLog(listOf(TbrSegment(ago(60), 40, 30))).heldMinutesUpTo(now)).isEqualTo(0)
    }
}
