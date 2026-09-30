package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionEngine
import app.aaps.plugins.aps.openAPSAIMI.pkpd.CarbAbsorptionModel
import app.aaps.plugins.aps.openAPSAIMI.pkpd.testInsulinAction
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class GuardedBasalReplayTest {
    // Frozen engine inputs from the audited rising-glucose / blocked-SMB decision.
    // Timestamps and identifiers are omitted. This is not a counterfactual glucose outcome.
    private val activity = listOf(
        .0121, .0136, .0147, .0155, .0161, .0165, .0166, .0166,
        .0165, .0162, .0159, .0155, .0150, .0145, .0140, .0134,
        .0128, .0121, .0115, .0109, .0104, .0097, .0091, .0085,
        .0080, .0074, .0070, .0064, .0059, .0054, .0051, .0046,
        .0042, .0038, .0034, .0032, .0029, .0026, .0023, .0021,
        .0019, .0017, .0014, .0013, .0011, .0010, .0009, .0007
    )
    private val recordedZeroBasal = listOf(
        152, 159, 164, 168, 170, 173, 174, 175, 176, 176, 176, 176,
        176, 175, 174, 173, 172, 171, 169, 168, 166, 165, 163, 162,
        161, 159, 158, 157, 155, 154, 153, 152, 151, 150, 150, 149,
        148, 148, 147, 147, 146, 146, 145, 145, 145, 145, 145, 144, 144
    )

    private fun forecast(rate: Double): List<Int> {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.carb_ratio } returns 10.3
        return AdvancedPredictionEngine.predict(
            currentBG = 152.0,
            iobArray = activity.mapIndexed { i, value -> IobTotal(i * 300_000L, activity = value) }.toTypedArray(),
            finalSensitivity = 55.21961212158203,
            cobG = 0.0, profile = profile, plannedInsulinAction = testInsulinAction(), delta = 5.0,
            plannedSmbU = 0.0, plannedRateUph = rate, profileBasalUph = 0.94,
            plannedDurationMin = 30, observedCarbImpactMgdlPer5m = 8.3,
            remainingCiPeakMgdlPer5m = 0.0, rescueFastActive = false,
            uamConfidence = 0.3, freshSmbPressureU = 0.7, targetBG = 117.0
        ).map { it.roundToInt() }
    }

    @Test fun neutralBasalReplayMatchesRecordedInputsAfterRemovingTheOldFoodBasedBasalLift() {
        val replay = forecast(0.94)
        assertEquals(recordedZeroBasal.size, replay.size)
        // The old zero-basal forecast used a food curve for the omitted insulin.
        // Remove that historical contribution; do not preserve the bug as a golden result.
        val oldWeights = CarbAbsorptionModel.buildWeights(48, 36.0, 135.0)
        var oldLift = 0.0
        recordedZeroBasal.zip(replay).forEachIndexed { i, (recorded, actual) ->
            if (i > 0) oldLift += oldWeights[i - 1] * 0.94 * 0.5 * 55.21961212158203
            assertTrue(abs(recorded - oldLift - actual) <= 1.1)
        }
    }

    @Test fun blockedSmbDoesNotRequireZeroBasalWithTheseFrozenInputs() {
        val guard = EarlyOverdeliveryGuard.evaluate(
            EarlyOverdeliveryGuard.Input(true, 0.0, 152.0, 5.0, 6.65, 2.048, 5, 0.7, false, 118.0, false, false)
        )
        assertEquals(0.0, guard.limitSmb(1.8825))
        assertTrue(guard.requiresBasalReview)
        val choice = GuardedBasalSelector.select(.94, .094, 152.0, 5.0, 6.65, 117.0, forecast = ::forecast)
        assertEquals(.94, choice.rate, 1e-9)
        assertTrue(forecast(choice.rate).all { it >= 117 })
        assertTrue(forecast(choice.rate).last() < forecast(0.0).last())
        println("Frozen-input replay: SMB=0; basal=${choice.rate}; min=${forecast(choice.rate).min()}; zero-basal min=${forecast(0.0).min()}")
    }

    @Test fun recordedFallsBeforeAndAfterRescueFoodCannotRestoreBasal() {
        // Audited fall at high BG, then below target before/after fast carbohydrates.
        for ((bg, delta, shortDelta) in listOf(
            Triple(201.0, -18.67, -15.54), Triple(114.0, -18.67, -19.8), Triple(98.0, -16.67, -17.52)
        )) {
            val choice = GuardedBasalSelector.select(1.2, .12, bg, delta, shortDelta, 117.0) {
                fail("Falling glucose must not enter basal restoration")
            }
            assertEquals(0.0, choice.rate)
        }
    }
}
