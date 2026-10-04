package app.aaps.pump.combowatch

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.IDs
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringNonKey
import app.aaps.pump.combowatch.protocol.BolusKind
import app.aaps.pump.combowatch.protocol.ComboResult
import app.aaps.pump.combowatch.protocol.CommandKind
import app.aaps.pump.combowatch.protocol.Outcome
import app.aaps.pump.combowatch.protocol.PumpEvent
import app.aaps.pump.combowatch.protocol.PumpSnapshot
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Single
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.anyDouble
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
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
    @Mock lateinit var snapshots: RegulationSnapshotBuilder
    @Mock lateinit var persistenceLayer: PersistenceLayer

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
            aapsLogger, rh, preferences, commandQueue, link, pumpSync, constraintsChecker, uiInteraction, pumpEnactResultProvider, snapshots, persistenceLayer
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
            execute(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        } doReturn result
    }

    private fun verifyNothingSentExceptStatus() = verifyBlocking(link, never()) {
        execute(
            org.mockito.kotlin.argThat { this != CommandKind.STATUS }, any(), any(), any(), any(),
            anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
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

    // ---- history from before AAPS registered the pump anew ----------------------------------------

    private fun aapsRegisteredThePumpAt(epochMs: Long) {
        whenever(preferences.get(LongNonKey.ActivePumpChangeTimestamp)).thenReturn(epochMs)
        whenever(pumpSync.syncBolusWithPumpId(1_000L, 0.3, BS.Type.SMB, 77L, PumpType.ACCU_CHEK_COMBO, pumpA)).thenReturn(false)
        whenever(persistenceLayer.syncPumpBolus(any(), any())).thenReturn(Single.just(PersistenceLayer.TransactionResult()))
    }

    private fun recordsOf(pump: String?) {
        whenever(persistenceLayer.getBolusesFromTimeToTime(any(), any(), any())).thenReturn(
            listOfNotNull(pump?.let { BS(timestamp = 400L, amount = 1.0, type = BS.Type.NORMAL, ids = IDs(pumpSerial = it)) })
        )
        whenever(persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
    }

    @Test
    fun `a bolus from before AAPS registered the pump anew is filed when the pump was in use before`() {
        registered(pumpA)
        watchHolds(pumpA)
        aapsRegisteredThePumpAt(5_000L)
        recordsOf(pumpA)
        plugin.handlePumpEvent(bolus(pumpA))
        verify(persistenceLayer).syncPumpBolus(
            argThat { timestamp == 1_000L && amount == 0.3 && type == BS.Type.SMB && ids.pumpId == 77L && ids.pumpSerial == pumpA && ids.pumpType == PumpType.ACCU_CHEK_COMBO },
            eq(BS.Type.SMB)
        )
    }

    @Test
    fun `the old history of a pump never seen before is left to AAPS's rule`() {
        registered(pumpA)
        watchHolds(pumpA)
        aapsRegisteredThePumpAt(5_000L)
        recordsOf(null)
        plugin.handlePumpEvent(bolus(pumpA))
        verify(persistenceLayer, never()).syncPumpBolus(any(), any())
    }

    @Test
    fun `records of another pump do not vouch for this one`() {
        registered(pumpA)
        watchHolds(pumpA)
        aapsRegisteredThePumpAt(5_000L)
        recordsOf(pumpB)
        plugin.handlePumpEvent(bolus(pumpA))
        verify(persistenceLayer, never()).syncPumpBolus(any(), any())
    }

    @Test
    fun `a bolus AAPS turned away for some other reason is not forced in`() {
        registered(pumpA)
        watchHolds(pumpA)
        // Registered before the bolus: whatever AAPS did not like about it, it was not its age.
        aapsRegisteredThePumpAt(500L)
        recordsOf(pumpA)
        plugin.handlePumpEvent(bolus(pumpA))
        verify(persistenceLayer, never()).syncPumpBolus(any(), any())
    }

    @Test
    fun `carbohydrates entered on the watch are filed as carbohydrates, whatever the pump's registration says`() {
        registered(pumpA)
        watchHolds(pumpA)
        doReturn("С часов: fast").whenever(rh).gs(eq(R.string.combowatch_carbs_from_watch), anyString())
        whenever(persistenceLayer.insertPumpCarbsIfNewByTimestamp(any())).thenReturn(Single.just(PersistenceLayer.TransactionResult()))
        plugin.handlePumpEvent(PumpEvent(seq = 9, type = PumpEvent.Type.CARBS, timestampEpochMs = 2_000L, pumpSerial = pumpA, note = "fast", carbsGrams = 20))
        verify(persistenceLayer).insertPumpCarbsIfNewByTimestamp(argThat { timestamp == 2_000L && amount == 20.0 && ids.pumpSerial == pumpA })
        verifyNoInteractions(pumpSync)
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
    fun `what the watch decided by itself is kept as a note with the treatments`() {
        registered(pumpA)
        watchHolds(pumpA)
        plugin.handlePumpEvent(PumpEvent(seq = 5, type = PumpEvent.Type.WATCH_NOTE, timestampEpochMs = 2_000L, pumpSerial = pumpA, note = "Часы без телефона: базал 0 %"))
        verify(pumpSync).insertTherapyEventIfNewWithTimestamp(2_000L, app.aaps.core.data.model.TE.Type.NOTE, "Часы без телефона: базал 0 %", null, PumpType.ACCU_CHEK_COMBO, pumpA)
    }

    @Test
    fun `an event of a kind this build does not know is passed over without harm`() {
        registered(pumpA)
        watchHolds(pumpA)
        plugin.handlePumpEvent(PumpEvent(seq = 6, type = PumpEvent.Type.UNKNOWN, timestampEpochMs = 2_000L, pumpSerial = pumpA))
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
            execute(eq(CommandKind.STATUS), eq(pumpA), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
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
    fun `with no pump registered the watch's pump is registered before a command is sent`() {
        // Otherwise AAPS would spend the command's own record on the registration and drop it.
        registered(null)
        watchHolds(pumpA)
        commandsAnswer(ComboResult("c", Outcome.DONE, 1L, tbrOutcome = "SET_NORMAL_TBR", tbrPercentage = 0, tbrDurationMinutes = 30))
        plugin.setTempBasalPercent(0, 30, validProfile, true, PumpSync.TemporaryBasalType.EMULATED_PUMP_SUSPEND)
        val order = inOrder(pumpSync, link)
        order.verify(pumpSync).syncStopTemporaryBasalWithPumpId(any(), any(), eq(PumpType.ACCU_CHEK_COMBO), eq(pumpA), any())
        order.verifyBlocking(link) {
            execute(eq(CommandKind.SET_TBR), eq(pumpA), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        }
    }

    @Test
    fun `reading the pump registers the watch's pump once it is known`() {
        registered(null)
        watchHolds(pumpA)
        commandsAnswer(ComboResult("s", Outcome.DONE, 1L))
        plugin.getPumpStatus("test")
        verify(pumpSync).syncStopTemporaryBasalWithPumpId(any(), any(), eq(PumpType.ACCU_CHEK_COMBO), eq(pumpA), any())
    }

    @Test
    fun `a registered pump is never registered over, and an unknown one never registered`() {
        registered(pumpA)
        watchHolds(pumpA)
        commandsAnswer(ComboResult("s", Outcome.DONE, 1L))
        plugin.getPumpStatus("test")
        plugin.setTempBasalPercent(0, 30, validProfile, true, PumpSync.TemporaryBasalType.EMULATED_PUMP_SUSPEND)

        registered(null)
        watchHolds(null, known = false)
        plugin.getPumpStatus("test")
        verifyNoInteractions(pumpSync)
    }

    // ---- basal profile ---------------------------------------------------------------------------

    @Test
    fun `basal rates are rounded the way the pump holds them`() {
        // The same cases comboctl's BasalProfile rounds: 0.01 steps up to 1 U/h, 0.05 above.
        assertThat(listOf(930, 1230, 1260, 1280, 1290, 1370, 1000, 1024, 1025, 999, 40, 20, 10300, 10349, 10350).map { ComboWatchPlugin.comboBasalFactor(it) })
            .isEqualTo(listOf(930, 1250, 1250, 1300, 1300, 1350, 1000, 1000, 1050, 1000, 50, 0, 10300, 10300, 10400))
    }

    @Test
    fun `a profile equals the pump's when it matches after the pump's rounding`() {
        registered(pumpA)
        val asked = listOf(0.93, 1.23, 1.26, 1.28, 1.29, 1.23, 1.37, 1.2, 1.1, 0.94, 1.0, 0.95, 1.0, 1.15, 1.2, 1.1, 0.94, 0.81, 1.05, 1.05, 1.05, 1.1, 1.05, 1.15)
        val profile = org.mockito.kotlin.mock<app.aaps.core.interfaces.profile.Profile>()
        for (hour in 0 until 24) whenever(profile.getBasalTimeFromMidnight(hour * 60 * 60)).thenReturn(asked[hour])
        val held = listOf(930, 1250, 1250, 1300, 1300, 1250, 1350, 1200, 1100, 940, 1000, 950, 1000, 1150, 1200, 1100, 940, 810, 1050, 1050, 1050, 1100, 1050, 1150)
        fun pumpHolds(factors: List<Int>) {
            whenever(link.watchPumpKnown).thenReturn(true)
            whenever(link.watchPump).thenReturn(pumpA)
            whenever(link.lastSnapshot).thenReturn(PumpSnapshot(1L, false, null, null, 100, "FULL_BATTERY", pumpA, factors))
        }
        pumpHolds(held)
        assertThat(plugin.isThisProfileSet(profile)).isTrue()
        // A real difference in one hour is still a difference.
        pumpHolds(held.toMutableList().also { it[3] = 1350 })
        assertThat(plugin.isThisProfileSet(profile)).isFalse()
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
            execute(eq(CommandKind.SET_TBR), eq(pumpA), any(), any(), any(), eq(0), eq(30), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        }
        assertThat(plugin.isInitialized()).isTrue()
    }
}
