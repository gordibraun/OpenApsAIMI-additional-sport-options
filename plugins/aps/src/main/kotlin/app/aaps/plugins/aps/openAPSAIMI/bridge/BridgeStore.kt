package app.aaps.plugins.aps.openAPSAIMI.bridge

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The owner's conversations with Claude, kept on the phone: what was written, with which photos
 * and which data pack, whether it has left the phone yet, and what came back. The record is the
 * truth for the screen; the server is where the on-call reads it. Nothing is lost without a
 * network: a report waits here until [BridgeUploadWorker] gets through.
 */
@Singleton
class BridgeStore @Inject constructor(private val context: Context) {

    class Attachment(val name: String, val path: String, val mime: String) {

        fun toJson(): JSONObject = JSONObject().put("name", name).put("path", path).put("mime", mime)

        companion object {

            fun fromJson(j: JSONObject) = Attachment(j.getString("name"), j.getString("path"), j.optString("mime", "image/jpeg"))
        }
    }

    class Message(
        val clientId: String,
        var serverId: String?,
        val role: String,
        val at: Long,
        var text: String,
        val category: String,
        val source: String,
        val attachments: List<Attachment>,
        val packPath: String?,
        var pending: Boolean,
        var error: String?,
        val actions: List<String>
    ) {

        fun toJson(): JSONObject = JSONObject()
            .put("clientId", clientId).put("serverId", serverId ?: JSONObject.NULL).put("role", role).put("at", at).put("text", text)
            .put("category", category).put("source", source)
            .put("attachments", JSONArray().also { arr -> attachments.forEach { arr.put(it.toJson()) } })
            .put("packPath", packPath ?: JSONObject.NULL).put("pending", pending).put("error", error ?: JSONObject.NULL)
            .put("actions", JSONArray(actions))

        companion object {

            fun fromJson(j: JSONObject) = Message(
                clientId = j.getString("clientId"), serverId = j.optString("serverId").takeIf { it.isNotEmpty() && it != "null" },
                role = j.optString("role", "user"), at = j.optLong("at"), text = j.optString("text"), category = j.optString("category", "other"),
                source = j.optString("source", "phone"),
                attachments = j.optJSONArray("attachments")?.let { arr -> List(arr.length()) { Attachment.fromJson(arr.getJSONObject(it)) } } ?: emptyList(),
                packPath = j.optString("packPath").takeIf { it.isNotEmpty() && it != "null" }, pending = j.optBoolean("pending", false),
                error = j.optString("error").takeIf { it.isNotEmpty() && it != "null" },
                actions = j.optJSONArray("actions")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList()
            )
        }
    }

    class Thread(val localId: String, var threadId: String?, var title: String, val category: String, val messages: MutableList<Message>) {

        val lastAt: Long get() = messages.maxOfOrNull { it.at } ?: 0L
        val awaitingReply: Boolean get() = messages.lastOrNull()?.role == "user"
        val lastServerId: String? get() = messages.mapNotNull { it.serverId }.maxOrNull()

        fun toJson(): JSONObject = JSONObject()
            .put("localId", localId).put("threadId", threadId ?: JSONObject.NULL).put("title", title).put("category", category)
            .put("messages", JSONArray().also { arr -> messages.forEach { arr.put(it.toJson()) } })

        companion object {

            fun fromJson(j: JSONObject) = Thread(
                localId = j.getString("localId"), threadId = j.optString("threadId").takeIf { it.isNotEmpty() && it != "null" },
                title = j.optString("title"), category = j.optString("category", "other"),
                messages = j.optJSONArray("messages")?.let { arr -> MutableList(arr.length()) { Message.fromJson(arr.getJSONObject(it)) } } ?: mutableListOf()
            )
        }
    }

    private val dir: File get() = File(context.filesDir, "bridge").also { it.mkdirs() }
    private val file: File get() = File(dir, "threads.json")
    val inboxDir: File get() = File(dir, "inbox").also { it.mkdirs() }
    fun newPhotoFile(): File = File(File(dir, "photos").also { it.mkdirs() }, "p${System.currentTimeMillis()}.jpg")
    private fun newPackFile(): File = File(File(dir, "packs").also { it.mkdirs() }, "pack${System.currentTimeMillis()}.json")

    private var cache: MutableList<Thread>? = null

    @Synchronized private fun load(): MutableList<Thread> {
        cache?.let { return it }
        val list = runCatching {
            val arr = JSONArray(file.readText())
            MutableList(arr.length()) { Thread.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(mutableListOf())
        cache = list
        return list
    }

    @Synchronized private fun save() {
        val list = cache ?: return
        val tmp = File(dir, "threads.json.tmp")
        tmp.writeText(JSONArray().also { arr -> list.forEach { arr.put(it.toJson()) } }.toString())
        tmp.renameTo(file)
    }

    @Synchronized fun threads(): List<Thread> = load().sortedByDescending { it.lastAt }
    @Synchronized fun thread(localId: String): Thread? = load().firstOrNull { it.localId == localId }
    @Synchronized fun pendingCount(): Int = load().sumOf { t -> t.messages.count { it.pending } }

    /** A new report from the owner, or a follow-up in an existing thread; it waits for the network. */
    @Synchronized fun newReport(category: String, text: String, attachments: List<Attachment>, pack: JSONObject?, source: String, inThread: String?): Thread {
        val list = load()
        val thread = inThread?.let { id -> list.firstOrNull { it.localId == id } }
            ?: Thread("L${System.currentTimeMillis()}", null, text.take(80).ifBlank { categoryTitle(category) }, category, mutableListOf()).also { list.add(it) }
        val packPath = pack?.let { p -> newPackFile().also { it.writeText(p.toString()) }.absolutePath }
        thread.messages.add(
            Message(
                clientId = "c${System.currentTimeMillis()}-${(1000..9999).random()}", serverId = null, role = "user", at = System.currentTimeMillis(),
                text = text, category = category, source = source, attachments = attachments, packPath = packPath, pending = true, error = null, actions = emptyList()
            )
        )
        save()
        return thread
    }

    @Synchronized fun pending(): List<Pair<Thread, Message>> =
        load().flatMap { t -> t.messages.filter { it.pending && it.role == "user" }.map { t to it } }.sortedBy { it.second.at }

    @Synchronized fun markSent(clientId: String, threadId: String, serverId: String) {
        for (t in load()) {
            val m = t.messages.firstOrNull { it.clientId == clientId } ?: continue
            m.pending = false; m.serverId = serverId; m.error = null
            t.threadId = threadId
            m.packPath?.let { runCatching { File(it).delete() } }
            save(); return
        }
    }

    @Synchronized fun markError(clientId: String, error: String, giveUp: Boolean) {
        for (t in load()) {
            val m = t.messages.firstOrNull { it.clientId == clientId } ?: continue
            m.error = error
            if (giveUp) m.pending = false
            save(); return
        }
    }

    /** Messages the server holds for a thread that this phone has not seen yet; returns how many replies are new. */
    @Synchronized fun merge(threadId: String, serverMessages: JSONArray): Int {
        val thread = load().firstOrNull { it.threadId == threadId } ?: return 0
        var newReplies = 0
        for (i in 0 until serverMessages.length()) {
            val j = serverMessages.getJSONObject(i)
            val id = j.optString("id")
            if (id.isEmpty()) continue
            if (thread.messages.any { it.serverId == id || it.clientId == j.optString("client_id") }) continue
            val role = j.optString("role", "user")
            if (role == "user" && j.optString("source") == "phone") continue
            thread.messages.add(
                Message(
                    clientId = id, serverId = id, role = role, at = j.optLong("at"), text = j.optString("text"), category = thread.category,
                    source = if (role == "assistant") "claude" else j.optString("source", "telegram"), attachments = emptyList(), packPath = null,
                    pending = false, error = null, actions = j.optJSONArray("actions")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList()
                )
            )
            if (role == "assistant") newReplies += 1
        }
        if (newReplies > 0 || serverMessages.length() > 0) {
            thread.messages.sortBy { it.at }
            save()
        }
        return newReplies
    }

    /** Packs the watch dropped into the inbox (its «Сообщить Claude») become reports of their own. */
    @Synchronized fun importWatchInbox(): Int {
        var imported = 0
        for (f in inboxDir.listFiles { file -> file.name.endsWith(".json") }.orEmpty().sortedBy { it.lastModified() }) {
            val json = runCatching { JSONObject(f.readText()) }.getOrNull()
            if (json != null) {
                val chip = json.optString("chip", "other")
                newReport(
                    category = chip, text = "Сигнал с часов: ${categoryTitle(chip).lowercase()}. Данные часов приложены.",
                    attachments = emptyList(), pack = JSONObject().put("watch", json), source = "watch", inThread = null
                )
                imported += 1
            }
            f.delete()
        }
        return imported
    }

    fun scheduleUpload() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UPLOAD_WORK, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequest.Builder(BridgeUploadWorker::class.java)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .build()
        )
    }

    fun schedulePoll() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            POLL_WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequest.Builder(BridgeUploadWorker::class.java, 15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )
    }

    companion object {

        private const val UPLOAD_WORK = "bridge-upload"
        private const val POLL_WORK = "bridge-poll"
        const val NOT_CONFIGURED = "сервер не настроен: адрес и токен в настройках «Еда по фото»"

        val CATEGORIES = listOf("glucose" to "Сахар", "dose" to "Доза", "pump" to "Помпа", "link" to "Связь", "other" to "Другое")
        fun categoryTitle(key: String): String = CATEGORIES.firstOrNull { it.first == key }?.second ?: key.replaceFirstChar { it.uppercase() }
    }
}
