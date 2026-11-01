package app.aaps.plugins.aps.loop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PostBolusBasalCapTest {

    @Test fun `a temp basal above 150 percent is held while a manual bolus is fresh`() {
        // 18:20 on 01.11.2026: 4.2 U/h asked for on a 0.84 U/h profile, 2.6 U by hand minutes before.
        assertEquals(0.84 * 1.5, PostBolusBasalCap.cappedRate(4.2, 0.84, 2.6)!!, 1e-9)
        assertEquals(150, PostBolusBasalCap.cappedPercent(420, 2.6))
    }

    @Test fun `without a fresh manual bolus, or below the cap, the request passes untouched`() {
        assertNull(PostBolusBasalCap.cappedRate(4.2, 0.84, 0.0))
        assertNull(PostBolusBasalCap.cappedRate(4.2, 0.84, 0.4))
        assertNull(PostBolusBasalCap.cappedRate(1.2, 0.84, 2.6))
        assertNull(PostBolusBasalCap.cappedRate(0.0, 0.84, 2.6))
        assertNull(PostBolusBasalCap.cappedPercent(150, 2.6))
        assertNull(PostBolusBasalCap.cappedPercent(0, 2.6))
        assertNull(PostBolusBasalCap.cappedRate(4.2, 0.0, 2.6))
    }

    @Test fun `the note says what was done`() {
        val note = PostBolusBasalCap.note(2.6, "4.20 U/h", "1.26 U/h")
        assertTrue(note.contains("2.60 U"), note)
        assertTrue(note.contains("30 min"), note)
    }
}
