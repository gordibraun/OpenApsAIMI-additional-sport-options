package app.aaps.pump.combowatch

import app.aaps.core.data.model.BS
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.StringNonKey
import app.aaps.pump.combowatch.protocol.BolusKind
import app.aaps.pump.combowatch.protocol.ComboResult
import app.aaps.pump.combowatch.protocol.CommandKind
import app.aaps.pump.combowatch.protocol.Outcome
import app.aaps.pump.combowatch.protocol.PumpEvent
import app.aaps.pump.combowatch.protocol.PumpSnapshot
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.anyDouble
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/**
 * Which pump the phone's decisions and records belong to.
 *
 * The watch can be paired with a different pump at any time, and AAPS accepts records from one
 * registered pump only. These tests pin down that nothing is ever filed under, or sent to, a pump
 * other than the one the phone is bound to.
 */
class ComboWatchPluginTest : TestBaseWithProfile() {

    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var pumpSync: PumpSync
    @Mock lateinit var link: ComboWatchLink
    @Mock lateinit var uiInteraction: UiInteraction

    private lateinit var plugin: ComboWatchPlugin

    private val pumpA = "PUMP_41056642"
    private val pumpB = "PUMP_10392647"

    @BeforeEach
    fun prepare() {
        // doReturn, because the base class already answers some of these shapes by formatting.
        doReturn("no pump on the watch").whenever(rh).gs(R.string.combowatch_no_pump_on_watch)
        doReturn("another pump on the watch").whenever(rh).gs(eq(R.string.combowatch_other_pump), anyString(), anyString())
        doReturn("bolus of another pump").whenever(rh).gs(eq(R.string.combowatch_bolus_of_other_pump), anyDouble(), anyString(), anyString())
        plugin = ComboWatchPlugin(
            aapsLogger, rh, preferences, commandQueue, link, pumpSync, constraintsChecker, uiInteraction, pumpEnactResultProvider
        )
    }

    private fun registered(pump: String?) {
        whenever(preferences.get(StringNonKey.ActivePumpSerialNumber)).thenReturn(pump ?: "")
    }

    private fun watchHolds(pump: String?, known: Boolean = true) {
        whenever(link.watchPumpKnown).thenReturn(known)
        whenever(link.watchPump).thenReturn(pump)
        whenever(link.lastSnapshot).thenReturn(
            pump?.let { PumpSnapshot(1L, tbrRunning = false, tbrPercentage = null, tbrRemainingMinutes = null, reservoirUnits = 100, batteryState = "FULL_BATTERY", pumpSerial = it) }
        )
    }

    private fun bolus(from: String?) = PumpEvent(
        seq = 1, type = PumpEvent.Type.BOLUS_INFUSED, timestampEpochMs = 1_000L,
        bolusId = 77L, bolusTenthsIU = 3, bolusKind = BolusKind.SMB, pumpSerial = from
    )

    private fun commandsAnswer(result: ComboResult) = link.stub {
        onBlocking {
            execute(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        } doReturn result
    }

    private fun verifyNothingSentExceptStatus() = verifyBlocking(link, never()) {
        execute(
            org.mockito.kotlin.argThat { this != CommandKind.STATUS }, any(), any(), any(), any(),
            anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()
        )
    }

    // ---- records --------------------------------------------------------------------------------

    @Test
    fun `a record of the registered pump is filed under that pump`() {
        registered(pumpA)
        watchHolds(pumpA)
        plugin.handlePumpEvent(bolus(pumpA))
        verify(pumpSync).syncBolusWithPumpId(1_000L, 0.3, BS.Type.SMB, 77L, PumpType.ACCU_CHEK_COMBO, pumpA)
    }

    @Test
    fun `with no pump registered the watch's pump is taken, and its record registers it`() {
        registered(null)
        watchHolds(pumpA)
        plugin.handlePumpEvent(bolus(pumpA))
        verify(pumpSync).syncBolusWithPumpId(1_000L, 0.3, BS.Type.SMB, 77L, PumpType.ACCU_CHEK_COMBO, pumpA)
        assertThat(plugin.serialNumber()).isEqualTo(pumpA)
    }

    @Test
    fun `a record of another pump is never handed to AAPS, not even with no pump registered`() {
        // Handing it over would make AAPS register that pump and then turn the right one away.
        registered(null)
        watchHolds(pumpA)
        plugin.handlePumpEvent(bolus(pumpB))
        verifyNoInteractions(pumpSync)
        verify(uiInteraction).addNotification(any(), eq("bolus of another pump"), any())
    }

    @Test
    fun `a record that names no pump is not filed under a guess`() {
        registered(pumpA)
        watchHolds(pumpA)
        plugin.handlePumpEvent(bolus(null))
        verifyNoInteractions(pumpSync)
    }

    @Test
    fun `a record of the pump the watch moved to is not filed under the registered one`() {
        registered(pumpA)
        watchHolds(pumpB)
        plugin.handlePumpEvent(bolus(pumpB))
        verifyNoInteractions(pumpSync)
    }

    @Test
    fun `while it is not known which pump the watch holds a record is left with the watch`() {
        registered(null)
        watchHolds(null, known = false)
        // Throwing is what leaves it unacknowledged.
        assertThrows<IllegalStateException> { plugin.handlePumpEvent(bolus(pumpA)) }
        verifyNoInteractions(pumpSync)
    }

    // ---- commands -------------------------------------------------------------------------------

    @Test
    fun `the lease names the registered pump whatever the watch holds`() {
        registered(pumpA)
        watchHolds(pumpB)
        assertThat(plugin.serialNumber()).isEqualTo(pumpA)
        commandsAnswer(ComboResult("s", Outcome.DONE, 1L))
        plugin.getPumpStatus("test")
        verifyBlocking(link) {
            execute(eq(CommandKind.STATUS), eq(pumpA), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        }
    }

    @Test
    fun `nothing that changes delivery is sent while the watch holds another pump`() {
        registered(pumpA)
        watchHolds(pumpB)
        val tbr = plugin.setTempBasalPercent(0, 30, validProfile, true, PumpSync.TemporaryBasalType.EMULATED_PUMP_SUSPEND)
        assertThat(tbr.success).isFalse()
        assertThat(tbr.enacted).isFalse()
        assertThat(tbr.comment).isEqualTo("another pump on the watch")
        val cancel = plugin.cancelTempBasal(true)
        assertThat(cancel.success).isFalse()
        verifyNothingSentExceptStatus()
        verify(uiInteraction).addNotification(any(), eq("another pump on the watch"), any())
        // A reading of the other pump does not make this driver look ready either.
        assertThat(plugin.isInitialized()).isFalse()
    }

    @Test
    fun `nothing that changes delivery is sent while the watch holds no pump`() {
        registered(pumpA)
        watchHolds(null)
        val tbr = plugin.setTempBasalPercent(0, 30, validProfile, true, PumpSync.TemporaryBasalType.EMULATED_PUMP_SUSPEND)
        assertThat(tbr.success).isFalse()
        assertThat(tbr.comment).isEqualTo("no pump on the watch")
        verifyNothingSentExceptStatus()
    }

    @Test
    fun `a command for the bound pump goes to the watch under its name`() {
        registered(pumpA)
        watchHolds(pumpA)
        commandsAnswer(ComboResult("c", Outcome.DONE, 1L, tbrOutcome = "SET_NORMAL_TBR", tbrPercentage = 0, tbrDurationMinutes = 30))
        val tbr = plugin.setTempBasalPercent(0, 30, validProfile, true, PumpSync.TemporaryBasalType.EMULATED_PUMP_SUSPEND)
        assertThat(tbr.success).isTrue()
        assertThat(tbr.enacted).isTrue()
        verifyBlocking(link) {
            execute(eq(CommandKind.SET_TBR), eq(pumpA), any(), any(), any(), eq(0), eq(30), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        }
        assertThat(plugin.isInitialized()).isTrue()
    }
}
