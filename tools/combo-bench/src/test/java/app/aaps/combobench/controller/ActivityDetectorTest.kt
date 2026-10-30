package app.aaps.combobench.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A walk or a sport session told from the pulse and from the fitness app's workout. */
class ActivityDetectorTest {

    private val t0 = 1_800_000_000_000L
    private fun minutes(m: Int) = m * 60_000L

    private fun pulse(detector: ActivityDetector, fromMinute: Int, toMinute: Int, bpm: Int): List<ActivityDetector.Signal> {
        val out = mutableListOf<ActivityDetector.Signal>()
        for (m in fromMinute..toMinute) out += detector.onHeartRate(t0 + minutes(m), bpm)
        return out
    }

    @Test fun `a pulse thirty above rest for eight minutes starts a walk that is asked about`() {
        val detector = ActivityDetector(restingDefaultBpm = 60)
        assertTrue(pulse(detector, 0, 7, 95).isEmpty())
        val signals = pulse(detector, 8, 8, 95)
        val started = signals.single() as ActivityDetector.Signal.Started
        assertEquals("WALK", started.mode)
        assertEquals(t0, started.startEpochMs)
        assertEquals(30, started.durationMinutes)
        assertEquals(false, started.sure)
    }

    @Test fun `a pulse forty-five above rest makes it sport, and the session ends after ten calm minutes`() {
        val detector = ActivityDetector(restingDefaultBpm = 60)
        val started = pulse(detector, 0, 8, 110).filterIsInstance<ActivityDetector.Signal.Started>().single()
        assertEquals("SPORT", started.mode)
        assertEquals(45, started.durationMinutes)
        // Still going at minute 30: nothing new until the provisional duration is nearly used up.
        assertTrue(pulse(detector, 9, 30, 110).isEmpty())
        val extended = pulse(detector, 31, 41, 110).filterIsInstance<ActivityDetector.Signal.Extended>()
        assertEquals(60, extended.last().durationMinutes)
        // Calm from minute 42: ended ten minutes later, lasting 45 minutes (42 rounded up to the five).
        val ended = pulse(detector, 42, 52, 70).filterIsInstance<ActivityDetector.Signal.Ended>().single()
        assertEquals(45, ended.durationMinutes)
        assertTrue(detector.session!!.ended)
    }

    @Test fun `the owner's "not an activity" takes the session away and quiets the pulse rule for half an hour`() {
        val detector = ActivityDetector(restingDefaultBpm = 60)
        pulse(detector, 0, 8, 100)
        detector.decline(t0 + minutes(9))
        assertNull(detector.session)
        assertTrue(pulse(detector, 10, 30, 100).isEmpty())
        val again = pulse(detector, 31, 50, 100).filterIsInstance<ActivityDetector.Signal.Started>()
        assertEquals(1, again.size)
    }

    @Test fun `a workout from the fitness app starts at once, names the kind, and ends when the app says so`() {
        val detector = ActivityDetector(restingDefaultBpm = 60)
        val started = detector.onWorkout(t0, "BIKING", running = true).single() as ActivityDetector.Signal.Started
        assertEquals("SPORT", started.mode)
        assertTrue(started.sure)
        // The pulse does not end a workout the app is tracking.
        assertTrue(pulse(detector, 1, 20, 65).isEmpty())
        val ended = detector.onWorkout(t0 + minutes(47), "BIKING", running = false).single() as ActivityDetector.Signal.Ended
        assertEquals(50, ended.durationMinutes)
        assertEquals("WALK", ActivityDetector.modeOf("WALKING"))
        assertEquals("SPORT", ActivityDetector.modeOf("SWIMMING_POOL"))
    }

    @Test fun `a long quiet without samples ends a pulse-started session`() {
        val detector = ActivityDetector(restingDefaultBpm = 60)
        pulse(detector, 0, 8, 100)
        val ended = detector.onTick(t0 + minutes(8 + 21)).filterIsInstance<ActivityDetector.Signal.Ended>().single()
        assertEquals(10, ended.durationMinutes)
    }

    @Test fun `a window's heart rate is summed up as average, maximum and minutes of effort`() {
        val detector = ActivityDetector(restingDefaultBpm = 60)
        pulse(detector, 0, 9, 80)     // ten easy minutes
        pulse(detector, 10, 29, 100)  // twenty minutes of effort (>= 90)
        pulse(detector, 30, 39, 110)  // ten hard minutes (>= 105)
        val profile = detector.profile(t0, t0 + minutes(39))!!
        assertEquals(40, profile.samples)
        assertEquals(97, profile.avgBpm)
        assertEquals(110, profile.maxBpm)
        assertEquals(30, profile.effortMinutes)
        assertEquals(10, profile.hardMinutes)
        assertTrue(profile.toTokens().startsWith("hr_avg=97 hr_max=110"))
        assertNull(detector.profile(t0 + minutes(100), t0 + minutes(110)))
    }

    @Test fun `state survives a restart through json`() {
        val detector = ActivityDetector(restingDefaultBpm = 60)
        pulse(detector, 0, 8, 110)
        val restored = ActivityDetector.fromJson(detector.toJson(), 60)
        assertEquals("SPORT", restored.session!!.mode)
        assertEquals(t0, restored.session!!.startEpochMs)
        assertEquals(detector.restingBpm, restored.restingBpm)
    }
}
