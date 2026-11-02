package app.aaps.core.objects.activity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The phone's CSV of the watch's pulse and steps: appended in order, never twice, read back by time. */
class ActivitySamplesCsvTest {

    @TempDir lateinit var dir: File
    private val t0 = 1_800_000_000_000L
    private fun minutes(m: Int) = m * 60_000L

    @Test fun `rows are appended once, with a header, and read back from a time`() {
        val file = File(dir, ActivitySamplesCsv.FILE_NAME)
        assertNull(ActivitySamplesCsv.lastMinute(file))
        val first = listOf(ActivitySamplesCsv.Row(t0, 70, 0), ActivitySamplesCsv.Row(t0 + minutes(1), null, 40))
        assertEquals(2, ActivitySamplesCsv.append(file, first))
        // The same batch again, plus one new minute: only the new one is written.
        assertEquals(1, ActivitySamplesCsv.append(file, first + ActivitySamplesCsv.Row(t0 + minutes(2), 90, null)))
        val lines = file.readLines()
        assertEquals(ActivitySamplesCsv.HEADER, lines.first())
        assertEquals(4, lines.size)
        assertTrue(lines[1].startsWith("$t0,") && lines[1].endsWith(",70,0"))
        assertTrue(lines[2].endsWith(",,40"))
        assertTrue(lines[3].endsWith(",90,"))
        assertEquals(t0 + minutes(2), ActivitySamplesCsv.lastMinute(file))
        assertEquals(listOf(ActivitySamplesCsv.Row(t0 + minutes(1), null, 40), ActivitySamplesCsv.Row(t0 + minutes(2), 90, null)), ActivitySamplesCsv.readSince(file, t0 + minutes(1)))
    }

    @Test fun `a minute older than the last written one is not written out of order`() {
        val file = File(dir, ActivitySamplesCsv.FILE_NAME)
        ActivitySamplesCsv.append(file, listOf(ActivitySamplesCsv.Row(t0 + minutes(5), 75, 10)))
        assertEquals(0, ActivitySamplesCsv.append(file, listOf(ActivitySamplesCsv.Row(t0 + minutes(4), 74, 10))))
        assertEquals(1, ActivitySamplesCsv.readSince(file, 0).size)
    }
}
