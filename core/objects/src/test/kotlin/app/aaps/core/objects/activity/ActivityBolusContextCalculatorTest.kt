package app.aaps.core.objects.activity

import app.aaps.core.data.model.TE
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ActivityBolusContextCalculatorTest {
    private val now = 50_000_000L
    private fun event(offset: Int, mode: String = "WALK", tail: Int = 0) = TE(
        timestamp = now + offset * 60_000L, type = TE.Type.EXERCISE, duration = 30 * 60_000L, glucoseUnit = app.aaps.core.data.model.GlucoseUnit.MGDL,
        note = "AIMI_ACTIVITY_V2 mode=$mode duration=30 tail=$tail")

    @Test fun activeAndUpcomingMatchPhonePolicy() {
        for (offset in listOf(-10, 0, 20, 75))
            assertEquals(0.8, ActivityBolusContextCalculator.calculate(listOf(event(offset)), now)!!.factor, 0.0001)
        assertEquals(0.7, ActivityBolusContextCalculator.calculate(listOf(event(0, "SPORT")), now)!!.factor, 0.0001)
    }

    @Test fun tailAndDistantStartTaper() {
        assertEquals(0.85, ActivityBolusContextCalculator.calculate(listOf(event(-60, "SPORT", 60)), now)!!.factor, 0.0001)
        assertEquals(0.9, ActivityBolusContextCalculator.calculate(listOf(event(105, "SPORT")), now)!!.factor, 0.0001)
    }

    @Test fun ExpiredInvalidOrUnrelatedEventsDoNotAdjustDose() {
        assertNull(ActivityBolusContextCalculator.calculate(listOf(event(-100), event(121), event(0).copy(isValid = false), event(0).copy(note = "other")), now))
    }
}
