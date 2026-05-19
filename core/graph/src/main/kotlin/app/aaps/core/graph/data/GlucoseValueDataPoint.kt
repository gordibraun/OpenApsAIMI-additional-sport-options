package app.aaps.core.graph.data

import android.content.Context
import android.graphics.Paint
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil

class GlucoseValueDataPoint(
    val data: GV,
    private val profileUtil: ProfileUtil,
    private val rh: ResourceHelper,
    dateUtil: DateUtil,
    private val lowPredictionMarkMgdl: Double? = null
) : DataPointWithLabelInterface {

    private fun valueToUnits(units: GlucoseUnit): Double =
        if (units == GlucoseUnit.MGDL) data.value else data.value * Constants.MGDL_TO_MMOLL

    override fun getX(): Double = data.timestamp.toDouble()
    override fun getY(): Double = valueToUnits(profileUtil.units)

    override fun setY(y: Double) {}
    override val label: String = predictionLabel(dateUtil.timeString(data.timestamp), profileUtil.fromMgdlToStringInUnits(data.value))
    override val duration = 0L
    override val shape get() = if (isPrediction) Shape.PREDICTION else Shape.BG
    override val size = if (isPrediction) 1f else 0.6f
    override val paintStyle: Paint.Style = if (isPrediction) Paint.Style.FILL else Paint.Style.STROKE

    override fun color(context: Context?): Int {
        return when {
            isPendingPrediction         -> predictionColor(context)
            isPredictionBelowLowMark -> rh.gac(context, app.aaps.core.ui.R.attr.lowColor)
            isPrediction             -> predictionColor(context)
            else                     -> rh.gac(context, app.aaps.core.ui.R.attr.originalBgValueColor)
        }
    }

    private fun predictionColor(context: Context?): Int {
        return when (data.sourceSensor) {
            SourceSensor.IOB_PREDICTION   -> rh.gac(context, app.aaps.core.ui.R.attr.iobColor)
            SourceSensor.COB_PREDICTION   -> rh.gac(context, app.aaps.core.ui.R.attr.cobColor)
            SourceSensor.A_COB_PREDICTION -> -0x7f000001 and rh.gac(context, app.aaps.core.ui.R.attr.cobColor)
            SourceSensor.UAM_PREDICTION   -> rh.gac(context, app.aaps.core.ui.R.attr.uamColor)
            SourceSensor.ZT_PREDICTION    -> rh.gac(context, app.aaps.core.ui.R.attr.ztColor)
            SourceSensor.AIMI_FINAL_PREDICTION -> rh.gac(context, app.aaps.core.ui.R.attr.aimiFinalPredictionColor)
            SourceSensor.AIMI_ACTIVITY_ACTIVE_PREDICTION -> rh.gc(app.aaps.core.ui.R.color.aimi_activity_active_prediction)
            SourceSensor.AIMI_ACTIVITY_TAIL_PREDICTION -> rh.gc(app.aaps.core.ui.R.color.aimi_activity_tail_prediction)
            SourceSensor.AIMI_BEFORE_DECISION_PREDICTION -> rh.gac(context, app.aaps.core.ui.R.attr.carbsColor)
            SourceSensor.AIMI_MOMENTUM_SOFT_PREDICTION -> rh.gac(context, app.aaps.core.ui.R.attr.uamColor)
            SourceSensor.AIMI_FINAL_PREDICTION_STALE -> rh.gac(context, app.aaps.core.ui.R.attr.lowColor)
            else                          -> rh.gac(context, app.aaps.core.ui.R.attr.defaultTextColor)
        }
    }

    private fun predictionLabel(time: String, value: String): String =
        if (isPrediction) "${data.sourceSensor.text}: $time $value" else "$time $value"

    private val isPrediction: Boolean
        get() = data.sourceSensor == SourceSensor.IOB_PREDICTION ||
            data.sourceSensor == SourceSensor.COB_PREDICTION ||
            data.sourceSensor == SourceSensor.A_COB_PREDICTION ||
            data.sourceSensor == SourceSensor.UAM_PREDICTION ||
            data.sourceSensor == SourceSensor.ZT_PREDICTION ||
            data.sourceSensor == SourceSensor.AIMI_FINAL_PREDICTION ||
            data.sourceSensor == SourceSensor.AIMI_ACTIVITY_ACTIVE_PREDICTION ||
            data.sourceSensor == SourceSensor.AIMI_ACTIVITY_TAIL_PREDICTION ||
            data.sourceSensor == SourceSensor.AIMI_BEFORE_DECISION_PREDICTION ||
            data.sourceSensor == SourceSensor.AIMI_MOMENTUM_SOFT_PREDICTION ||
            data.sourceSensor == SourceSensor.AIMI_FINAL_PREDICTION_STALE

    private val isPredictionBelowLowMark: Boolean
        get() = isPrediction && lowPredictionMarkMgdl?.let { data.value < it } == true

    private val isPendingPrediction: Boolean
        get() = data.sourceSensor == SourceSensor.AIMI_BEFORE_DECISION_PREDICTION

}
