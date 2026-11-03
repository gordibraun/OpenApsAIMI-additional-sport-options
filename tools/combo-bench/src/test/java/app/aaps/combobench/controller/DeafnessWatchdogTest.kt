package app.aaps.combobench.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The controller notices that it has gone deaf to a phone sitting next to it, and restarts once an hour at most. */
class DeafnessWatchdogTest {

    private val t0 = 1_800_000_000_000L
    private fun minutes(m: Int) = m * 60_000L
    private val dog = DeafnessWatchdog()

    private fun state(heard: Int, sends: Int, lastSend: Int, restart: Int = -1) = DeafnessWatchdog.State(
        lastHeardEpochMs = t0 + minutes(heard), startedEpochMs = t0 - minutes(60), sendsSinceAck = sends,
        lastSendEpochMs = t0 + minutes(lastSend), lastRestartEpochMs = if (restart < 0) 0L else t0 + minutes(restart)
    )

    @Test fun `deaf for twenty minutes with three unanswered sends and the phone nearby means a restart`() {
        val v = dog.check(t0 + minutes(25), state(heard = 0, sends = 3, lastSend = 20), phoneNearby = true, pumpBusy = false)
        assertTrue(v is DeafnessWatchdog.Verdict.Restart)
    }

    @Test fun `a phone that is away, or one heard recently, or sends that were answered, is not deafness`() {
        assertEquals(DeafnessWatchdog.Verdict.Fine, dog.check(t0 + minutes(25), state(0, 3, 20), phoneNearby = false, pumpBusy = false))
        assertEquals(DeafnessWatchdog.Verdict.Fine, dog.check(t0 + minutes(25), state(10, 3, 20), phoneNearby = true, pumpBusy = false))
        assertEquals(DeafnessWatchdog.Verdict.Fine, dog.check(t0 + minutes(25), state(0, 1, 20), phoneNearby = true, pumpBusy = false))
        // Nothing sent for a long while: we are not trying, so nothing can be concluded.
        assertEquals(DeafnessWatchdog.Verdict.Fine, dog.check(t0 + minutes(60), state(0, 5, 20), phoneNearby = true, pumpBusy = false))
    }

    @Test fun `a busy pump and a recent restart postpone the restart but are reported`() {
        assertTrue(dog.check(t0 + minutes(25), state(0, 3, 20), phoneNearby = true, pumpBusy = true) is DeafnessWatchdog.Verdict.DeafButWait)
        assertTrue(dog.check(t0 + minutes(25), state(0, 3, 20, restart = 0), phoneNearby = true, pumpBusy = false) is DeafnessWatchdog.Verdict.DeafButWait)
        assertTrue(dog.check(t0 + minutes(90), state(0, 3, 85, restart = 0), phoneNearby = true, pumpBusy = false) is DeafnessWatchdog.Verdict.Restart)
    }

    @Test fun `a fresh process that never heard the phone counts the quiet time from its own start`() {
        val fresh = DeafnessWatchdog.State(lastHeardEpochMs = 0L, startedEpochMs = t0, sendsSinceAck = 3, lastSendEpochMs = t0 + minutes(19), lastRestartEpochMs = 0L)
        assertEquals(DeafnessWatchdog.Verdict.Fine, dog.check(t0 + minutes(19), fresh, phoneNearby = true, pumpBusy = false))
        assertTrue(dog.check(t0 + minutes(21), fresh, phoneNearby = true, pumpBusy = false) is DeafnessWatchdog.Verdict.Restart)
    }
}
