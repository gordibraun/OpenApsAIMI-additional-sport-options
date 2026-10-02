package app.aaps.pump.combowatch

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.combowatch.protocol.RegulationSnapshot
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Takes from the loop's latest run what the watch needs to keep basal safe while the phone is
 * away, and nothing else.
 *
 * Nothing is computed here for the watch's sake. The insulin activity ahead, the carbohydrates
 * left, the sensitivity and the thresholds are the ones the algorithm itself just worked with;
 * the insulin's action curve is read off the insulin the phone is set up with. The algorithm is
 * not touched and does not know this exists.
 */
@Singleton
class RegulationSnapshotBuilder @Inject constructor(
    private val loop: dagger.Lazy<Loop>,
    private val activePlugin: dagger.Lazy<ActivePlugin>,
    private val iobCobCalculator: dagger.Lazy<IobCobCalculator>,
    private val pumpSync: PumpSync,
    private val aapsLogger: AAPSLogger
) {

    @Volatile private var built: RegulationSnapshot? = null

    /**
     * The snapshot of the latest loop run, or null while the loop has not produced one that can
     * be used. Built once per run, the first time it is asked for - which is before the command
     * that run led to is sent, while the records still show the temporary basal the run saw.
     */
    fun current(pumpSerial: String, nowEpochMs: Long = System.currentTimeMillis()): RegulationSnapshot? {
        val fresh = runCatching { build(pumpSerial) }
            .onFailure { aapsLogger.warn(LTag.PUMP, "combowatch: no snapshot of the loop run: ${it.message}") }
            .getOrNull()
        if (fresh != null) built = fresh
        val snapshot = built?.takeIf { it.pumpSerial == pumpSerial } ?: return null
        return snapshot.copy(
            // The permission to act on it runs from the last time the phone was in touch.
            validUntilEpochMs = nowEpochMs + VALID_FOR_MS,
            cobG = maxOf(snapshot.cobG, carbohydratesOnBoardNow())
        )
    }

    /**
     * Carbohydrates entered since the run are not in it. The meal bolus that follows them will be
     * in the watch's own record of the pump, and without the carbohydrates beside it that bolus
     * would look to the watch like insulin with nothing to meet it. This is the figure the
     * overview shows, which counts an entry from the moment it is made.
     */
    private fun carbohydratesOnBoardNow(): Double =
        runCatching { iobCobCalculator.get().getCobInfo("combowatch").displayCob }.getOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0

    private fun build(pumpSerial: String): RegulationSnapshot? {
        val request = loop.get().lastRun?.request ?: return null
        val iob = request.iobData?.takeIf { it.isNotEmpty() } ?: return null
        val madeAt = iob[0].time
        built?.let { if (it.madeAtEpochMs == madeAt && it.pumpSerial == pumpSerial) return it }

        val result = request.rawData() as? RT
        val algorithmProfile = request.oapsProfileAimi
        val sensitivity = (result?.variable_sens ?: request.variableSens ?: algorithmProfile?.sens)
            ?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val carbRatio = (algorithmProfile?.carb_ratio ?: request.oapsProfile?.carb_ratio ?: request.oapsProfileAutoIsf?.carb_ratio)
            ?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val state = pumpSync.expectedPumpState()
        val dia = (algorithmProfile?.dia?.takeIf { it > 0.0 } ?: state.profile?.dia)?.takeIf { it.isFinite() && it > 0.0 } ?: return null

        val insulin = activePlugin.get().activeInsulin
        val unitDose = BS(timestamp = 0L, amount = 1.0, type = BS.Type.NORMAL)
        val remaining = List(INSULIN_SAMPLES) { index ->
            insulin.iobCalcForTreatment(unitDose, index * INSULIN_SAMPLE_MS, dia).iobContrib.coerceIn(0.0, 1.0)
        }

        // What the records show running as this run's insulin curve was computed. Asked for now,
        // before the run's own command changes it.
        val assumed = state.temporaryBasal?.takeIf { it.end > madeAt }?.let { tbr ->
            state.profile?.let { profile -> RegulationSnapshot.AssumedTbr(tbr.convertedToAbsolute(madeAt, profile), tbr.end) }
        }

        return RegulationSnapshot(
            madeAtEpochMs = madeAt,
            pumpSerial = pumpSerial,
            validUntilEpochMs = 0L,
            targetMgdl = result?.targetBG ?: request.targetBG,
            hypoThresholdMgdl = result?.hypoThreshold ?: 0.0,
            sensitivityMgdlPerU = sensitivity,
            carbRatioGPerU = carbRatio,
            cobG = request.mealData?.mealCOB ?: 0.0,
            iobU = iob[0].iob,
            insulinActivity = iob.map { it.activity },
            insulinRemaining = remaining,
            assumedTbr = assumed,
            phoneForecast = result?.predBGs?.AIMI_FINAL
        ).takeIf { it.isWellFormed }
            ?.also { aapsLogger.debug(LTag.PUMP, "combowatch: snapshot of the loop run at $madeAt: IOB ${it.iobU}, COB ${it.cobG}, ISF ${it.sensitivityMgdlPerU}") }
    }

    companion object {

        /** Without the phone for longer than this the watch stops acting on what the phone last knew. */
        const val VALID_FOR_MS = 24 * 60 * 60_000L

        /** The curve is read every two and a half minutes for eight hours, as the watch expects it. */
        private const val INSULIN_SAMPLES = 193
        private const val INSULIN_SAMPLE_MS = 150_000L
    }
}
