package app.aaps.plugins.aps.openAPSAIMI.food

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * The phone's own record of photographed plates: photos and entries on disk, so that nothing is
 * lost when the app restarts or the network is away. Every change goes out as
 * [EventFoodPhotoChanged] and, when the server has to hear of it, wakes [FoodUploadWorker].
 */
@Singleton
class FoodPhotoStore @Inject constructor(
    private val context: Context,
    private val rxBus: RxBus,
    private val aapsLogger: AAPSLogger
) {

    private val dir: File get() = File(context.filesDir, "food").also { it.mkdirs() }
    private val file: File get() = File(dir, "entries.json")
    private val lock = Any()
    private var cache: MutableList<FoodPhotoEntry>? = null
    /** Photos on their way to the server right now; not persisted, shown as «Оцениваю…». */
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun load(): MutableList<FoodPhotoEntry> = cache ?: run {
        val loaded = runCatching { if (file.exists()) FoodPhotoEntry.listFromJson(JSONObject(file.readText())) else emptyList() }
            .onFailure { aapsLogger.error(LTag.CORE, "food entries unreadable", it) }
            .getOrDefault(emptyList()).toMutableList()
        cache = loaded
        loaded
    }

    private fun save(entries: List<FoodPhotoEntry>) {
        val tmp = File(dir, "entries.json.tmp")
        tmp.writeText(FoodPhotoEntry.listToJson(entries).toString())
        tmp.renameTo(file)
    }

    /** Newest first. */
    fun all(): List<FoodPhotoEntry> = synchronized(lock) { load().sortedByDescending { it.atEpochMs } }

    fun get(id: String): FoodPhotoEntry? = synchronized(lock) { load().firstOrNull { it.id == id } }

    fun sending(id: String): Boolean = id in inFlight

    /** A new photo file for the camera to write into. */
    fun newPhotoFile(): Pair<String, File> {
        val id = UUID.randomUUID().toString().replace("-", "").takeLast(12)
        return id to File(dir, "$id.jpg")
    }

    /**
     * Keep a photo the owner just took or picked. The image is shrunk to [MAX_SIDE_PX] on its longer
     * side so that an upload is a few hundred kilobytes, not several megabytes.
     */
    fun add(id: String, photo: File, caption: String): FoodPhotoEntry {
        shrink(photo)
        val entry = FoodPhotoEntry(id = id, atEpochMs = System.currentTimeMillis(), photoPath = photo.absolutePath, caption = caption.trim())
        synchronized(lock) {
            val entries = load()
            entries += entry
            save(entries)
        }
        changed(sync = true)
        return entry
    }

    fun update(id: String, change: FoodPhotoEntry.() -> Unit): FoodPhotoEntry? {
        val entry = synchronized(lock) {
            val entries = load()
            val e = entries.firstOrNull { it.id == id } ?: return null
            e.change()
            save(entries)
            e
        }
        changed(sync = entry.needsUpload || entry.decisionPending)
        return entry
    }

    /** The owner's word on a photo; the server hears of it when the network allows. */
    fun decide(id: String, status: FoodPhotoEntry.Status, grams: Double? = null): FoodPhotoEntry? = update(id) {
        this.status = status
        if (grams != null) finalGrams = grams
        decidedAtEpochMs = System.currentTimeMillis()
        decisionPending = true
        error = null
    }

    fun markSending(id: String, sending: Boolean) {
        if (sending) inFlight += id else inFlight -= id
        rxBus.send(EventFoodPhotoChanged())
    }

    fun remove(id: String) {
        synchronized(lock) {
            val entries = load()
            entries.firstOrNull { it.id == id }?.let { runCatching { File(it.photoPath).delete() } }
            entries.removeAll { it.id == id }
            save(entries)
        }
        changed(sync = false)
    }

    /** What the server still has to hear: photos not yet estimated, decisions not yet told. */
    fun pending(): List<FoodPhotoEntry> = all().filter { it.needsUpload || it.decisionPending }

    private fun changed(sync: Boolean) {
        rxBus.send(EventFoodPhotoChanged())
        if (sync) enqueueSync()
    }

    fun enqueueSync() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequest.Builder(FoodUploadWorker::class.java)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
        )
    }

    /** A small bitmap of the photo for the list, or null when the file is gone. */
    fun thumbnail(entry: FoodPhotoEntry, sidePx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(entry.photoPath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sidePx) sample *= 2
        BitmapFactory.decodeFile(entry.photoPath, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun shrink(photo: File) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(photo.absolutePath, bounds)
            val longer = max(bounds.outWidth, bounds.outHeight)
            if (longer <= MAX_SIDE_PX) return
            var sample = 1
            while (longer / (sample * 2) >= MAX_SIDE_PX) sample *= 2
            val bitmap = BitmapFactory.decodeFile(photo.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
            val scale = MAX_SIDE_PX.toFloat() / max(bitmap.width, bitmap.height)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
            photo.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }.onFailure { aapsLogger.warn(LTag.CORE, "food photo not shrunk: ${it.javaClass.simpleName}") }
    }

    companion object {

        const val WORK_NAME = "food-photo-sync"
        const val MAX_SIDE_PX = 1280
    }
}
