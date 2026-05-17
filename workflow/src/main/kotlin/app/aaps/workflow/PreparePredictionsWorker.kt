package app.aaps.workflow

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.time.T
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.graph.data.DataPointWithLabelInterface
import app.aaps.core.graph.data.GlucoseValueDataPoint
import app.aaps.core.graph.data.PointsWithLabelGraphSeries
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.OverviewMenus
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.target
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.utils.receivers.DataWorkerStorage
import kotlinx.coroutines.Dispatchers
import java.util.Calendar
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PreparePredictionsWorker(
    context: Context,
    params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.Default) {

    @Inject lateinit var overviewData: OverviewData
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var config: Config
    @Inject lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Inject lateinit var loop: Loop
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var overviewMenus: OverviewMenus
    @Inject lateinit var dataWorkerStorage: DataWorkerStorage
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var preferences: Preferences

    class PreparePredictionsData(
        val overviewData: OverviewData
    )

    override suspend fun doWorkAndLog(): Result {
        val data = dataWorkerStorage.pickupObject(inputData.getLong(DataWorkerStorage.STORE_KEY, -1)) as PreparePredictionsData?
            ?: return Result.failure(workDataOf("Error" to "missing input data"))

        val apsResult = resolveBestApsResult()
        val predictionsAreStaleByBg = apsResult?.let { isPredictionStale(it) } == true
        val predictionsAreStaleByContext = apsResult?.let { isPredictionContextStale(it) } == true
        val predictionsAreStaleByCarbs = apsResult?.let { isPredictionStaleByCarbs(it) } == true
        val predictionsAreStale = predictionsAreStaleByBg || predictionsAreStaleByContext || predictionsAreStaleByCarbs
        val hidePredictions = predictionsAreStaleByBg
        val markPredictionPendingRecalc = !hidePredictions && (predictionsAreStaleByContext || predictionsAreStaleByCarbs)
        val predictionsAvailable =
            apsResult?.predictionsAsGv?.isNotEmpty() == true ||
                (if (config.APS) loop.lastRun?.request?.hasPredictions == true else config.AAPSCLIENT)
        val menuChartSettings = overviewMenus.setting
        // align to hours
        val calendar = Calendar.getInstance().also {
            it.timeInMillis = System.currentTimeMillis()
            it[Calendar.MILLISECOND] = 0
            it[Calendar.SECOND] = 0
            it[Calendar.MINUTE] = 0
            it.add(Calendar.HOUR, 1)
        }
        val graphCurrentEndTime = calendar.timeInMillis + 100000 // little bit more to avoid wrong rounding - GraphView specific
        if (predictionsAvailable && apsResult != null && menuChartSettings[0][OverviewMenus.CharType.PRE.ordinal]) {
            val latestPredictionTime = latestVisiblePredictionTime(apsResult)
            val predictionEndTime = max(graphCurrentEndTime, latestPredictionTime + T.mins(5).msecs())
            val selectedRangeMs = T.hours(data.overviewData.rangeToDisplay.toLong()).msecs()
            val futureWindowMs = max(0L, predictionEndTime - graphCurrentEndTime)
            val minHistoryMs = min(T.hours(2).msecs(), selectedRangeMs)
            val historyWindowMs = max(minHistoryMs, selectedRangeMs - futureWindowMs)

            data.overviewData.toTime = graphCurrentEndTime
            data.overviewData.fromTime = data.overviewData.toTime - historyWindowMs
            data.overviewData.endTime = predictionEndTime

            aapsLogger.debug(
                LTag.WORKER,
                "AIMI prediction graph axis shows full forecast: history=${historyWindowMs / T.hours(1).msecs()}h " +
                    "future=${futureWindowMs / T.hours(1).msecs()}h " +
                    "latest=${dateUtil.dateAndTimeString(latestPredictionTime)} " +
                    "end=${dateUtil.dateAndTimeString(predictionEndTime)}"
            )
        } else {
            data.overviewData.toTime = graphCurrentEndTime
            data.overviewData.fromTime = data.overviewData.toTime - T.hours(data.overviewData.rangeToDisplay.toLong()).msecs()
            data.overviewData.endTime = data.overviewData.toTime
        }

        val bgListArray: MutableList<DataPointWithLabelInterface> = ArrayList()
        val finalAimiListArray: MutableList<DataPointWithLabelInterface> = ArrayList()
        val predictionValues = mutableListOf<app.aaps.core.data.model.GV>()
        val finalAimiPredictionValues = mutableListOf<app.aaps.core.data.model.GV>()
        val lowPredictionMarkMgdl = profileUtil.convertToMgdl(preferences.get(UnitDoubleKey.OverviewLowMark), profileUtil.units)
        val displayPredictionValues = apsResult?.predictionsAsGv?.let { values ->
            if (markPredictionPendingRecalc) {
                values.map { bg ->
                    when (bg.sourceSensor) {
                        SourceSensor.AIMI_FINAL_PREDICTION,
                        SourceSensor.AIMI_FINAL_PREDICTION_STALE -> bg.copy(sourceSensor = SourceSensor.AIMI_BEFORE_DECISION_PREDICTION)

                        else -> bg
                    }
                }
            } else values
        }
        val predictions: MutableList<GlucoseValueDataPoint>? = if (hidePredictions) {
            mutableListOf()
        } else displayPredictionValues
            ?.map { bg ->
                GlucoseValueDataPoint(bg, profileUtil, rh, dateUtil, lowPredictionMarkMgdl)
            }
            ?.toMutableList()
        if (predictions != null) {
            predictions.sortWith { o1: GlucoseValueDataPoint, o2: GlucoseValueDataPoint -> o1.x.compareTo(o2.x) }
            for (prediction in predictions) {
                if (prediction.data.value < 40) continue
                if (prediction.data.sourceSensor == SourceSensor.AIMI_FINAL_PREDICTION ||
                    prediction.data.sourceSensor == SourceSensor.AIMI_BEFORE_DECISION_PREDICTION ||
                    prediction.data.sourceSensor == SourceSensor.AIMI_FINAL_PREDICTION_STALE
                ) {
                    finalAimiListArray.add(prediction)
                    finalAimiPredictionValues.add(prediction.data)
                }
                else {
                    bgListArray.add(prediction)
                    predictionValues.add(prediction.data)
                }
            }
        }
        data.overviewData.predictionsGraphSeries = PointsWithLabelGraphSeries(Array(bgListArray.size) { i -> bgListArray[i] })
        data.overviewData.finalAimiPredictionGraphSeries = PointsWithLabelGraphSeries(Array(finalAimiListArray.size) { i -> finalAimiListArray[i] })
        data.overviewData.predictionValues = predictionValues
        data.overviewData.finalAimiPredictionValues = finalAimiPredictionValues
        val firstFinalPoint = finalAimiListArray.firstOrNull() as? GlucoseValueDataPoint
        val lastFinalPoint = finalAimiListArray.lastOrNull() as? GlucoseValueDataPoint
        aapsLogger.debug(
            LTag.WORKER,
            "AIMI final prediction prepared: points=${finalAimiListArray.size} stale=$predictionsAreStale " +
                "hide=$hidePredictions pendingRecalc=$markPredictionPendingRecalc contextStale=$predictionsAreStaleByContext carbsStale=$predictionsAreStaleByCarbs" +
                (firstFinalPoint?.let { " first=${dateUtil.dateAndTimeString(it.data.timestamp)} ${profileUtil.fromMgdlToStringInUnits(it.data.value)}" } ?: "") +
                (lastFinalPoint?.let { " last=${dateUtil.dateAndTimeString(it.data.timestamp)} ${profileUtil.fromMgdlToStringInUnits(it.data.value)}" } ?: "")
        )
        return Result.success()
    }

    private fun isPredictionStale(apsResult: app.aaps.core.interfaces.aps.APSResult): Boolean {
        val actualBg = currentBgSnapshot() ?: return true
        val predictionBgTimestamp = apsResult.glucoseStatus?.date ?: apsResult.date
        val predictionBgValue = apsResult.glucoseStatus?.glucose
        val bgAgeGap = actualBg.timestamp - predictionBgTimestamp
        val bgValueGap = predictionBgValue?.let { kotlin.math.abs(actualBg.value - it) } ?: 0.0
        val isStaleByTime = bgAgeGap > T.mins(6).msecs()
        val isStaleByValue = bgAgeGap > T.mins(1).msecs() && bgValueGap >= 10.0

        if (isStaleByTime || isStaleByValue) {
            aapsLogger.debug(
                LTag.WORKER,
                "Hiding stale AIMI prediction by BG: aps=${dateUtil.dateAndTimeString(apsResult.date)} " +
                    "bgSnapshot=${dateUtil.dateAndTimeString(predictionBgTimestamp)} ${profileUtil.fromMgdlToStringInUnits(predictionBgValue ?: 0.0)} " +
                    "currentBg=${dateUtil.dateAndTimeString(actualBg.timestamp)} ${profileUtil.fromMgdlToStringInUnits(actualBg.value)} " +
                    "source=${actualBg.source} " +
                    "ageGapMs=$bgAgeGap valueGapMgdl=$bgValueGap"
            )
        }
        return isStaleByTime || isStaleByValue
    }

    private fun isPredictionStaleByCarbs(apsResult: app.aaps.core.interfaces.aps.APSResult): Boolean {
        val resultTime = apsResult.date
        val resultCob = apsResult.mealData?.mealCOB
        val currentCob = try {
            activePlugin.activeIobCobCalculator.getCobInfo("AIMI prediction stale by COB").displayCob
        } catch (_: Throwable) {
            null
        }
        if (resultCob != null && currentCob != null && abs(currentCob - resultCob) >= 5.0) {
            aapsLogger.debug(
                LTag.WORKER,
                "AIMI prediction pending recalculation by COB mismatch: result=${dateUtil.dateAndTimeString(resultTime)} " +
                    "resultCOB=${"%.1f".format(resultCob)} currentCOB=${"%.1f".format(currentCob)}"
            )
            return true
        }

        val changedCarbs = try {
            persistenceLayer.getCarbsFromTime(resultTime - T.hours(24).msecs(), false)
                .blockingGet()
                .firstOrNull { carbs ->
                    carbs.isValid && (carbs.dateCreated > resultTime || carbs.timestamp > resultTime)
                }
        } catch (_: Throwable) {
            persistenceLayer.getNewestCarbs()
                ?.takeIf { carbs -> carbs.isValid && (carbs.dateCreated > resultTime || carbs.timestamp > resultTime) }
        } ?: return false

        aapsLogger.debug(
            LTag.WORKER,
            "AIMI prediction pending recalculation by carbs: result=${dateUtil.dateAndTimeString(resultTime)} " +
                "carbs=${changedCarbs.amount}g event=${dateUtil.dateAndTimeString(changedCarbs.timestamp)} " +
                "created=${if (changedCarbs.dateCreated > 0) dateUtil.dateAndTimeString(changedCarbs.dateCreated) else "unknown"}"
        )
        return true
    }

    private data class BgSnapshot(val timestamp: Long, val value: Double, val source: String)

    private fun currentBgSnapshot(): BgSnapshot? {
        val adsBg = activePlugin.activeIobCobCalculator.ads.actualBg()
            ?.let { BgSnapshot(it.timestamp, it.recalculated, "autosens") }
        val dbBg = persistenceLayer.getLastGlucoseValue()
            ?.let { BgSnapshot(it.timestamp, it.value, "db") }
        return listOfNotNull(adsBg, dbBg).maxByOrNull { it.timestamp }
    }

    private fun isPredictionContextStale(apsResult: app.aaps.core.interfaces.aps.APSResult): Boolean {
        val aimiProfile = apsResult.oapsProfileAimi ?: return false
        val now = dateUtil.now()
        val currentProfile = profileFunction.getProfile(now) ?: return true
        val currentProfileSwitch = persistenceLayer.getEffectiveProfileSwitchActiveAt(now)
        val currentPercentage = currentProfileSwitch?.originalPercentage ?: currentProfile.percentage
        val currentTempTarget = persistenceLayer.getTemporaryTargetActiveAt(now)
        val currentTempTargetSet = currentTempTarget != null
        val currentTarget = currentTempTarget?.target() ?: currentProfile.getTargetMgdl()

        val tempTargetMismatch = aimiProfile.temptargetSet != currentTempTargetSet
        val targetMismatch = abs(currentTarget - aimiProfile.target_bg) >= 2.0
        val profileMismatch = currentPercentage != aimiProfile.profile_percentage

        if (tempTargetMismatch || targetMismatch || profileMismatch) {
            aapsLogger.debug(
                LTag.WORKER,
                "AIMI prediction pending recalculation by context: resultTarget=${aimiProfile.target_bg} currentTarget=$currentTarget " +
                    "resultTempTarget=${aimiProfile.temptargetSet} currentTempTarget=$currentTempTargetSet " +
                    "resultProfile=${aimiProfile.profile_percentage}% currentProfile=$currentPercentage%"
            )
        }

        return tempTargetMismatch || targetMismatch || profileMismatch
    }

    private fun resolveBestApsResult(): app.aaps.core.interfaces.aps.APSResult? {
        val now = dateUtil.now()
        val actualBgTimestamp = activePlugin.activeIobCobCalculator.ads.actualBg()?.timestamp ?: now
        val inMemoryResult = if (config.APS) loop.lastRun?.constraintsProcessed else processedDeviceStatusData.getAPSResult()
        val persistedResult = persistenceLayer.getApsResultCloseTo(actualBgTimestamp)
        val persistedRecentResult = persistenceLayer.getApsResults(now - T.mins(10).msecs(), now)
            .asSequence()
            .filter { it.predictionsAsGv?.isNotEmpty() == true }
            .maxByOrNull { predictionTimestamp(it) }

        val bestResult = listOfNotNull(inMemoryResult, persistedResult, persistedRecentResult)
            .maxByOrNull { predictionTimestamp(it) }

        if (bestResult === persistedResult && persistedResult != null) {
            aapsLogger.debug(
                LTag.WORKER,
                "Using persisted APSResult for immediate prediction restore: ${dateUtil.dateAndTimeString(persistedResult.date)}"
            )
        }
        if (bestResult === persistedRecentResult && persistedRecentResult != null && bestResult !== persistedResult) {
            aapsLogger.debug(
                LTag.WORKER,
                "Using recent persisted APSResult fallback for immediate prediction restore: ${dateUtil.dateAndTimeString(persistedRecentResult.date)}"
            )
        }
        return bestResult
    }

    private fun predictionTimestamp(apsResult: app.aaps.core.interfaces.aps.APSResult): Long =
        apsResult.glucoseStatus?.date ?: apsResult.date

    private fun latestVisiblePredictionTime(apsResult: app.aaps.core.interfaces.aps.APSResult): Long =
        max(
            apsResult.latestPredictionsTime,
            apsResult.predictionsAsGv.maxOfOrNull { it.timestamp } ?: apsResult.latestPredictionsTime
        )

}
