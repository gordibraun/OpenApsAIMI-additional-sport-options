package app.aaps.plugins.aps.openAPSAIMI.safety

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GuardedBasalSelectorTest {
    private fun choose(bg: Double = 152.0, delta: Double = 5.0, short: Double = 6.7,
                       maximum: Double = 1.02, step: Double = 0.102,
                       forecast: (Double) -> List<Int>) =
        GuardedBasalSelector.select(maximum, step, bg, delta, short, 117.0, forecast = forecast)

    @Test fun september30SmbRemainsBlockedButSafeBasalNeedNotBeZero() {
        val smb = EarlyOverdeliveryGuard.evaluate(EarlyOverdeliveryGuard.Input(
            true, 0.0, 152.0, 5.0, 6.7, 2.048, 10, 0.7, false, 118.0, false, false
        ))
        assertEquals(0.0, smb.limitSmb(1.9))
        val choice = choose { rate -> List(48) { (144 - rate / 1.02 * 26).toInt() } }
        assertEquals(1.02, choice.rate, 1e-9)
    }

    @Test fun unsafeProfileSelectsOnlyPumpRepresentableReducedBasal() {
        val checked = mutableListOf<Double>()
        val c = choose { rate -> checked.add(rate); List(48) { (140 - 50 * rate).toInt() } }
        assertEquals(0.408, c.rate, 1e-9)
        assertTrue(checked.all { kotlin.math.abs(it / 0.102 - kotlin.math.round(it / 0.102)) < 1e-8 })
    }

    @Test fun lowFallingAndSportCapAreRespected() {
        assertEquals(0.0, choose(bg = 98.0, delta = -16.67) { fail("Must not restore basal during a fall") }.rate)
        assertEquals(0.0, choose(bg = 123.0, short = -1.0) { fail("Trend reaches target") }.rate)
        assertTrue(choose(maximum = 0.84) { List(48) { 150 } }.rate <= 0.84)
    }

    @Test fun shallowDeclineAboveTargetMustCheckForecastInsteadOfBlindlyStoppingBasal() {
        for ((bg, delta, short) in listOf(Triple(167.0, -2.0, -1.24), Triple(165.0, -0.67, -1.0))) {
            val checked = mutableListOf<Double>()
            val c = choose(bg, delta, short) { rate -> checked.add(rate); List(48) { 125 } }
            assertEquals(1.02, c.rate, 1e-9)
            assertTrue(checked.isNotEmpty())
        }
    }

    @Test fun shallowDeclineStillCannotRestoreBasalWithUnsafeOrMissingForecast() {
        for (series in listOf(emptyList(), List(48) { 100 }, List(12) { 180 })) {
            assertEquals(0.0, choose(167.0, -2.0, -1.24) { series }.rate)
        }
    }

    @Test fun eitherTrendReachingTargetDuringTempBasalRetainsTheStop() {
        for ((delta, short) in listOf(-8.0 to 1.0, 1.0 to -8.0)) {
            assertEquals(0.0, choose(165.0, delta, short) { fail("Trend reaches target") }.rate)
        }
        assertEquals(0.0, choose(bg = 117.0) { fail("Already at target") }.rate)
        assertEquals(0.0, GuardedBasalSelector.select(1.0, 0.1, 160.0, -4.0, -4.0, 117.0, 60) {
            fail("Use actual temp duration")
        }.rate)
        assertEquals(0.0, GuardedBasalSelector.select(1.0, 0.1, 160.0, 0.0, 0.0, 117.0, 0) {
            fail("Invalid duration")
        }.rate)
    }

    @Test fun riskWithNoBasalDoesNotPermitAddingAnyBack() {
        assertEquals(0.0, choose { List(48) { 65 } }.rate)
    }

    @Test fun missingTruncatedOrInvalidForecastFailsClosed() {
        for (series in listOf(emptyList(), List(12) { 200 }, List(48) { 0 })) {
            assertEquals(0.0, choose { series }.rate)
        }
        assertEquals(0.0, choose(step = Double.NaN) { fail("Invalid pump step") }.rate)
    }

    @Test fun allSelectedCandidatesRespectTargetAndProfileCap() {
        for (zeroMinimum in 80..200 step 10) for (effect in 5..80 step 5) {
            val c = choose { rate -> List(48) { (zeroMinimum - effect * rate).toInt() } }
            assertTrue(c.rate in 0.0..1.02)
            if (c.rate > 0) assertTrue((zeroMinimum - effect * c.rate).toInt() >= 117)
        }
    }
}
