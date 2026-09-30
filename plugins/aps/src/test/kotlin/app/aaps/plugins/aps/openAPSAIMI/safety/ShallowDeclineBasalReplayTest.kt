package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionEngine
import app.aaps.plugins.aps.openAPSAIMI.pkpd.testInsulinAction
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class ShallowDeclineBasalReplayTest {
    // De-identified frozen inputs from two decisions that stopped basal solely for
    // a shallow decline above target. Replay validates calculations, not clinical outcomes.
    private data class Snapshot(
        val bg: Double, val delta: Double, val shortDelta: Double, val isf: Double,
        val carbImpact: Double, val activity: List<Double>, val recordedZero: List<Int>
    )

    private val snapshots = listOf(
        Snapshot(167.0, -2.0, -1.24, 52.646385192871094, 6.2,
            listOf(.0313, .03, .0286, .0272, .0258, .0244, .023, .0217,
                .0204, .0191, .0178, .0165, .0154, .0142, .0131, .0121,
                .0111, .0101, .0093, .0084, .0076, .007, .0063, .0058,
                .0052, .0048, .0042, .0038, .0034, .003, .0026, .0023,
                .0019, .0017, .0014, .0012, .001, .0008, .0007, .0006,
                .0006, .0005, .0005, .0003, .0003, .0003, .0003, .0003),
            listOf(167, 165, 162, 159, 157, 154, 152, 149, 147, 145, 143, 141,
                140, 138, 137, 135, 134, 133, 133, 132, 131, 131, 130, 130,
                130, 129, 129, 129, 129, 129, 129, 129, 129, 129, 129, 129,
                129, 129, 129, 130, 130, 130, 130, 130, 130, 130, 130, 130, 131)),
        Snapshot(165.0, -0.67, -1.0, 53.75031661987305, 6.6,
            listOf(.0284, .0268, .0254, .0238, .0224, .021, .0196, .0182,
                .017, .0157, .0145, .0134, .0123, .0113, .0103, .0094,
                .0085, .0078, .007, .0063, .0057, .0052, .0047, .0042,
                .0038, .0033, .0029, .0026, .0022, .0019, .0016, .0014,
                .0011, .0009, .0007, .0006, .0005, .0004, .0004, .0003,
                .0003, .0002, .0002, .0002, .0002, .0002, .0002, .0002),
            listOf(165, 164, 162, 161, 159, 157, 156, 154, 153, 152, 151, 150,
                149, 149, 148, 148, 147, 147, 147, 147, 146, 146, 146, 146,
                147, 147, 147, 147, 147, 147, 148, 148, 148, 149, 149, 149,
                149, 150, 150, 150, 150, 151, 151, 151, 151, 151, 151, 151, 152))
    )

    private val action = testInsulinAction(dia = 5.0, peak = 55)
    private val profile = mockk<OapsProfileAimi>(relaxed = true).also {
        every { it.carb_ratio } returns 10.3
    }

    private fun forecast(s: Snapshot, rate: Double) = AdvancedPredictionEngine.predict(
        currentBG = s.bg,
        iobArray = s.activity.mapIndexed { i, value -> IobTotal(i * 300_000L, activity = value) }.toTypedArray(),
        finalSensitivity = s.isf, cobG = 0.0, profile = profile, delta = s.delta,
        plannedInsulinAction = action, plannedSmbU = 0.0,
        plannedRateUph = rate, profileBasalUph = .81, plannedDurationMin = 30,
        observedCarbImpactMgdlPer5m = s.carbImpact, remainingCiPeakMgdlPer5m = 0.0,
        uamConfidence = 0.0, freshSmbPressureU = .2, targetBG = 117.0
    ).map { it.roundToInt() }

    @Test fun unchangedZeroBasalReplayMatchesRecordedForecast() {
        for (s in snapshots) {
            val replay = forecast(s, 0.0)
            assertEquals(s.recordedZero.size, replay.size)
            s.recordedZero.zip(replay).forEach { (recorded, actual) ->
                assertTrue(abs(recorded - actual) <= 1, "Recorded=$recorded replay=$actual")
            }
        }
    }

    @Test fun shallowDeclineSelectsReducedThenProfileBasalWithoutUnblockingSmb() {
        for ((index, s) in snapshots.withIndex()) {
            val guard = EarlyOverdeliveryGuard.evaluate(EarlyOverdeliveryGuard.Input(
                true, 0.0, s.bg, s.delta, s.shortDelta, if (index == 0) 2.166 else 1.757,
                30, .2, false, forecast(s, .81).min().toDouble(), false, false
            ))
            assertEquals(0.0, guard.limitSmb(1.0))
            assertTrue(guard.requiresBasalReview)
            val choice = GuardedBasalSelector.select(.81, .081, s.bg, s.delta, s.shortDelta, 117.0) {
                forecast(s, it)
            }
            assertTrue(choice.rate > 0.0)
            assertTrue(choice.rate <= .81)
            assertTrue(forecast(s, choice.rate).all { it >= 117 })
            if (index == 0) assertTrue(choice.rate < .81) else assertEquals(.81, choice.rate, 1e-9)
            println("Shallow-decline replay BG=${s.bg}: basal=${choice.rate}, min=${forecast(s, choice.rate).min()}, SMB still blocked")
        }
    }
}
