package app.aaps.plugins.aps.openAPSAIMI.food

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.WorkerParameters
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.objects.workflow.LoggingWorker
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Carries what the phone's record owes the server: photos waiting for an estimate, decisions
 * not yet told. Runs only with a network, retries on its own when the network is away, and tells
 * the owner with a notification when an estimate has come in and waits for their word.
 */
class FoodUploadWorker(
    context: Context,
    params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.IO) {

    @Inject lateinit var store: FoodPhotoStore
    @Inject lateinit var api: FoodApi

    override suspend fun doWorkAndLog(): Result {
        if (!api.configured()) {
            store.all().filter { it.needsUpload }.forEach { e ->
                store.update(e.id) { error = NOT_CONFIGURED }
            }
            return Result.success()
        }
        var retry = false
        for (entry in store.pending()) {
            if (entry.needsUpload) {
                store.markSending(entry.id, true)
                try {
                    val answer = api.estimate(entry)
                    val estimate = FoodEstimate.fromJson(answer.optJSONObject("estimate"))
                    val updated = store.update(entry.id) {
                        serverId = answer.optString("id").takeIf { it.isNotEmpty() }
                        this.estimate = estimate
                        if (status == FoodPhotoEntry.Status.QUEUED) status = FoodPhotoEntry.Status.ESTIMATED
                        error = if (estimate == null) "сервер не назвал углеводы — поправьте число сами" else null
                    }
                    if (updated?.status == FoodPhotoEntry.Status.ESTIMATED) notifyEstimate(updated)
                } catch (e: FoodApi.Failure) {
                    aapsLogger.warn(LTag.CORE, "food estimate failed for ${entry.id}: ${e.message}")
                    store.update(entry.id) {
                        error = e.message
                        if (!e.retryable && status == FoodPhotoEntry.Status.QUEUED) status = FoodPhotoEntry.Status.ERROR
                    }
                    if (e.retryable) retry = true
                    continue
                } catch (e: Exception) {
                    aapsLogger.error(LTag.CORE, "food estimate crashed for ${entry.id}", e)
                    store.update(entry.id) {
                        error = "${e.javaClass.simpleName}: ${e.message}"
                        if (status == FoodPhotoEntry.Status.QUEUED) status = FoodPhotoEntry.Status.ERROR
                    }
                    continue
                } finally {
                    store.markSending(entry.id, false)
                }
            }
            val fresh = store.get(entry.id) ?: continue
            if (!fresh.decisionPending) continue
            val serverId = fresh.serverId
            if (serverId == null) {
                // Declined before the server ever saw it: nothing to tell.
                if (!fresh.needsUpload) store.update(fresh.id) { decisionPending = false }
                continue
            }
            // eaten / declined are the owner's word; anything else (a corrected number, an undo) leaves the plate undecided.
            val decision = when (fresh.status) {
                FoodPhotoEntry.Status.EATEN    -> "eaten"
                FoodPhotoEntry.Status.DECLINED -> "declined"
                else                           -> "undecided"
            }
            try {
                api.decision(serverId, decision, if (decision == "declined") null else fresh.finalGrams ?: fresh.estimatedGrams.takeIf { decision == "eaten" }, "фото ${fresh.shortId}", if (decision == "declined") null else fresh.splitToEnter)
                store.update(fresh.id) { decisionPending = false }
            } catch (e: FoodApi.Failure) {
                aapsLogger.warn(LTag.CORE, "food decision not sent for ${fresh.id}: ${e.message}")
                if (e.retryable) retry = true else store.update(fresh.id) { decisionPending = false; error = e.message }
            }
        }
        return if (retry) Result.retry() else Result.success()
    }

    private fun notifyEstimate(entry: FoodPhotoEntry) {
        runCatching {
            val estimate = entry.estimate
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Еда по фото", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                applicationContext, 0,
                Intent().setClassName(applicationContext.packageName, "app.aaps.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val items = estimate?.items?.take(3)?.joinToString(", ") { it.name }.orEmpty()
            manager.notify(
                NOTIFICATION_ID_BASE + (entry.id.hashCode() and 0xffff),
                Notification.Builder(applicationContext, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_gallery)
                    .setContentTitle(estimate?.let { "≈ ${it.carbsG.roundToInt()} г углеводов на фото" } ?: "Оценка готова")
                    .setContentText(items.ifEmpty { "Откройте вкладку «Еда»" })
                    .setStyle(Notification.BigTextStyle().bigText((if (items.isNotEmpty()) "$items\n" else "") + "Во вкладке «Еда»: «Ем» подставит граммы в калькулятор, «Не ем» — отменит."))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    companion object {

        const val CHANNEL = "food-photo"
        const val NOT_CONFIGURED = "сервер не подключён: вставьте адрес и токен из команды /token бота в настройки «Еда по фото»"
        private const val NOTIFICATION_ID_BASE = 7400
    }
}
