package app.aaps.plugins.aps.openAPSAIMI.pkpd

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** Configuration values controlling the online learning loop. */
data class PkPdLearningConfig(
    val bounds: PkPdBounds = PkPdBounds(),
    val minWindowMin: Int = 20,
    val maxWindowMin: Int = 180,
    val minIOBForLearningU: Double = 0.3,
    val maxRateChangeScale: Double = 1.0,
    val lr: Double = 0.02,
    val tailWeight: Double = 1.5
)

class AdaptivePkPdEstimator(
    private val kernel: Kernel = LogNormalKernel(),
    private val cfg: PkPdLearningConfig = PkPdLearningConfig(),
    initial: PkPdParams = PkPdParams(diaHrs = 4.0, peakMin = 75.0)
) {
    private val state = AtomicReference(initial)
    private var lastUpdateEpochMin: Long? = null

    fun params(): PkPdParams = state.get()

    /** Offline single-dose experiment only; total remaining IOB is not an original dose. */
    @Synchronized
    fun update(
        epochMin: Long,
        bg: Double,
        deltaMgDlPer5: Double,
        doseUnits: Double,
        carbsActiveG: Double,
        windowMin: Int,
        exerciseFlag: Boolean,
        isfMgDlPerU: Double = 45.0
    ) {
        if (epochMin <= 0 || lastUpdateEpochMin?.let { epochMin <= it } == true) return
        if (!listOf(bg, deltaMgDlPer5, doseUnits, carbsActiveG, isfMgDlPerU).all { it.isFinite() }) return
        if (bg <= 39.0 || doseUnits < 0.0 || carbsActiveG < 0.0 || isfMgDlPerU <= 0.0) return
        if (windowMin < cfg.minWindowMin || windowMin > cfg.maxWindowMin) return
        if (doseUnits < cfg.minIOBForLearningU) return
        if (carbsActiveG > 0.0) return
        if (exerciseFlag) return
        // RELAXED: Allow learning during slower rises (was 3.0, now 5.0)
        if (deltaMgDlPer5 > 5.0) return

        val p0 = state.get()
        val action = kernel.actionAt(windowMin.toDouble(), p0)
        if (!action.isFinite() || action < 0.0) return
        val expectedDropPer5 = action * doseUnits * isfMgDlPerU * 5.0
        val err = (-deltaMgDlPer5) - expectedDropPer5
        val tailFactor = 1.0 + cfg.tailWeight * max(0.0, (windowMin - p0.peakMin) / max(1.0, p0.peakMin))
        val diaAdj = cfg.lr * tailFactor * sign(err) * min(1.0, abs(err) / 10.0)
        val tpAdj = cfg.lr * 0.5 * sign(err) * min(1.0, abs(err) / 10.0)
        val anchorDia = 4.0
        val anchorTp  = 75.0
        val reg = 0.002   // régularisation très faible

        val diaAdjReg = diaAdj - reg * (p0.diaHrs - anchorDia)
        val tpAdjReg  = tpAdj  - reg * (p0.peakMin - anchorTp)

        val now = epochMin
        // One observation covers five minutes; gaps do not accrue a learning budget.
        val elapsedMin = lastUpdateEpochMin?.let { (now - it).coerceAtMost(5) } ?: 5L
        val dtDays = elapsedMin / (60.0 * 24.0)
        lastUpdateEpochMin = now
        val bounds = cfg.bounds
        val maxDiaStep = bounds.maxDiaChangePerDayH * cfg.maxRateChangeScale * dtDays
        val maxTpStep = bounds.maxPeakChangePerDayMin * cfg.maxRateChangeScale * dtDays
        val newDia = (p0.diaHrs + diaAdjReg)
            .coerceIn(p0.diaHrs - maxDiaStep, p0.diaHrs + maxDiaStep)
            .coerceIn(bounds.diaMinH, bounds.diaMaxH)
        val newTp = (p0.peakMin + tpAdjReg)
            .coerceIn(p0.peakMin - maxTpStep, p0.peakMin + maxTpStep)
            .coerceIn(bounds.peakMinMin, bounds.peakMinMax)
        state.set(PkPdParams(newDia, newTp))
    }

    fun iobResidualAt(minFromDose: Double): Double {
        val params = state.get()
        val cdfFrac = kernel.normalizedCdf(minFromDose, params)
        return (1.0 - cdfFrac).coerceIn(0.0, 1.0)
    }

    fun actionAt(minFromDose: Double): Double =
        kernel.actionAt(minFromDose, state.get()).coerceAtLeast(0.0)

    fun activityStateAt(minFromDose: Double): InsulinActivityState {
        val params = state.get()
        val window = kernel.activityWindow(params)
        val diaMin = window.diaMin
        val clampedMin = minFromDose.coerceIn(0.0, diaMin)
        val peakAction = kernel.actionAt(window.peakMin, params).coerceAtLeast(1e-6)
        val currentAction = if (clampedMin <= 0.0) 0.0 else kernel.actionAt(clampedMin, params) / peakAction
        val normalizedPosition = window.normalizedPosition(clampedMin)
        val postWindow = window.postWindowFraction(clampedMin)
        val minutesUntilOnset = (window.onsetMin - clampedMin).coerceAtLeast(0.0)
        val anticipation = if (minutesUntilOnset <= 0.0) 0.0 else exp(-minutesUntilOnset / 45.0)
        val stage = when {
            clampedMin < window.onsetMin - 1e-6 -> InsulinActivityStage.PRE_ONSET
            clampedMin < window.peakMin -> InsulinActivityStage.RISING
            clampedMin <= window.offsetMin -> InsulinActivityStage.PEAK
            clampedMin < diaMin - 1e-6 -> InsulinActivityStage.TAIL
            else -> InsulinActivityStage.EXHAUSTED
        }
        return InsulinActivityState(
            window = window,
            relativeActivity = currentAction.coerceIn(0.0, 1.0),
            normalizedPosition = normalizedPosition.coerceIn(0.0, 1.0),
            postWindowFraction = postWindow.coerceIn(0.0, 1.0),
            anticipationWeight = anticipation.coerceIn(0.0, 1.0),
            minutesUntilOnset = minutesUntilOnset,
            stage = stage
        )
    }
}

object IsfTddProvider {
    @Volatile
    private var isf: Double = 45.0

    fun isfTdd(): Double = isf

    fun set(valueMgDlPerU: Double) {
        isf = valueMgDlPerU
    }
}
