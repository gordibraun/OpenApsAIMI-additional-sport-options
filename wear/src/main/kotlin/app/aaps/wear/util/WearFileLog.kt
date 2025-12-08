package app.aaps.wear.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Простенький файловый логгер для wear-модуля.
 * Без лишней магии: init(enable), d(), e(), close().
 * Пишет в: <externalFilesDir>/logs/watchface.log
 */
object WearFileLog {
    @Volatile private var enabled: Boolean = false
    @Volatile private var writer: FileWriter? = null
    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun init(context: Context, enable: Boolean) {
        enabled = enable
        closeLocked()
        if (!enabled) return
        try {
            val dir = File(context.getExternalFilesDir(null), "logs")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "watchface.log")
            writer = FileWriter(file, /*append=*/true)
            writeLineLocked("=== START ${Date()} ===")
        } catch (t: Throwable) {
            Log.e("WEAR_FILELOG", "init failed", t)
            enabled = false
            closeLocked()
        }
    }

    @JvmStatic
    fun d(tag: String, msg: String) {
        if (!enabled) return
        val line = "${ts.format(Date())} D/$tag: $msg"
        synchronized(this) { writeLineLocked(line) }
    }

    @JvmStatic
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (!enabled) return
        val base = "${ts.format(Date())} E/$tag: $msg"
        val line = if (tr != null) "$base ${tr.javaClass.simpleName}: ${tr.message}" else base
        synchronized(this) { writeLineLocked(line) }
    }

    @Synchronized
    fun close() {
        closeLocked()
    }

    // --- Внутреннее

    private fun writeLineLocked(line: String) {
        val w = writer ?: return
        try {
            w.append(line).append('\n')
            w.flush()
        } catch (_: Throwable) { /* ignore */ }
    }

    private fun closeLocked() {
        try { writer?.close() } catch (_: Throwable) { /* ignore */ }
        writer = null
    }
}