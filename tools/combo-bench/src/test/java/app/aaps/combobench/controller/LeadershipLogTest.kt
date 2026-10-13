package app.aaps.combobench.controller

import app.aaps.pump.combowatch.executor.AutonomyPolicy
import app.aaps.pump.combowatch.executor.AutonomyPolicy.Standing
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeadershipLogTest {

    private val saved = mutableListOf<JSONObject>()
    private val log = LeadershipLog(load = { saved.toList() }, save = { saved.clear(); saved += it })
    private val inCharge = Standing.NotAlone("the phone is in charge")

    @Test fun `the phone leads first, the watch takes over when the silence is long enough, dated by the clocks not the look`() {
        val heard = 1_000_000L
        val first = log.observe(inCharge, AutonomyPolicy.Mode.ACTIVE, heard, heard, heard + 300_000L, false)
        assertEquals(LeadershipLog.Leader.PHONE, first?.leader)
        // Looked at again two minutes later: nothing new.
        assertNull(log.observe(inCharge, AutonomyPolicy.Mode.ACTIVE, heard + 120_000L, heard, heard + 300_000L, false))
        // The reading at +6:45 finds the watch alone; the handover is dated +5:00, when the silence reached five minutes.
        val handover = log.observe(Standing.Alone, AutonomyPolicy.Mode.ACTIVE, heard + 405_000L, heard, heard + 300_000L, false)
        assertEquals(LeadershipLog.Leader.WATCH, handover?.leader)
        assertEquals(heard + AutonomyPolicy.MIN_PHONE_SILENCE_MS, handover?.atEpochMs)
        assertTrue(handover!!.text.contains("базал ведут часы"))
        assertEquals(2, saved.size)
    }

    @Test fun `a lease hands the lead back to the phone and says so`() {
        log.observe(Standing.Alone, AutonomyPolicy.Mode.ACTIVE, 2_000_000L, 1_000_000L, 1_300_000L, false)
        val back = log.observe(inCharge, AutonomyPolicy.Mode.ACTIVE, 2_100_000L, 2_100_000L, 2_400_000L, false)
        assertEquals(LeadershipLog.Leader.PHONE, back?.leader)
        assertEquals(2_100_000L, back?.atEpochMs)
        assertTrue(back!!.text.contains("снова на связи"))
    }

    @Test fun `alone with autonomy off nobody leads, and the line says the pump runs its profile`() {
        val entry = log.observe(Standing.Alone, AutonomyPolicy.Mode.OFF, 2_000_000L, 1_000_000L, 1_300_000L, false)
        assertEquals(LeadershipLog.Leader.NOBODY, entry?.leader)
        assertTrue(entry!!.text.contains("по профилю"))
    }

    @Test fun `the watch's own pump session is not a change of who leads`() {
        log.observe(Standing.Alone, AutonomyPolicy.Mode.ACTIVE, 2_000_000L, 1_000_000L, 1_300_000L, false)
        assertNull(log.observe(Standing.NotAlone("the pump is in use"), AutonomyPolicy.Mode.ACTIVE, 2_010_000L, 1_000_000L, 1_300_000L, false))
        assertNull(log.observe(Standing.NotAlone("an earlier command is not settled"), AutonomyPolicy.Mode.ACTIVE, 2_020_000L, 1_000_000L, 1_300_000L, false))
        assertEquals(1, log.entries().size)
    }

    @Test fun `a phone that left no snapshot leaves nobody in charge, with the reason in the owner's words`() {
        val entry = log.observe(Standing.NotAlone("the phone left no snapshot"), AutonomyPolicy.Mode.ACTIVE, 2_000_000L, 1_000_000L, 1_300_000L, false)
        assertEquals(LeadershipLog.Leader.NOBODY, entry?.leader)
        assertTrue(entry!!.text.contains("не оставил данных"))
    }

    @Test fun `the log survives a restart`() {
        log.observe(inCharge, AutonomyPolicy.Mode.ACTIVE, 1_000_000L, 1_000_000L, 1_300_000L, false)
        val reloaded = LeadershipLog(load = { saved.toList() }, save = {})
        assertEquals(LeadershipLog.Leader.PHONE, reloaded.current()?.leader)
    }
}
