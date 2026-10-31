package app.aaps.combobench.controller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceFreshnessTest {

    private val now = 1_800_000_000_000L
    private fun min(m: Int) = m * 60_000L

    @Test fun ownForecastStandsForAQuarterOfAnHour() {
        assertTrue(FaceFreshness.ownUsable(now, now - min(10)))
        assertFalse(FaceFreshness.ownUsable(now, now - min(20)))
        assertFalse(FaceFreshness.ownUsable(now, now - 14 * 60 * min(60)))
        assertFalse(FaceFreshness.ownUsable(now, 0L))
    }

    @Test fun phoneForecastStandsHalfAnHourItsInsulinFourHours() {
        assertTrue(FaceFreshness.phoneForecastUsable(now, now - min(25)))
        assertFalse(FaceFreshness.phoneForecastUsable(now, now - min(40)))
        assertTrue(FaceFreshness.phoneIobUsable(now, now - min(40)))
        assertFalse(FaceFreshness.phoneIobUsable(now, now - min(5 * 60)))
    }

    @Test fun aFigureStampedSlightlyAheadOfTheWatchClockIsFresh() {
        assertTrue(FaceFreshness.ownUsable(now, now + min(1)))
        assertFalse(FaceFreshness.ownUsable(now, now + min(10)))
    }
}
