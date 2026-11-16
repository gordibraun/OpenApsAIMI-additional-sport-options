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
            context, aapsLogger, rh, preferences, commandQueue, link, pumpSync, constraintsChecker, uiInteraction, pumpEnactResultProvider, snapshots, persistenceLayer,
            { org.mockito.kotlin.mock<app.aaps.core.interfaces.iob.IobCobCalculator>() }
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

    // ---- temporary basals, when the pump's events arrive twice ----------------------------------------

    /**
     * The records of temporary basals, kept by the two rules AAPS files them with: a start ends whatever runs
     * at its moment and is entered once per id; an end, once per id, ends whatever runs at its moment unless
     * that has been ended by an end already.
     */
    private class BasalRecords {

        class Row(val pumpId: Long, val start: Long, var end: Long, val percent: Int, var endId: Long? = null)

        val rows = mutableListOf<Row>()
        private fun runningAt(t: Long) = rows.filter { it.start <= t && it.end > t }.maxByOrNull { it.start }

        fun start(timestamp: Long, percent: Int, durationMs: Long, pumpId: Long): Boolean {
            if (rows.any { it.pumpId == pumpId }) return false
            runningAt(timestamp)?.let { it.end = timestamp; it.endId = pumpId }
            rows += Row(pumpId, timestamp, timestamp + durationMs, percent)
            return true
        }

        fun stop(timestamp: Long, endPumpId: Long): Boolean {
            if (rows.any { it.endId == endPumpId }) return false
            val running = runningAt(timestamp)?.takeIf { it.endId == null } ?: return false
            running.end = timestamp
            running.endId = endPumpId
            return true
        }

        fun minutesOf(percent: Int) = rows.single { it.percent == percent }.let { (it.end - it.start) / 60_000.0 }
    }

    private fun recordsKeptIn(records: BasalRecords) {
        whenever(pumpSync.syncTemporaryBasalWithPumpId(any(), any(), any(), any(), anyOrNull(), any(), any(), any())).thenAnswer {
            records.start(it.getArgument(0), it.getArgument<Double>(1).toInt(), it.getArgument(2), it.getArgument(5))
        }
        whenever(pumpSync.syncStopTemporaryBasalWithPumpId(any(), any(), any(), any(), any())).thenAnswer {
            records.stop(it.getArgument(0), it.getArgument(1))
        }
    }

    private fun tbrStarted(seq: Long, at: Long, percent: Int) = PumpEvent(
        seq = seq, type = PumpEvent.Type.TBR_STARTED, timestampEpochMs = at, tbrPercentage = percent, tbrDurationMinutes = 30, tbrType = "normal", pumpSerial = pumpA
    )

    private fun tbrEnded(seq: Long, at: Long) = PumpEvent(seq = seq, type = PumpEvent.Type.TBR_ENDED, timestampEpochMs = at, pumpSerial = pumpA)

    // 16.11.2026, as the watch reported it: 150 % from 07:19:05; replaced at 07:24 by 340 % (the old one "ended"
    // at 07:24:50, the new one "started" at 07:24:24, in that order); replaced at 07:39 by 40 %.
    private val at071905 = 1_794_802_745_942L
    private val at072424 = 1_794_803_064_886L
    private val at072450 = 1_794_803_090_514L
    private val at073906 = 1_794_803_946_576L
    private val at073938 = 1_794_803_978_185L

    @Test
    fun `a basal stays on record for as long as the pump ran it although its events arrived twice`() {
        registered(pumpA)
        watchHolds(pumpA)
        val records = BasalRecords()
        recordsKeptIn(records)
        plugin.handlePumpEvent(tbrStarted(17368, at071905, 150))
        // The answer to the command brings the pair, and the watch's own message brings it again.
        repeat(2) {
            plugin.handlePumpEvent(tbrEnded(17369, at072450))
            plugin.handlePumpEvent(tbrStarted(17370, at072424, 340))
        }
        assertThat(records.minutesOf(340)).isEqualTo(30.0)
        repeat(2) {
            plugin.handlePumpEvent(tbrEnded(17372, at073938))
            plugin.handlePumpEvent(tbrStarted(17373, at073906, 40))
        }
        // 07:19:05-07:24:24, 07:24:24-07:39:06, and the last one still running.
        assertThat(records.minutesOf(150)).isWithin(0.01).of((at072424 - at071905) / 60_000.0)
        assertThat(records.minutesOf(340)).isWithin(0.01).of((at073906 - at072424) / 60_000.0)
        assertThat(records.minutesOf(40)).isEqualTo(30.0)
    }

    @Test
    fun `events that arrive once are filed exactly as before`() {
        registered(pumpA)
        watchHolds(pumpA)
        val records = BasalRecords()
        recordsKeptIn(records)
        plugin.handlePumpEvent(tbrStarted(17368, at071905, 150))
        plugin.handlePumpEvent(tbrEnded(17369, at072450))
        plugin.handlePumpEvent(tbrStarted(17370, at072424, 340))
        plugin.handlePumpEvent(tbrEnded(17372, at073938))
        plugin.handlePumpEvent(tbrStarted(17373, at073906, 40))
        assertThat(records.minutesOf(150)).isWithin(0.01).of((at072424 - at071905) / 60_000.0)
        assertThat(records.minutesOf(340)).isWithin(0.01).of((at073906 - at072424) / 60_000.0)
        assertThat(records.minutesOf(40)).isEqualTo(30.0)
    }

    @Test
    fun `a basal the owner or the loop cancels is ended where the pump ended it, also when told twice`() {
        registered(pumpA)
        watchHolds(pumpA)
        val records = BasalRecords()
        recordsKeptIn(records)
        plugin.handlePumpEvent(tbrStarted(17370, at072424, 340))
        // Cancelled five minutes in: an end alone, no start after it.
        repeat(2) { plugin.handlePumpEvent(tbrEnded(17371, at072424 + 5 * 60_000L)) }
        assertThat(records.minutesOf(340)).isEqualTo(5.0)
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
    fun `a note of the watch about a dose is told from one that only narrates`() {
        // Doses given or tried on the watch: a dose being confirmed on the phone may not know of them.
        listOf(
            "Болюс с часов: 0,9 ЕД на 7 г — подан",
            "Болюс с часов: 1,8 ЕД, доза владельца — подан",
            "Болюс с часов: 0,7 ЕД на 15 г — исход не выяснен, часы сверяются с помпой",
            "Болюс с часов: 0,7 ЕД на 10 г — FAILED: pump not reached",
            "Часы без телефона: микродоза 0.1 ЕД. сахар 145, изменение +5 за 5 мин: прогноз 133 (после 60 мин), цель 117 — нужно 0.3 ЕД, микродоза 0.1 ЕД, базал 140 %",
            "Часы без телефона: микродоза 0.0 ЕД — не выполнено (pump is Suspended, not ready for a bolus). сахар 139",
            "", null
        ).forEach { assertThat(ComboWatchPlugin.speaksOfInsulin(it)).isTrue() }
        // What the watch noticed or did with the basal rate: the records the dose was calculated from are as they were.
        listOf(
            "Часы: Инсулин почти не действует: сахар 220 (+11 за 5 мин), за 2 ч подано 2.9 ЕД болюсами, прогноз обещал около 99 — по факту на 104 выше.",
            "Часы: Инсулин действует хуже, чем ожидалось: сахар 181 (+3 за 5 мин), за 2 ч подано 3.7 ЕД болюсами, прогноз обещал около 39 — по факту на 142 выше.",
            "Часы без телефона: сахар 108, изменение -6 за 5 мин: за 30 мин опустится до 72, цель 117 — базал остановлен",
            "Часы без телефона: сахар 112, изменение +10 за 5 мин: прогноз 140 (после 60 мин), цель 117 — нужно 0.4 ЕД, базал 150 %",
            "часы: Похоже на нагрузку: спорт с 14:43 (пульс 99 при покое 67). Дозы не меняются, пока хозяин не ответит на часах",
            "Часы: владелец сменил набор/канюлю при сахаре 180; проверка через час"
        ).forEach { assertThat(ComboWatchPlugin.speaksOfInsulin(it)).isFalse() }
    }

    @Test
    fun `a meal the watch dosed itself is read from its note, and only when the pump delivered`() {
        assertThat(ComboWatchPlugin.gramsCoveredOnTheWatch("Болюс с часов: 0,7 ЕД на 10 г — подан")).isEqualTo(10)
        assertThat(ComboWatchPlugin.gramsCoveredOnTheWatch("Болюс с часов: 2.3 ЕД на 35 г — подан")).isEqualTo(35)
        // A dose the owner typed in has no carbohydrates of its own.
        assertThat(ComboWatchPlugin.gramsCoveredOnTheWatch("Болюс с часов: 1,5 ЕД, доза владельца — подан")).isNull()
        // Not delivered, or not known: the carbohydrates stay as they are.
        assertThat(ComboWatchPlugin.gramsCoveredOnTheWatch("Болюс с часов: 0,7 ЕД на 10 г — FAILED: pump not reached")).isNull()
        assertThat(ComboWatchPlugin.gramsCoveredOnTheWatch("Болюс с часов: 0,7 ЕД на 10 г — исход не выяснен, часы сверяются с помпой")).isNull()
        assertThat(ComboWatchPlugin.gramsCoveredOnTheWatch("Часы без телефона: сахар 148")).isNull()
    }

    @Test
    fun `the food type of carbs filed from the watch is read from their note`() {
        assertThat(ComboWatchPlugin.watchCarbType("С часов: fast")).isEqualTo("fast")
        assertThat(ComboWatchPlugin.watchCarbType("С часов: slow")).isEqualTo("slow")
        assertThat(ComboWatchPlugin.watchCarbType("С часов: balanced")).isEqualTo("balanced")
        assertThat(ComboWatchPlugin.watchCarbType(null)).isEqualTo("balanced")
    }

    @Test
    fun `the pump's 100 percent is the profile's own rate wherever the pump holds it rounded`() {
        // 1.23 U/h asked, 1.25 held: AAPS must not see two different rates and read the pump every five minutes.
        assertThat(ComboWatchPlugin.baseRate(1250, 1.23)).isEqualTo(1.23)
        assertThat(ComboWatchPlugin.baseRate(1300, 1.28)).isEqualTo(1.28)
        assertThat(ComboWatchPlugin.baseRate(1000, 1.02)).isEqualTo(1.02)
        assertThat(ComboWatchPlugin.baseRate(940, 0.94)).isEqualTo(0.94)
        // A pump that holds something else says so.
        assertThat(ComboWatchPlugin.baseRate(1500, 1.23)).isEqualTo(1.5)
        assertThat(ComboWatchPlugin.baseRate(1250, 1.31)).isEqualTo(1.25)
        // No profile known yet, or a nonsense one: the pump's figure.
        assertThat(ComboWatchPlugin.baseRate(1250, null)).isEqualTo(1.25)
        assertThat(ComboWatchPlugin.baseRate(1250, 0.0)).isEqualTo(1.25)
        assertThat(ComboWatchPlugin.baseRate(1250, Double.NaN)).isEqualTo(1.25)
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
