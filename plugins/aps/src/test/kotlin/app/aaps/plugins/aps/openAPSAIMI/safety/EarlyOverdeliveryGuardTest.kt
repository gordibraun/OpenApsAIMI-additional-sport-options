package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.plugins.aps.openAPSAIMI.pkpd.ForecastDecisionSearch
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EarlyOverdeliveryGuardTest {
    private val rebound = EarlyOverdeliveryGuard.Input(
        noActiveMealMode = true, cobG = 2.14, bg = 119.0, delta = 12.33, shortAvgDelta = 11.8,
        iobU = 0.186, lastSmbMinutes = 100, lastSmbU = 0.3, turningDown = false,
        minForecastBg = 116.0, explicitlySlowCarbs = false, cumulativeSmbBlocked = false
    )

    @Test fun reboundSoftLimitDoesNotEnterTheZeroBasalReturn() {
        val decision = EarlyOverdeliveryGuard.evaluate(rebound)
        assertEquals(0.3, decision.maxSmbUnits)
        assertFalse(decision.requiresBasalHold)
    }

    @Test fun softLimitIsNotAnInstructionToGiveInsulin() {
        val cap = EarlyOverdeliveryGuard.evaluate(rebound).maxSmbUnits!!
        val fraction = ForecastDecisionSearch.safeFraction { false }
        assertEquals(0.0, ForecastDecisionSearch.candidateBolus(minOf(1.2, cap), fraction, 0.1))
        assertEquals(0.0, ForecastDecisionSearch.candidateBolus(minOf(0.0, cap), 1.0, 0.1))
    }

    @Test fun freshInsulinAndLowForecastStillHold() {
        val decision = EarlyOverdeliveryGuard.evaluate(rebound.copy(lastSmbMinutes = 5))
        assertTrue(decision.requiresBasalHold)
        assertEquals(0.0, decision.maxSmbUnits)
    }

    @Test fun laterBoostCannotEscapeTheSoftCapOrForceASmallerRequestUp() {
        val decision = EarlyOverdeliveryGuard.evaluate(rebound)
        assertEquals(0.3, decision.limitSmb(0.3 * 1.5))
        assertEquals(0.1, decision.limitSmb(0.1))
        assertEquals(0.0, decision.limitSmb(0.0))
        assertEquals(0.0, EarlyOverdeliveryGuard.evaluate(rebound.copy(cumulativeSmbBlocked = true)).limitSmb(1.0))
    }

    @Test fun fallingWithHighIobStillHolds() {
        assertTrue(EarlyOverdeliveryGuard.evaluate(rebound.copy(iobU = 1.5, delta = -2.0)).requiresBasalHold)
    }

    @Test fun cumulativeBlockWinsOverTheSoftLimit() {
        assertTrue(EarlyOverdeliveryGuard.evaluate(rebound.copy(cumulativeSmbBlocked = true)).requiresBasalHold)
    }

    @Test fun unrestrictedMealDoesNotRequestABasalHold() {
        val decision = EarlyOverdeliveryGuard.evaluate(rebound.copy(noActiveMealMode = false))
        assertNull(decision.maxSmbUnits)
        assertFalse(decision.requiresBasalHold)
    }
}
