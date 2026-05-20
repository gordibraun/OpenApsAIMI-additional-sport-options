package app.aaps.workflow

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.events.EventAppInitialized
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.interfaces.rx.events.EventNewHistoryData
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.utils.receivers.DataWorkerStorage
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

class InvokeLoopWorker(
    context: Context,
    params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.Default) {

    @Inject lateinit var dataWorkerStorage: DataWorkerStorage
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var loop: Loop

    class InvokeLoopData(
        val cause: Event?
    )

    /*
     This method is triggered once autosens calculation has completed, so the LoopPlugin
     has current data to work with. However, autosens calculation can be triggered by multiple
     sources. A new BG triggers the normal loop run, while recent treatment/history-only changes
     trigger a forecast-only APS refresh so displayed AIMI lines do not stay stale until next BG.
     <p>
    */
    override suspend fun doWorkAndLog(): Result {

        val data = dataWorkerStorage.pickupObject(inputData.getLong(DataWorkerStorage.STORE_KEY, -1)) as InvokeLoopData?
            ?: return Result.failure(workDataOf("Error" to "missing input data"))

        val cause = data.cause
        val recentHistoryForecastOnly = cause is EventNewHistoryData &&
            cause.newestGlucoseValueTimestamp == null &&
            cause.oldDataTimestamp > System.currentTimeMillis() - T.hours(12).msecs()
        if (cause !is EventNewBG && cause !is EventAppInitialized && !recentHistoryForecastOnly) {
            return Result.success(workDataOf("Result" to "no calculation needed"))
        }
        val glucoseValue = iobCobCalculator.ads.actualBg() ?: return Result.success(workDataOf("Result" to "bg outdated"))
        if (recentHistoryForecastOnly) {
            loop.invoke("ForecastOnly for ${cause?.javaClass?.simpleName ?: "HistoryData"}", false)
            return Result.success(workDataOf("Result" to "forecast-only calculation"))
        }
        if (glucoseValue.timestamp <= loop.lastBgTriggeredRun) return Result.success(workDataOf("Result" to "already looped with that value"))
        loop.lastBgTriggeredRun = glucoseValue.timestamp
        loop.invoke("Calculation for $glucoseValue", true)
        return Result.success()
    }
}
