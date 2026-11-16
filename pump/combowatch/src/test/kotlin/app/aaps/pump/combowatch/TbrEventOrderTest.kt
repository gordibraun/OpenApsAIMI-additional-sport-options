package app.aaps.pump.combowatch

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Which "ended" event may still end a temporary basal, once the pump's events can reach the phone twice.
 * The times are those of 16.11.2026: the old basal reported as ended at 07:24:50, the 340 % one as started
 * at 07:24:24 - in that order, numbers 17369 and 17370.
 */
class TbrEventOrderTest {

    private var kept: Pair<Long, Long>? = null
    private fun order() = TbrEventOrder(load = { kept }, save = { seq, at -> kept = seq to at })

    private val started340 = 1_794_803_064_886L // 07:24:24
    private val endedOld = 1_794_803_090_514L   // 07:24:50

    @Test
    fun `the end of the old basal is filed while the new one is not on record yet`() {
        val order = order()
        order.started(17368, started340 - 5 * 60_000L)
        assertThat(order.endIsOfAReplacedBasal(17369, endedOld)).isFalse()
    }

    @Test
    fun `a copy of that end after the new basal has been filed is passed over`() {
        val order = order()
        order.started(17370, started340)
        assertThat(order.endIsOfAReplacedBasal(17369, endedOld)).isTrue()
    }

    @Test
    fun `the end that really ends the new basal later is filed`() {
        val order = order()
        order.started(17370, started340)
        assertThat(order.endIsOfAReplacedBasal(17372, started340 + 15 * 60_000L)).isFalse()
    }

    @Test
    fun `an old end whose moment lies before the new basal began cannot touch it and is left to AAPS`() {
        val order = order()
        order.started(17370, started340)
        assertThat(order.endIsOfAReplacedBasal(17365, started340 - 10 * 60_000L)).isFalse()
    }

    @Test
    fun `with nothing remembered every end is filed as before`() {
        assertThat(order().endIsOfAReplacedBasal(17369, endedOld)).isFalse()
    }

    @Test
    fun `the order survives a restart of the app`() {
        order().started(17370, started340)
        assertThat(order().endIsOfAReplacedBasal(17369, endedOld)).isTrue()
    }

    @Test
    fun `a copy of an older start does not move the order back`() {
        val order = order()
        order.started(17370, started340)
        order.started(17368, started340 - 5 * 60_000L)
        assertThat(order.endIsOfAReplacedBasal(17369, endedOld)).isTrue()
        assertThat(kept).isEqualTo(17370L to started340)
    }

    @Test
    fun `a watch that counts anew is followed, and its ends are not taken for old copies`() {
        val order = order()
        order.started(17370, started340)
        // The watch's data was cleared: its numbers begin again, far below.
        assertThat(order.endIsOfAReplacedBasal(3, started340 + 60 * 60_000L)).isFalse()
        order.started(4, started340 + 60 * 60_000L - 20_000L)
        assertThat(kept?.first).isEqualTo(4L)
        assertThat(order.endIsOfAReplacedBasal(3, started340 + 60 * 60_000L)).isTrue()
        assertThat(order.endIsOfAReplacedBasal(5, started340 + 65 * 60_000L)).isFalse()
    }

    @Test
    fun `a store that cannot be read or written does not stop the events`() {
        val order = TbrEventOrder(load = { error("no storage") }, save = { _, _ -> error("no storage") })
        assertThat(order.endIsOfAReplacedBasal(17369, endedOld)).isFalse()
        order.started(17370, started340)
        assertThat(order.endIsOfAReplacedBasal(17369, endedOld)).isTrue()
    }
}
