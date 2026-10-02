package app.aaps.pump.combowatch.regulation

import app.aaps.core.data.iob.Iob
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.ICfg
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DecimalFormatter
import org.json.JSONObject
import java.text.DecimalFormat
import java.util.Locale

/** The shared algorithm files log through AAPS's logger; on the watch there is nowhere to log to. */
internal object QuietLogger : AAPSLogger {

    override fun debug(message: String) = Unit
    override fun debug(enable: Boolean, tag: LTag, message: String) = Unit
    override fun debug(tag: LTag, message: String) = Unit
    override fun debug(tag: LTag, accessor: () -> String) = Unit
    override fun debug(tag: LTag, format: String, vararg arguments: Any?) = Unit
    override fun warn(tag: LTag, message: String) = Unit
    override fun warn(tag: LTag, format: String, vararg arguments: Any?) = Unit
    override fun info(tag: LTag, message: String) = Unit
    override fun info(tag: LTag, format: String, vararg arguments: Any?) = Unit
    override fun error(tag: LTag, message: String) = Unit
    override fun error(tag: LTag, message: String, throwable: Throwable) = Unit
    override fun error(tag: LTag, format: String, vararg arguments: Any?) = Unit
    override fun error(message: String) = Unit
    override fun error(message: String, throwable: Throwable) = Unit
    override fun error(format: String, vararg arguments: Any?) = Unit
    override fun debug(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
    override fun info(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
    override fun warn(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
    override fun error(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
}

/** Number formatting for the reasons the shared files compose; only ever read by a developer. */
internal object PlainDecimals : DecimalFormatter {

    private fun format(value: Double, digits: Int) = String.format(Locale.US, "%.${digits}f", value)
    override fun to0Decimal(value: Double) = format(value, 0)
    override fun to0Decimal(value: Double, unit: String) = format(value, 0) + unit
    override fun to1Decimal(value: Double) = format(value, 1)
    override fun to1Decimal(value: Double, unit: String) = format(value, 1) + unit
    override fun to2Decimal(value: Double) = format(value, 2)
    override fun to2Decimal(value: Double, unit: String) = format(value, 2) + unit
    override fun to3Decimal(value: Double) = format(value, 3)
    override fun to3Decimal(value: Double, unit: String) = format(value, 3) + unit
    override fun toPumpSupportedBolus(value: Double, bolusStep: Double) = format(value, 2)
    override fun toPumpSupportedBolusWithUnits(value: Double, bolusStep: Double) = format(value, 2)
    override fun pumpSupportedBolusFormat(bolusStep: Double) = DecimalFormat("0.00")
}

/**
 * The phone's insulin, as a table.
 *
 * The phone samples the action curve of the insulin it is set up with and sends the samples in
 * the snapshot. This hands them to the shared code in the shape that code asks for, so the watch
 * computes with the phone's curve and never has one of its own.
 */
internal class SampledInsulin(private val remaining: List<Double>) : Insulin {

    override val id = Insulin.InsulinType.UNKNOWN
    override val friendlyName = "sampled from the phone"
    override val comment = ""
    override val dia: Double get() = horizonMinutes / 60.0
    override val peak = 0
    override val iCfg: ICfg get() = ICfg(friendlyName, (horizonMinutes * 60_000.0).toLong(), 0L)

    /** How far the table reaches, in whole five-minute steps. */
    val horizonMinutes: Int get() = ((remaining.size - 1) * SAMPLE_MINUTES / 5.0).toInt() * 5

    override fun iobCalcForTreatment(bolus: BS, time: Long, dia: Double): Iob {
        val position = (time - bolus.timestamp) / (SAMPLE_MINUTES * 60_000.0)
        val fraction = when {
            position <= 0.0                 -> 1.0
            position >= remaining.lastIndex -> 0.0
            else                            -> {
                val lower = position.toInt()
                remaining[lower] + (remaining[lower + 1] - remaining[lower]) * (position - lower)
            }
        }
        return Iob(iobContrib = bolus.amount * fraction.coerceIn(0.0, 1.0))
    }

    override fun configuration() = JSONObject()
    override fun applyConfiguration(configuration: JSONObject) = Unit

    companion object {

        /** The spacing of the samples, the same the shared code reads the curve at. */
        const val SAMPLE_MINUTES = 2.5

        /** The fewest samples that still cover the four hours a forecast looks ahead. */
        const val MIN_SAMPLES = 97
    }
}

/**
 * The forecast takes the algorithm's profile object but reads one number from it, the
 * carbohydrate ratio. Everything else is filled with values that mean nothing.
 */
internal fun profileForForecast(carbRatioGPerU: Double, sensitivityMgdlPerU: Double, targetMgdl: Double) = OapsProfileAimi(
    dia = 0.0, min_5m_carbimpact = 0.0, max_iob = 0.0, max_daily_basal = 0.0, max_basal = 0.0,
    min_bg = targetMgdl, max_bg = targetMgdl, target_bg = targetMgdl, carb_ratio = carbRatioGPerU, sens = sensitivityMgdlPerU,
    autosens_adjust_targets = false, max_daily_safety_multiplier = 0.0, current_basal_safety_multiplier = 0.0,
    high_temptarget_raises_sensitivity = false, low_temptarget_lowers_sensitivity = false,
    sensitivity_raises_target = false, resistance_lowers_target = false, adv_target_adjustments = false,
    exercise_mode = false, half_basal_exercise_target = 0, maxCOB = 0, skip_neutral_temps = false, remainingCarbsCap = 0,
    enableUAM = false, A52_risk_enable = false, SMBInterval = 0, enableSMB_with_COB = false, enableSMB_with_temptarget = false,
    allowSMB_with_high_temptarget = false, enableSMB_always = false, enableSMB_after_carbs = false,
    maxSMBBasalMinutes = 0, maxUAMSMBBasalMinutes = 0, bolus_increment = 0.1, carbsReqThreshold = 0, current_basal = 0.0,
    temptargetSet = false, autosens_max = 1.0, out_units = "mg/dl", lgsThreshold = null,
    variable_sens = sensitivityMgdlPerU, insulinDivisor = 0, TDD = 0.0, peakTime = 0.0,
    futureActivity = 0.0, sensorLagActivity = 0.0, historicActivity = 0.0, currentActivity = 0.0
)
