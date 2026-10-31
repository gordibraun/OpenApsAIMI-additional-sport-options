package app.aaps.plugins.aps.openAPSAIMI.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.work.WorkerParameters
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.objects.workflow.LoggingWorker
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/**
 * Carries the owner's reports to the server when there is a network, and brings Claude's replies
 * back. Runs after every «Отправить», every quarter of an hour, and when the watch drops a pack.
 */
class BridgeUploadWorker(
    context: Context,
    params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.IO) {

    @Inject lateinit var store: BridgeStore
    @Inject lateinit var api: BridgeApi
    @Inject lateinit var rxBus: RxBus

    override suspend fun doWorkAndLog(): Result {
        store.importWatchInbox()
        if (!api.configured()) {
            store.pending().forEach { (_, m) -> store.markError(m.clientId, BridgeStore.NOT_CONFIGURED, giveUp = false) }
            rxBus.send(EventBridgeChanged())
            return Result.success()
        }
        var retry = false
        for ((thread, message) in store.pending()) {
            val payload = JSONObject()
                .put("text", message.text).put("category", message.category).put("source", message.source)
                .put("client_id", message.clientId).put("created_at", message.at)
                .put("thread_id", thread.threadId ?: JSONObject.NULL)
            message.packPath?.let { path -> runCatching { JSONObject(File(path).readText()) }.getOrNull()?.let { payload.put("pack", it) } }
            val attachments = JSONArray()
            for (a in message.attachments) {
                val f = File(a.path)
                if (!f.exists() || f.length() > MAX_ATTACHMENT_BYTES) continue
                attachments.put(JSONObject().put("name", a.name).put("mime", a.mime).put("base64", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)))
            }
            payload.put("attachments", attachments)
            try {
                val answer = api.report(payload)
                store.markSent(message.clientId, answer.getString("thread_id"), answer.getString("message_id"))
                aapsLogger.info(LTag.CORE, "bridge: report ${message.clientId} delivered as ${answer.optString("message_id")} in ${answer.optString("thread_id")}")
            } catch (e: BridgeApi.Failure) {
                aapsLogger.warn(LTag.CORE, "bridge: report ${message.clientId} not delivered: ${e.message}")
                store.markError(message.clientId, e.message ?: "ошибка", giveUp = !e.retryable)
                if (e.retryable) retry = true
            } catch (e: Exception) {
                aapsLogger.error(LTag.CORE, "bridge: report ${message.clientId} crashed", e)
                store.markError(message.clientId, "${e.javaClass.simpleName}: ${e.message}", giveUp = true)
            }
        }
        var newReplies = 0
        var lastReply: String? = null
        for (thread in store.threads().filter { it.threadId != null }.take(10)) {
            try {
                val messages = api.thread(thread.threadId!!, thread.lastServerId)
                val added = store.merge(thread.threadId!!, messages)
                if (added > 0) {
                    newReplies += added
                    lastReply = store.thread(thread.localId)?.messages?.lastOrNull { it.role == "assistant" }?.text
                }
            } catch (e: BridgeApi.Failure) {
                if (e.retryable) retry = true
                aapsLogger.warn(LTag.CORE, "bridge: thread ${thread.threadId} not read: ${e.message}")
            }
        }
        if (newReplies > 0) notifyReply(lastReply.orEmpty())
        rxBus.send(EventBridgeChanged())
        return if (retry) Result.retry() else Result.success()
    }

    private fun notifyReply(text: String) {
        runCatching {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Связь с Claude", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                applicationContext, 0,
                Intent().setClassName(applicationContext.packageName, "app.aaps.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.notify(
                NOTIFICATION_ID,
                Notification.Builder(applicationContext, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setContentTitle("Claude ответил")
                    .setContentText(text.take(120))
                    .setStyle(Notification.BigTextStyle().bigText(text.take(1500)))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    companion object {

        const val CHANNEL = "bridge"
        private const val NOTIFICATION_ID = 7500
        private const val MAX_ATTACHMENT_BYTES = 6L * 1024 * 1024
    }
}
