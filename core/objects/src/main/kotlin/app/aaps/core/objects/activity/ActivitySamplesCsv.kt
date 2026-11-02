package app.aaps.core.objects.activity

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The phone's record of the watch's minute-by-minute pulse and steps: one CSV beside the AAPS
 * log, appended as the watch sends them (02.11.2026). A record for study and for the «Связь»
 * pack, not a dosing input: nothing in the loop reads it.
 */
object ActivitySamplesCsv {

    const val FILE_NAME = "activity-samples.csv"
    const val HEADER = "minuteEpochMs,localTime,heartRateBpm,steps"
    const val MINUTE_MS = 60_000L

    data class Row(val minuteEpochMs: Long, val heartRateBpm: Int?, val steps: Int?)

    /** Next to AndroidAPS.log, where adb reads it without run-as; null where the phone has no external files directory. */
    fun file(context: Context): File? = context.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }

    /**
     * Append the rows newer than the file's last minute, oldest first, and say how many were
     * written. A batch the watch sends twice therefore changes nothing; a minute that arrives
     * late, behind a newer one, is dropped rather than written out of order.
     */
    @Synchronized
    fun append(file: File, rows: List<Row>): Int {
        val last = lastMinute(file) ?: Long.MIN_VALUE
        val fresh = rows.filter { it.minuteEpochMs > last }.sortedBy { it.minuteEpochMs }.distinctBy { it.minuteEpochMs }
        if (fresh.isEmpty()) return 0
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val text = buildString {
            if (!file.exists() || file.length() == 0L) append(HEADER).append('\n')
            fresh.forEach { append(it.minuteEpochMs).append(',').append(time.format(Date(it.minuteEpochMs))).append(',').append(it.heartRateBpm ?: "").append(',').append(it.steps ?: "").append('\n') }
        }
        file.parentFile?.mkdirs()
        file.appendText(text)
        return fresh.size
    }

    /** The rows from [sinceEpochMs] on, oldest first; an unreadable line is skipped. */
    fun readSince(file: File, sinceEpochMs: Long): List<Row> {
        if (!file.exists()) return emptyList()
        return file.useLines { lines -> lines.mapNotNull { parse(it) }.filter { it.minuteEpochMs >= sinceEpochMs }.toList() }
    }

    /** The minute of the last row, read from the tail of the file; null for no rows. */
    fun lastMinute(file: File): Long? {
        if (!file.exists() || file.length() == 0L) return null
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            val want = minOf(size, 4096L)
            raf.seek(size - want)
            val bytes = ByteArray(want.toInt())
            raf.readFully(bytes)
            val tail = String(bytes, Charsets.UTF_8)
            return tail.split('\n').asReversed().firstNotNullOfOrNull { parse(it)?.minuteEpochMs }
        }
    }

    fun parse(line: String): Row? {
        val parts = line.trim().split(',')
        if (parts.size < 4) return null
        val minute = parts[0].toLongOrNull() ?: return null
        return Row(minute, parts[2].toIntOrNull(), parts[3].toIntOrNull())
    }
}
