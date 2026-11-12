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

    @Test fun aBolusStillBeingDeliveredWhenThePhonesLoopRanIsAddedToItsFigure() {
        // The pump dates a bolus from its first drop; the watch reads it half a minute later. A loop run of the
        // phone in between is newer than the bolus by the pump's time, and still knows nothing of it.
        val learned = now - 20_000L
        assertTrue(FaceFreshness.bolusMissingFromFigure(learned, figureAtEpochMs = now - 40_000L, byPhone = true, reportPending = false))
        // A loop run after the watch reported it has counted it.
        assertFalse(FaceFreshness.bolusMissingFromFigure(learned, figureAtEpochMs = now - 5_000L, byPhone = true, reportPending = false))
    }

    @Test fun aBolusGivenByHandOnThePumpIsAddedUntilThePhoneHasHeardOfIt() {
        // Given six minutes ago on the pump itself, read by the watch just now; the phone's figure is two minutes old.
        assertTrue(FaceFreshness.bolusMissingFromFigure(now, figureAtEpochMs = now - min(2), byPhone = true, reportPending = false))
        // The report has not got through: the phone cannot have it, however new its figure.
        assertTrue(FaceFreshness.bolusMissingFromFigure(now - min(10), figureAtEpochMs = now - min(1), byPhone = true, reportPending = true))
    }

    @Test fun theWatchsOwnForecastHoldsEveryBolusItHadReadByThen() {
        assertFalse(FaceFreshness.bolusMissingFromFigure(now - min(3), figureAtEpochMs = now - min(1), byPhone = false, reportPending = true))
        assertTrue(FaceFreshness.bolusMissingFromFigure(now - 10_000L, figureAtEpochMs = now - min(1), byPhone = false, reportPending = false))
    }
}
