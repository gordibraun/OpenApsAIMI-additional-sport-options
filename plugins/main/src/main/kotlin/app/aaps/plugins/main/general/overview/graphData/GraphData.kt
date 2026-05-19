package app.aaps.plugins.main.general.overview.graphData

import android.content.Context
import android.graphics.DashPathEffect
import android.graphics.Paint
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.time.T
import app.aaps.core.graph.data.AreaGraphSeries
import app.aaps.core.graph.data.BarGraphSeries
import app.aaps.core.graph.data.BolusDataPoint
import app.aaps.core.graph.data.DataPointWithLabelInterface
import app.aaps.core.graph.data.DeviationDataPoint
import app.aaps.core.graph.data.DoubleDataPoint
import app.aaps.core.graph.data.EffectiveProfileSwitchDataPoint
import app.aaps.core.graph.data.FixedLineGraphSeries
import app.aaps.core.graph.data.GlucoseValueDataPoint
import app.aaps.core.graph.data.LineGraphSeries
import app.aaps.core.graph.data.PointsWithLabelGraphSeries
import app.aaps.core.graph.data.ScaledDataPoint
import app.aaps.core.graph.data.TimeAsXAxisLabelFormatter
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.toast.ToastUtils
import com.jjoe64.graphview.GraphView
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.Series
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max

@Suppress("UNCHECKED_CAST")
class GraphData @Inject constructor(
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val preferences: Preferences,
    private val rh: ResourceHelper,
    private val dateUtil: DateUtil
) {

    private var maxY = Double.MIN_VALUE
    private var minY = Double.MAX_VALUE
    private val units: GlucoseUnit get() = profileFunction.getUnits()
    private val series: MutableList<Series<*>> = ArrayList()

    private lateinit var graph: GraphView
    private lateinit var overviewData: OverviewData

    fun with(graph: GraphView, overviewData: OverviewData): GraphData = this.also {
        it.graph = graph
        it.overviewData = overviewData
    }

    fun addBucketedData() {
        addSeries(overviewData.bucketedGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    fun addBgReadings(addPredictions: Boolean, context: Context?) {
        maxY = if (overviewData.bgReadingsArray.isEmpty()) {
            if (units == GlucoseUnit.MGDL) 180.0 else 10.0
        } else overviewData.maxBgValue
        minY = 0.0
        val bgSeries = overviewData.bgReadingGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>
        val finalAimiPoints = finalAimiPredictionValuesForDisplay()
            .map { GlucoseValueDataPoint(it, profileUtil, rh, dateUtil, lowPredictionMarkMgdl()) }
        val finalAimiSeries = PointsWithLabelGraphSeries(Array(finalAimiPoints.size) { i -> finalAimiPoints[i] })
        val predictionSeries = overviewData.predictionsGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>

        maxY = max(maxY, finalAimiSeries.highestValueY)
        if (addPredictions) maxY = max(maxY, predictionSeries.highestValueY)

        addSeries(bgSeries)
        addSeries(finalAimiSeries)
        if (addPredictions) addSeries(predictionSeries)
        bgSeries.setOnDataPointTapListener { _, dataPoint ->
            if (dataPoint is GlucoseValueDataPoint) ToastUtils.infoToast(context, dataPoint.label)
        }
        finalAimiSeries.setOnDataPointTapListener { _, dataPoint ->
            if (dataPoint is GlucoseValueDataPoint) ToastUtils.infoToast(context, dataPoint.label)
        }
    }

    fun addFullPredictionReadings(context: Context?, targetMgdl: Double? = null, fromTime: Long? = null, toTime: Long? = null) {
        val lowMarkMgdl = lowPredictionMarkMgdl()
        val predictionPoints = overviewData.predictionValues.map { GlucoseValueDataPoint(it, profileUtil, rh, dateUtil, lowMarkMgdl) }
        val finalAimiPoints = finalAimiPredictionValuesForDisplay().map { GlucoseValueDataPoint(it, profileUtil, rh, dateUtil, lowMarkMgdl) }
        val allPoints = predictionPoints + finalAimiPoints
        if (allPoints.isEmpty()) {
            maxY = if (units == GlucoseUnit.MGDL) 180.0 else 10.0
            minY = 0.0
            return
        }

        val padding = if (units == GlucoseUnit.MGDL) 10.0 else 0.5
        val targetY = targetMgdl?.takeIf { it.isFinite() && it > 0.0 }?.let { profileUtil.fromMgdlToUnits(it) }
        maxY = maxOf(allPoints.maxOf { it.y }, preferences.get(UnitDoubleKey.OverviewHighMark), targetY ?: Double.MIN_VALUE) + padding
        minY = maxOf(0.0, minOf(allPoints.minOf { it.y }, preferences.get(UnitDoubleKey.OverviewLowMark), targetY ?: Double.MAX_VALUE) - padding)

        targetY?.let {
            addFullPredictionTargetLine(
                fromTime ?: allPoints.minOf { point -> point.x }.toLong(),
                toTime ?: allPoints.maxOf { point -> point.x }.toLong(),
                it
            )
        }

        val predictionSeries = PointsWithLabelGraphSeries(Array(predictionPoints.size) { i -> predictionPoints[i] })
        val finalAimiSeries = PointsWithLabelGraphSeries(Array(finalAimiPoints.size) { i -> finalAimiPoints[i] })
        addSeries(predictionSeries)
        addSeries(finalAimiSeries)
        predictionSeries.setOnDataPointTapListener { _, dataPoint ->
            if (dataPoint is GlucoseValueDataPoint) ToastUtils.infoToast(context, dataPoint.label)
        }
        finalAimiSeries.setOnDataPointTapListener { _, dataPoint ->
            if (dataPoint is GlucoseValueDataPoint) ToastUtils.infoToast(context, dataPoint.label)
        }
    }

    private fun lowPredictionMarkMgdl(): Double =
        profileUtil.convertToMgdl(preferences.get(UnitDoubleKey.OverviewLowMark), units)

    private fun finalAimiPredictionValuesForDisplay(): List<GV> {
        val values = overviewData.finalAimiPredictionValues
        if (values.isEmpty()) return values
        val latestBg = overviewData.bgReadingsArray
            .filter { it.value.isFinite() && it.value > 0.0 }
            .maxByOrNull { it.timestamp }
            ?: return values
        val finalLine = values.filter { it.sourceSensor.isFinalAimiDisplaySource() }
        if (finalLine.isEmpty()) return values
        val closest = finalLine.minByOrNull { abs(it.timestamp - latestBg.timestamp) } ?: return values
        val anchorMismatch =
            latestBg.timestamp >= closest.timestamp &&
                abs(closest.timestamp - latestBg.timestamp) <= T.mins(6).msecs() &&
                abs(closest.value - latestBg.value) >= 12.0
        return if (anchorMismatch) emptyList() else values
    }

    private fun SourceSensor.isFinalAimiDisplaySource(): Boolean =
        this == SourceSensor.AIMI_FINAL_PREDICTION ||
            this == SourceSensor.AIMI_ACTIVITY_ACTIVE_PREDICTION ||
            this == SourceSensor.AIMI_ACTIVITY_TAIL_PREDICTION

    private fun addFullPredictionTargetLine(fromTime: Long, toTime: Long, targetInUnits: Double) {
        val targetPoints = arrayOf(
            DataPoint(fromTime.toDouble(), targetInUnits),
            DataPoint(toTime.toDouble(), targetInUnits)
        )
        addSeries(LineGraphSeries(targetPoints).also {
            it.isDrawDataPoints = false
            it.setCustomPaint(Paint().also { paint ->
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                paint.pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
                paint.color = rh.gac(graph.context, app.aaps.core.ui.R.attr.tempTargetBackgroundColor)
            })
        })
    }

    fun addInRangeArea(fromTime: Long, toTime: Long, lowLine: Double, highLine: Double) {
        val inRangeAreaDataPoints = arrayOf(
            DoubleDataPoint(fromTime.toDouble(), lowLine, highLine),
            DoubleDataPoint(toTime.toDouble(), lowLine, highLine)
        )
        addSeries(AreaGraphSeries(inRangeAreaDataPoints).also {
            it.color = 0
            it.isDrawBackground = true
            it.backgroundColor = rh.gac(graph.context, app.aaps.core.ui.R.attr.inRangeBackground)
        })
    }

    fun addBasals() {
        overviewData.basalScale.multiplier = 1.0 // get unscaled Y-values for max calculation
        var maxBasalValue =
            maxOf(0.1, (overviewData.baseBasalGraphSeries as LineGraphSeries<ScaledDataPoint>).highestValueY, (overviewData.tempBasalGraphSeries as LineGraphSeries<ScaledDataPoint>).highestValueY)
        maxBasalValue =
            maxOf(
                maxBasalValue,
                (overviewData.basalLineGraphSeries as LineGraphSeries<ScaledDataPoint>).highestValueY,
                (overviewData.absoluteBasalGraphSeries as LineGraphSeries<ScaledDataPoint>).highestValueY
            )
        addSeries(overviewData.baseBasalGraphSeries as LineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.tempBasalGraphSeries as LineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.basalLineGraphSeries as LineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.absoluteBasalGraphSeries as LineGraphSeries<ScaledDataPoint>)
        maxY = max(maxY, preferences.get(UnitDoubleKey.OverviewHighMark))
        val scale = preferences.get(UnitDoubleKey.OverviewLowMark) / maxY / 1.2
        overviewData.basalScale.multiplier = maxY * scale / maxBasalValue
    }

    fun addTargetLine() {
        addSeries(overviewData.temporaryTargetSeries as LineGraphSeries<DataPoint>)
    }

    fun addRunningModes() {
        addSeries(overviewData.runningModesSeries as PointsWithLabelGraphSeries<DataPoint>)
    }

    fun addTreatments(context: Context?) {
        maxY = maxOf(maxY, overviewData.maxTreatmentsValue)
        addSeries(overviewData.treatmentsSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
        (overviewData.treatmentsSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>).setOnDataPointTapListener { _, dataPoint ->
            if (dataPoint is BolusDataPoint) ToastUtils.infoToast(context, dataPoint.label)
        }
    }

    fun addEps(context: Context?, scale: Double) {
        addSeries(overviewData.epsSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
        (overviewData.epsSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>).setOnDataPointTapListener { _, dataPoint ->
            if (dataPoint is EffectiveProfileSwitchDataPoint) ToastUtils.infoToast(context, dataPoint.data.originalCustomizedName)
        }
        overviewData.epsScale.multiplier = maxY * scale / overviewData.maxEpsValue
    }

    fun addTherapyEvents() {
        maxY = maxOf(maxY, overviewData.maxTherapyEventValue)
        addSeries(overviewData.therapyEventSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    fun addActivity(scale: Double) {
        addSeries(overviewData.activitySeries as FixedLineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.activityPredictionSeries as FixedLineGraphSeries<ScaledDataPoint>)
        overviewData.actScale.multiplier = maxY * scale / overviewData.maxIAValue
    }

    //Function below show -BGI to be able to compare curves with deviations
    fun addMinusBGI(useForScale: Boolean, scale: Double) {
        if (useForScale) {
            maxY = overviewData.maxBGIValue
            minY = -overviewData.maxBGIValue
        }
        overviewData.bgiScale.multiplier = maxY * scale / overviewData.maxBGIValue
        addSeries(overviewData.minusBgiSeries as FixedLineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.minusBgiHistSeries as FixedLineGraphSeries<ScaledDataPoint>)
    }

    // scale in % of vertical size (like 0.3)
    fun addIob(useForScale: Boolean, scale: Double) {
        if (useForScale) {
            maxY = overviewData.maxIobValueFound
            minY = -overviewData.maxIobValueFound
        }
        overviewData.iobScale.multiplier = maxY * scale / overviewData.maxIobValueFound
        addSeries(overviewData.iobSeries as FixedLineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.iobPredictions1Series as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
        //addSeries(overviewData.iobPredictions2Series)
    }

    // scale in % of vertical size (like 0.3)
    fun addAbsIob(useForScale: Boolean, scale: Double) {
        if (useForScale) {
            maxY = overviewData.maxIobValueFound
            minY = -overviewData.maxIobValueFound
        }
        overviewData.iobScale.multiplier = maxY * scale / overviewData.maxIobValueFound
        addSeries(overviewData.absIobSeries as FixedLineGraphSeries<ScaledDataPoint>)
    }

    // scale in % of vertical size (like 0.3)
    fun addCob(useForScale: Boolean, scale: Double) {
        if (useForScale) {
            maxY = overviewData.maxCobValueFound
            minY = -overviewData.maxCobValueFound
        }
        overviewData.cobScale.multiplier = maxY * scale / overviewData.maxCobValueFound
        addSeries(overviewData.cobSeries as FixedLineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.cobMinFailOverSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    // scale in % of vertical size (like 0.3)
    fun addDeviations(useForScale: Boolean, scale: Double) {
        if (useForScale) {
            maxY = overviewData.maxDevValueFound
            minY = -maxY
        }
        overviewData.devScale.multiplier = maxY * scale / overviewData.maxDevValueFound
        addSeries(overviewData.deviationsSeries as BarGraphSeries<DeviationDataPoint>)
    }

    // scale in % of vertical size (like 0.3)
    fun addRatio(useForScale: Boolean, scale: Double) {
        if (useForScale) {
            maxY = 100.0 + max(overviewData.maxRatioValueFound, abs(overviewData.minRatioValueFound))
            minY = 100.0 - max(overviewData.maxRatioValueFound, abs(overviewData.minRatioValueFound))
            overviewData.ratioScale.multiplier = 1.0
            overviewData.ratioScale.shift = 100.0
        } else {
            overviewData.ratioScale.multiplier = maxY * scale / max(overviewData.maxRatioValueFound, abs(overviewData.minRatioValueFound))
            overviewData.ratioScale.shift = 0.0
        }
        addSeries(overviewData.ratioSeries as LineGraphSeries<ScaledDataPoint>)
    }

    // scale in % of vertical size (like 0.3)
    fun addDeviationSlope(useForScale: Boolean, scale: Double, isRatioScale: Boolean = false) {
        if (useForScale) {
            maxY = max(overviewData.maxFromMaxValueFound, overviewData.maxFromMinValueFound)
            minY = -maxY
        }
        var graphMaxY = maxY
        if (isRatioScale) {
            graphMaxY = maxY - 100.0
            overviewData.dsMinScale.shift = 100.0
            overviewData.dsMaxScale.shift = 100.0
        } else {
            overviewData.dsMinScale.shift = 0.0
            overviewData.dsMaxScale.shift = 0.0
        }
        overviewData.dsMaxScale.multiplier = graphMaxY * scale / overviewData.maxFromMaxValueFound
        overviewData.dsMinScale.multiplier = graphMaxY * scale / overviewData.maxFromMinValueFound
        addSeries(overviewData.dsMaxSeries as LineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.dsMinSeries as LineGraphSeries<ScaledDataPoint>)
    }

    // scale in % of vertical size (like 0.3)
    fun addNowLine(now: Long) {
        val nowPoints = arrayOf(
            DataPoint(now.toDouble(), 0.0),
            DataPoint(now.toDouble(), maxY)
        )
        addSeries(LineGraphSeries(nowPoints).also {
            it.isDrawDataPoints = false
            // custom paint to make a dotted line
            it.setCustomPaint(Paint().also { paint ->
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.pathEffect = DashPathEffect(floatArrayOf(10f, 20f), 0f)
                paint.color = rh.gac(graph.context, app.aaps.core.ui.R.attr.dotLineColor)
            })
        })
    }

    // scale in % of vertical size (like 0.3)
    fun addVarSens(useForScale: Boolean, scale: Double) {
        if (useForScale) {
            maxY = overviewData.maxVarSensValueFound
            minY = overviewData.minVarSensValueFound
        }
        overviewData.varSensScale.multiplier = maxY * scale / overviewData.maxVarSensValueFound
        addSeries(overviewData.varSensSeries as LineGraphSeries<ScaledDataPoint>)
    }

    fun setNumVerticalLabels() {
        graph.gridLabelRenderer.numVerticalLabels = max(3, if (units == GlucoseUnit.MGDL) (maxY / 40 + 1).toInt() else (maxY / 2 + 1).toInt())
    }

    fun formatAxis(fromTime: Long, endTime: Long) {
        graph.viewport.setMaxX(endTime.toDouble())
        graph.viewport.setMinX(fromTime.toDouble())
        graph.viewport.isXAxisBoundsManual = true
        graph.gridLabelRenderer.labelFormatter = TimeAsXAxisLabelFormatter("HH")
        graph.gridLabelRenderer.numHorizontalLabels = 7 // only 7 because of the space
    }

    private fun addSeries(s: Series<*>) = series.add(s)

    fun performUpdate() {
        // clear old data - use removeAllSeries() to properly detach GraphView from series
        graph.removeAllSeries()

        // add pre calculated series
        for (s in series) {
            if (!s.isEmpty) {
                s.onGraphViewAttached(graph)
                graph.series.add(s)
            }
        }
        var step = 1.0
        if (maxY < 1) step = 0.1
        graph.viewport.setMaxY(Round.ceilTo(maxY, step))
        graph.viewport.setMinY(Round.floorTo(minY, step))
        graph.viewport.isYAxisBoundsManual = true

        // draw it
        graph.onDataChanged(false, false)
        series.clear()
    }

    fun addHeartRate(useForScale: Boolean, scale: Double) {
        val maxHR = (overviewData.heartRateGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>).highestValueY
        if (useForScale) {
            minY = 30.0
            maxY = maxHR
        }
        addSeries(overviewData.heartRateGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
        overviewData.heartRateScale.multiplier = maxY * scale / maxHR
    }

    fun addSteps(useForScale: Boolean, scale: Double) {
        val maxSteps = (overviewData.stepsCountGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>).highestValueY
        if (useForScale) {
            minY = 0.0
            maxY = maxSteps
        }
        addSeries(overviewData.stepsCountGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
        overviewData.stepsForScale.multiplier = maxY * scale / maxSteps
    }
}
