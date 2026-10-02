package app.aaps.pump.combowatch

import app.aaps.core.data.iob.CobInfo
import app.aaps.core.data.iob.Iob
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** The snapshot is the loop run's own numbers, taken once per run and never recomputed. */
class RegulationSnapshotBuilderTest : TestBaseWithProfile() {

    @Mock lateinit var loop: Loop
    @Mock lateinit var pumpSync: PumpSync
    @Mock lateinit var insulin: Insulin
    @Mock lateinit var request: APSResult
    @Mock lateinit var pumpProfile: Profile

    private lateinit var builder: RegulationSnapshotBuilder
    private val runAt = 1_800_000_000_000L
    private val pump = "PUMP_41056642"

    @BeforeEach
    fun prepare() {
        whenever(activePlugin.activeInsulin).thenReturn(insulin)
        // An insulin that is gone, linearly, after five hours.
        doAnswer { invocation ->
            val minutes = invocation.getArgument<Long>(1) / 60_000.0
            Iob(iobContrib = (1.0 - minutes / 300.0).coerceIn(0.0, 1.0))
        }.whenever(insulin).iobCalcForTreatment(any(), any(), any())

        whenever(pumpProfile.dia).thenReturn(5.0)
        whenever(pumpProfile.getBasal(any())).thenReturn(1.2)
        noTemporaryBasal()

        val lastRun = Loop.LastRun().also { it.request = request }
        whenever(loop.lastRun).thenReturn(lastRun)
        whenever(request.oapsProfileAimi).thenReturn(null)
        whenever(request.oapsProfileAutoIsf).thenReturn(null)
        loopRan(runAt)
        whenever(request.targetBG).thenReturn(117.0)
        whenever(request.mealData).thenReturn(MealData().also { it.mealCOB = 12.0 })

        builder = RegulationSnapshotBuilder({ loop }, { activePlugin }, { iobCobCalculator }, pumpSync, aapsLogger)
    }

    private fun loopRan(at: Long, sensitivity: Double? = 48.0, carbRatio: Double? = 9.0) {
        whenever(request.iobData).thenReturn(Array(48) { IobTotal(time = at + it * 300_000L, iob = 2.0 - it * 0.04, activity = 0.01 - it * 0.0002) })
        whenever(request.rawData()).thenReturn(
            RT(runningDynamicIsf = true, variable_sens = sensitivity, targetBG = 117.0, hypoThreshold = 72.0, predBGs = Predictions(AIMI_FINAL = listOf(120, 118, 115)))
        )
        whenever(request.variableSens).thenReturn(sensitivity)
        // Mocked before it is handed to another stubbing, which must not be interrupted by this one.
        val algorithmProfile = carbRatio?.let { ratio -> mock<app.aaps.core.interfaces.aps.OapsProfile>().also { whenever(it.carb_ratio).thenReturn(ratio) } }
        whenever(request.oapsProfile).thenReturn(algorithmProfile)
    }

    private fun noTemporaryBasal() =
        whenever(pumpSync.expectedPumpState()).thenReturn(PumpSync.PumpState(null, null, null, pumpProfile, pump))

    private fun temporaryBasal(percent: Double, startedAt: Long, minutes: Int) =
        whenever(pumpSync.expectedPumpState()).thenReturn(
            PumpSync.PumpState(
                PumpSync.PumpState.TemporaryBasal(startedAt, minutes * 60_000L, percent, false, PumpSync.TemporaryBasalType.NORMAL, 1L, startedAt, PumpType.ACCU_CHEK_COMBO, pump),
                null, null, pumpProfile, pump
            )
        )

    @Test
    fun `the snapshot carries the loop run's own numbers`() {
        val snapshot = builder.current(pump, nowEpochMs = runAt + 5_000)!!
        assertThat(snapshot.madeAtEpochMs).isEqualTo(runAt)
        assertThat(snapshot.pumpSerial).isEqualTo(pump)
        assertThat(snapshot.sensitivityMgdlPerU).isEqualTo(48.0)
        assertThat(snapshot.carbRatioGPerU).isEqualTo(9.0)
        assertThat(snapshot.targetMgdl).isEqualTo(117.0)
        assertThat(snapshot.hypoThresholdMgdl).isEqualTo(72.0)
        assertThat(snapshot.cobG).isEqualTo(12.0)
        assertThat(snapshot.iobU).isEqualTo(2.0)
        assertThat(snapshot.insulinActivity).hasSize(48)
        assertThat(snapshot.insulinActivity.first()).isEqualTo(0.01)
        assertThat(snapshot.phoneForecast).containsExactly(120, 118, 115).inOrder()
        assertThat(snapshot.assumedTbr).isNull()
    }

    @Test
    fun `carbohydrates entered after the run are in the snapshot that precedes their bolus`() {
        // The run saw 12 g. Fifty more were entered a minute ago; the overview's figure has them.
        whenever(iobCobCalculator.getCobInfo(any())).thenReturn(CobInfo(runAt, 61.0, 0.0))
        assertThat(builder.current(pump, nowEpochMs = runAt + 120_000)!!.cobG).isEqualTo(61.0)

        // It only ever adds to what the run saw: absorption since the run is the watch's to work out.
        whenever(iobCobCalculator.getCobInfo(any())).thenReturn(CobInfo(runAt, 9.0, 0.0))
        assertThat(builder.current(pump, nowEpochMs = runAt + 180_000)!!.cobG).isEqualTo(12.0)
    }

    @Test
    fun `without a figure for carbohydrates now the run's own stands`() {
        whenever(iobCobCalculator.getCobInfo(any())).thenReturn(CobInfo(runAt, null, 0.0))
        assertThat(builder.current(pump)!!.cobG).isEqualTo(12.0)
        whenever(iobCobCalculator.getCobInfo(any())).thenThrow(IllegalStateException("not calculated yet"))
        assertThat(builder.current(pump)!!.cobG).isEqualTo(12.0)
    }

    @Test
    fun `a run with a number that is not a number gives no snapshot`() {
        whenever(request.iobData).thenReturn(Array(48) { IobTotal(time = runAt + it * 300_000L, iob = 2.0, activity = if (it == 7) Double.NaN else 0.01) })
        assertThat(builder.current(pump)).isNull()
    }

    @Test
    fun `the insulin's curve is read off the phone's insulin for eight hours`() {
        val curve = builder.current(pump)!!.insulinRemaining
        assertThat(curve).hasSize(193)
        assertThat(curve.first()).isEqualTo(1.0)
        // 150 minutes into a five-hour straight line.
        assertThat(curve[60]).isWithin(1e-9).of(0.5)
        assertThat(curve.last()).isEqualTo(0.0)
        assertThat(curve.zipWithNext().all { (earlier, later) -> later <= earlier }).isTrue()
    }

    @Test
    fun `the permission runs a day from the last renewal, not from the loop run`() {
        val first = builder.current(pump, nowEpochMs = runAt + 5_000)!!
        val later = builder.current(pump, nowEpochMs = runAt + 200_000)!!
        assertThat(first.validUntilEpochMs).isEqualTo(runAt + 5_000 + RegulationSnapshotBuilder.VALID_FOR_MS)
        assertThat(later.validUntilEpochMs).isEqualTo(runAt + 200_000 + RegulationSnapshotBuilder.VALID_FOR_MS)
        assertThat(later.madeAtEpochMs).isEqualTo(first.madeAtEpochMs)
    }

    @Test
    fun `the temporary basal the run saw is kept, even after the run's own command has replaced it`() {
        // 0 % was running when the loop ran, with twenty minutes left.
        temporaryBasal(percent = 0.0, startedAt = runAt - 600_000, minutes = 30)
        val atDispatch = builder.current(pump, nowEpochMs = runAt + 2_000)!!
        assertThat(atDispatch.assumedTbr!!.rateUph).isEqualTo(0.0)
        assertThat(atDispatch.assumedTbr!!.endsAtEpochMs).isEqualTo(runAt + 1_200_000)

        // The command went through; the records now show the new one. The insulin curve of this
        // run was still computed with the old one, and the snapshot goes on saying so.
        temporaryBasal(percent = 150.0, startedAt = runAt + 40_000, minutes = 30)
        val atRenewal = builder.current(pump, nowEpochMs = runAt + 180_000)!!
        assertThat(atRenewal.assumedTbr!!.rateUph).isEqualTo(0.0)
        assertThat(atRenewal.assumedTbr!!.endsAtEpochMs).isEqualTo(runAt + 1_200_000)
    }

    @Test
    fun `a percentage is turned into the rate it meant at the time of the run`() {
        temporaryBasal(percent = 150.0, startedAt = runAt - 60_000, minutes = 30)
        assertThat(builder.current(pump)!!.assumedTbr!!.rateUph).isWithin(1e-9).of(1.8)
    }

    @Test
    fun `a temporary basal that had already ended by the run is not carried`() {
        temporaryBasal(percent = 0.0, startedAt = runAt - 3_600_000, minutes = 30)
        assertThat(builder.current(pump)!!.assumedTbr).isNull()
    }

    @Test
    fun `a new loop run makes a new snapshot`() {
        builder.current(pump)
        loopRan(runAt + 300_000, sensitivity = 60.0)
        val next = builder.current(pump)!!
        assertThat(next.madeAtEpochMs).isEqualTo(runAt + 300_000)
        assertThat(next.sensitivityMgdlPerU).isEqualTo(60.0)
    }

    @Test
    fun `before the loop has run there is nothing to send`() {
        whenever(loop.lastRun).thenReturn(null)
        assertThat(builder.current(pump)).isNull()
    }

    @Test
    fun `a run without a usable sensitivity or carbohydrate ratio is not sent as if it had one`() {
        loopRan(runAt, sensitivity = null)
        assertThat(builder.current(pump)).isNull()
        loopRan(runAt, carbRatio = null)
        assertThat(builder.current(pump)).isNull()
    }

    @Test
    fun `a snapshot made for one pump is not handed out for another`() {
        builder.current(pump)
        whenever(loop.lastRun).thenReturn(null)
        assertThat(builder.current(pump)).isNotNull()
        assertThat(builder.current("PUMP_10392647")).isNull()
    }
}
