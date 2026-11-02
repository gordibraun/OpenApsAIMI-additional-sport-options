package app.aaps.combobench.controller

import app.aaps.pump.combowatch.protocol.ActivitySample
import app.aaps.pump.combowatch.protocol.ActivitySamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Minute buckets of pulse and steps, handed out once when complete, and their text form for the phone. */
class ActivitySampleLogTest {

    private val t0 = 1_800_000_000_000L
    private fun minutes(m: Int) = m * 60_000L

    @Test fun `heart rate samples of a minute average, and a minute is handed out once it is five minutes old`() {
        val log = ActivitySampleLog()
        log.heartRate(t0 + 10_000, 70)
        log.heartRate(t0 + 40_000, 75)
        log.heartRate(t0 + minutes(1), 90)
        assertTrue(log.takeCompleted(t0 + minutes(4)).isEmpty())
        val taken = log.takeCompleted(t0 + minutes(6))
        assertEquals(listOf(ActivitySample(t0, 73, null), ActivitySample(t0 + minutes(1), 90, null)), taken)
        assertTrue(log.takeCompleted(t0 + minutes(6)).isEmpty())
        assertEquals(0, log.pendingMinutes)
    }

    @Test fun `a step interval is spread evenly over its minutes, the remainder on the first ones`() {
        val log = ActivitySampleLog()
        log.steps(t0, t0 + minutes(2), 10)
        val taken = log.takeCompleted(t0 + minutes(10))
        assertEquals(listOf(4, 3, 3), taken.map { it.steps })
        assertEquals(listOf(t0, t0 + minutes(1), t0 + minutes(2)), taken.map { it.minuteEpochMs })
    }

    @Test fun `daily totals give the steps walked since the last report, and a smaller total is a new day`() {
        val log = ActivitySampleLog()
        log.stepsDaily(t0, 1000)                    // first report: nothing to compare with
        log.stepsDaily(t0 + minutes(3), 1030)       // 30 steps over minutes 1..3
        log.stepsDaily(t0 + minutes(4), 12)         // midnight passed: 12 steps since
        val taken = log.takeCompleted(t0 + minutes(20))
        assertEquals(listOf(t0 + minutes(1), t0 + minutes(2), t0 + minutes(3), t0 + minutes(4)), taken.map { it.minuteEpochMs })
        assertEquals(listOf(10, 10, 10, 12), taken.map { it.steps })
    }

    @Test fun `steps that arrive for a minute already handed out land in the oldest open minute`() {
        val log = ActivitySampleLog()
        log.heartRate(t0, 80)
        assertEquals(1, log.takeCompleted(t0 + minutes(6)).size)
        log.steps(t0, t0, 40)                       // late for minute 0, which is gone
        log.heartRate(t0 + minutes(7), 82)
        val taken = log.takeCompleted(t0 + minutes(30))
        assertEquals(t0 + minutes(1), taken.first().minuteEpochMs)
        assertEquals(40, taken.first().steps)
        assertNull(taken.first().heartRateBpm)
    }

    @Test fun `the buckets survive a restart through json`() {
        val log = ActivitySampleLog()
        log.heartRate(t0, 66)
        log.stepsDaily(t0, 500)
        log.lastFlushEpochMs = t0
        val restored = ActivitySampleLog.fromJson(log.toJson())
        restored.stepsDaily(t0 + minutes(1), 520)
        val taken = restored.takeCompleted(t0 + minutes(10))
        assertEquals(listOf(ActivitySample(t0, 66, null), ActivitySample(t0 + minutes(1), null, 20)), taken)
        assertEquals(t0, restored.lastFlushEpochMs)
    }

    @Test fun `the text form round-trips, with gaps as empty minutes and unknown figures as dashes`() {
        val samples = listOf(ActivitySample(t0, 72, 0), ActivitySample(t0 + minutes(1), null, 35), ActivitySample(t0 + minutes(3), 80, null))
        val text = ActivitySamples.encode(samples)
        assertEquals("v1;$t0;72/0,-/35,-/-,80/-", text)
        assertEquals(samples, ActivitySamples.decode(text))
        assertTrue(ActivitySamples.decode("v2;$t0;1/1").isEmpty())
        assertTrue(ActivitySamples.decode(null).isEmpty())
        assertTrue(ActivitySamples.decode("garbage").isEmpty())
        assertEquals("v1;0;", ActivitySamples.encode(emptyList()))
    }

    @Test fun `pieces break at long gaps and at two hours`() {
        val samples = (0 until 150).map { ActivitySample(t0 + minutes(it), 70, 0) } +
            listOf(ActivitySample(t0 + minutes(300), 70, 0), ActivitySample(t0 + minutes(301), 71, 1))
        val pieces = ActivitySamples.pieces(samples)
        assertEquals(3, pieces.size)
        assertEquals(120, pieces[0].size)
        assertEquals(30, pieces[1].size)
        assertEquals(2, pieces[2].size)
        assertEquals(t0 + minutes(300), pieces[2].first().minuteEpochMs)
    }
}
