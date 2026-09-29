package app.aaps.combobench

import app.aaps.combobench.TherapySessionPolicy.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TherapySessionPolicyTest {
    private fun rejected(block: () -> Unit) = assertTrue(runCatching(block).isFailure)

    @Test fun onlyStopStatusAndResumeExist() {
        assertEquals(listOf(Kind.STATUS, Kind.STOP, Kind.RESUME), Kind.entries)
        assertNull(Kind.STATUS.percentage)
        assertEquals(0, Kind.STOP.percentage)
        assertEquals(100, Kind.RESUME.percentage)
    }

    @Test fun stopDurationsAreTheComboQuarterHours() {
        for (minutes in TherapySessionPolicy.STOP_DURATIONS) {
            assertEquals(0, minutes % 15)
            assertEquals(0, TherapySessionPolicy.request(Kind.STOP, minutes).percentage)
        }
        rejected { TherapySessionPolicy.request(Kind.STOP, 0) }
        rejected { TherapySessionPolicy.request(Kind.STOP, 10) }
        rejected { TherapySessionPolicy.request(Kind.STOP, 90) }
        rejected { TherapySessionPolicy.request(Kind.STOP, 24 * 60) }
    }

    @Test fun statusAndResumeTakeNoDuration() {
        assertEquals(0, TherapySessionPolicy.request(Kind.STATUS).durationMinutes)
        assertEquals(0, TherapySessionPolicy.request(Kind.RESUME).durationMinutes)
        rejected { TherapySessionPolicy.request(Kind.STATUS, 15) }
        rejected { TherapySessionPolicy.request(Kind.RESUME, 15) }
    }

    @Test fun lockedStateAllowsOnlyStatusReconciliation() {
        assertTrue(TherapySessionPolicy.mayStart(Kind.STOP, active = false, locked = false, otherWorkActive = false))
        assertTrue(TherapySessionPolicy.mayStart(Kind.STATUS, active = false, locked = true, otherWorkActive = false))
        assertFalse(TherapySessionPolicy.mayStart(Kind.STOP, active = false, locked = true, otherWorkActive = false))
        assertFalse(TherapySessionPolicy.mayStart(Kind.RESUME, active = false, locked = true, otherWorkActive = false))
    }

    @Test fun nothingStartsWhileAnySessionRuns() {
        for (kind in Kind.entries) {
            assertFalse(TherapySessionPolicy.mayStart(kind, active = true, locked = false, otherWorkActive = false))
            assertFalse(TherapySessionPolicy.mayStart(kind, active = false, locked = false, otherWorkActive = true))
        }
    }

    @Test fun nonceMayAdvanceOnlyByOneOrTheDriverRecoveryStep() {
        assertTrue(TherapySessionPolicy.nonceStepAllowed(1))
        assertTrue(TherapySessionPolicy.nonceStepAllowed(500))
        assertFalse(TherapySessionPolicy.nonceStepAllowed(0))
        assertFalse(TherapySessionPolicy.nonceStepAllowed(2))
        assertFalse(TherapySessionPolicy.nonceStepAllowed(-1))
        assertFalse(TherapySessionPolicy.nonceStepAllowed(1000))
    }

    @Test fun settledRequiresDriverDisconnectClosedLinkAndKnownCommandOutcome() {
        assertTrue(TherapySessionPolicy.settled(true, true, true, false, true))
        assertFalse(TherapySessionPolicy.settled(false, true, true, false, true))
        assertFalse(TherapySessionPolicy.settled(true, false, true, false, true))
        assertFalse(TherapySessionPolicy.settled(true, true, false, false, true))
        assertFalse(TherapySessionPolicy.settled(true, true, true, true, true))
        assertFalse(TherapySessionPolicy.settled(true, true, true, false, false))
    }

    @Test fun targetIsTheSecondOffBodyPumpOnly() {
        assertEquals("PUMP_10392647", TherapySessionPolicy.PUMP)
        assertEquals("00:0E:2F:E7:D1:95", TherapySessionPolicy.ADDRESS)
    }
}
