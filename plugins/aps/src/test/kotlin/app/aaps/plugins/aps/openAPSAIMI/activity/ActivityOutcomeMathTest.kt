package app.aaps.plugins.aps.openAPSAIMI.activity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActivityOutcomeMathTest {

    private val start = 1_800_000_000_000L
    private fun minutes(m: Int) = m * 60_000L
    private val sport = ActivityOutcomeMath.Session(start, 50, "SPORT", 480, ActivityOutcomeMath.HrProfile(128, 152, 40, 20, 60))

    /** A glucose curve: one reading every five minutes over the whole window. */
    private fun glucose(session: ActivityOutcomeMath.Session, value: (Int) -> Double): List<Pair<Long, Double>> {
        val minutes = ((session.tailEndEpochMs - session.startEpochMs) / 60_000L).toInt()
        return (0..minutes step 5).map { start + minutes(it) to value(it) }
    }

    @Test fun `a night low through the tail means the tail was too weak`() {
        val inputs = ActivityOutcomeMath.Inputs(
            glucose(sport) { if (it in 300..330) 62.0 else 110.0 }, carbsG = listOf(start + minutes(320) to 15.0),
            boluses = emptyList(), basalDeliveredU = 4.1, basalProfileU = 6.0
        )
        val report = ActivityOutcomeMath.evaluate(sport, inputs)
        assertEquals(ActivityOutcomeMath.Verdict.TOO_WEAK, report.verdict)
        assertEquals(35, report.minutesBelow70)
        assertEquals(62, report.minGlucose)
        assertEquals(15, report.carbsG)
        assertEquals(68, report.basalPercentOfProfile)
        val line = ActivityOutcomeMath.line(report) { "29.10 20:00" }
        assertTrue(line.contains("спорт 50 мин") && line.contains("пульс 128/152") && line.contains("хвост 8 ч") && line.endsWith("хвост слабоват"), line)
    }

    @Test fun `high glucose with nothing eaten means the tail was too strong, in between it was fair`() {
        val high = ActivityOutcomeMath.Inputs(glucose(sport) { 190.0 }, emptyList(), emptyList(), 5.0, 6.0)
        assertEquals(ActivityOutcomeMath.Verdict.TOO_STRONG, ActivityOutcomeMath.evaluate(sport, high).verdict)
        val fair = ActivityOutcomeMath.Inputs(glucose(sport) { 120.0 }, emptyList(), listOf(Triple(start + minutes(200), 0.3, true)), 5.0, 6.0)
        val report = ActivityOutcomeMath.evaluate(sport, fair)
        assertEquals(ActivityOutcomeMath.Verdict.FAIR, report.verdict)
        assertEquals(1, report.smbCount)
    }

    @Test fun `too few readings give no verdict`() {
        val sparse = ActivityOutcomeMath.Inputs(glucose(sport) { 110.0 }.take(10), emptyList(), emptyList(), 5.0, 6.0)
        assertEquals(ActivityOutcomeMath.Verdict.UNKNOWN, ActivityOutcomeMath.evaluate(sport, sparse).verdict)
    }

    @Test fun `after three sessions the advice says which way the tail should move`() {
        fun report(verdict: ActivityOutcomeMath.Verdict, mode: String = "SPORT") =
            ActivityOutcomeMath.Report(start, mode, 50, 480, null, 100, 130, 0, 0, 0, 0, 0.0, 0.0, 0, 70, verdict, "")
        assertNull(ActivityOutcomeMath.suggestion("SPORT", listOf(report(ActivityOutcomeMath.Verdict.TOO_WEAK), report(ActivityOutcomeMath.Verdict.FAIR))))
        val weak = ActivityOutcomeMath.suggestion("SPORT", listOf(report(ActivityOutcomeMath.Verdict.TOO_WEAK), report(ActivityOutcomeMath.Verdict.TOO_WEAK), report(ActivityOutcomeMath.Verdict.FAIR)))
        assertNotNull(weak)
        assertTrue(weak!!.text.contains("усилить"), weak.text)
        val fine = ActivityOutcomeMath.suggestion("WALK", List(4) { report(ActivityOutcomeMath.Verdict.FAIR, "WALK") })
        assertTrue(fine!!.text.contains("справедливым"), fine.text)
        // Heart-rate tokens survive the trip through the record's note.
        val hr = ActivityOutcomeMath.HrProfile.fromNote("AIMI_ACTIVITY_V2 mode=SPORT hr_avg=128 hr_max=152 hr_effort=40 hr_hard=20 hr_rest=60")
        assertEquals(ActivityOutcomeMath.HrProfile(128, 152, 40, 20, 60), hr)
    }
}
