package app.aaps.combobench.controller

import app.aaps.pump.combowatch.regulation.InsulinEffectiveness
import app.aaps.pump.combowatch.regulation.InsulinEffectiveness.Level
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner is told once, again an hour on, at once when it gets worse, and not while they asked for quiet. */
class InsulinAlertPolicyTest {

    private var now = 1_800_000_000_000L
    private fun minutes(n: Int) = n * 60_000L
    private fun verdict(level: Level, bg: Int = 240) = InsulinEffectiveness.Verdict(level, bg, 100, 2, 3.0, "text")

    @Test fun toldOnceThenAgainAnHourLaterWhileItGoesOn() {
        var step = InsulinAlertPolicy.onVerdict(JSONObject(), verdict(Level.SUSPECT), now)
        assertTrue(step.notify)
        now += minutes(10)
        step = InsulinAlertPolicy.onVerdict(step.state, verdict(Level.SUSPECT), now)
        assertFalse(step.notify)
        now += minutes(55)
        step = InsulinAlertPolicy.onVerdict(step.state, verdict(Level.SUSPECT), now)
        assertTrue(step.notify)
    }

    @Test fun aSuspicionThatHardensIsToldAtOnceButOnlyOnce() {
        var step = InsulinAlertPolicy.onVerdict(JSONObject(), verdict(Level.SUSPECT), now)
        now += minutes(10)
        step = InsulinAlertPolicy.onVerdict(step.state, verdict(Level.STRONG), now)
        assertTrue(step.notify)
        now += minutes(10)
        step = InsulinAlertPolicy.onVerdict(step.state, verdict(Level.STRONG), now)
        assertFalse(step.notify)
    }

    @Test fun quietIsQuietUntilItsTime() {
        val state = InsulinAlertPolicy.quiet(JSONObject(), now, 90, "set changed")
        var step = InsulinAlertPolicy.onVerdict(state, verdict(Level.STRONG), now + minutes(30))
        assertFalse(step.notify)
        step = InsulinAlertPolicy.onVerdict(step.state, verdict(Level.STRONG), now + minutes(91))
        assertTrue(step.notify)
    }

    @Test fun theNoticeComesDownWhenGlucoseIsBackUnder160AndNotBefore() {
        var step = InsulinAlertPolicy.onVerdict(JSONObject(), verdict(Level.STRONG), now)
        step = InsulinAlertPolicy.onVerdict(step.state, verdict(Level.NONE, bg = 190), now + minutes(30))
        assertFalse(step.clear)
        step = InsulinAlertPolicy.onVerdict(step.state, verdict(Level.NONE, bg = 150), now + minutes(60))
        assertTrue(step.clear)
        step = InsulinAlertPolicy.onVerdict(step.state, verdict(Level.NONE, bg = 150), now + minutes(65))
        assertFalse(step.clear)
        // Shown again later, it is a fresh notice, not a repeat held back by the hour.
        step = InsulinAlertPolicy.onVerdict(step.state, verdict(Level.SUSPECT), now + minutes(70))
        assertTrue(step.notify)
    }
}
