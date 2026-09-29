package app.aaps.plugins.aps.openAPSAIMI.smb

import app.aaps.plugins.aps.openAPSAIMI.DetermineBasalaimiSMB2
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Characterizes the audited defect, not desired dosing behavior. Replace on repair. */
class MpcCandidateCostAuditTest {
    private val engine = mockk<DetermineBasalaimiSMB2>().also { engine ->
        // Execute the real, dependency-free cost method and its private prediction function.
        every { engine.costFunction(any(), any(), any(), any(), any(), any()) } answers { callOriginal() }
        every { engine["predictGlycemia"](any<Double>(), any<Double>(), any<Int>(), any<Double>(),
            any<Int>(), any<Double>(), any<Double>(), any<Int>(), any<Int>()) } answers { callOriginal() }
    }

    @Test fun currentCallerSweepSelectsTheBasalNumberRegardlessOfGlucose() {
        val basalUph = 1.125
        val candidatesU = (0..20).map { it * 1.5 / 20 }
        for (bg in listOf(80.0, 119.0, 200.0, 280.0)) {
            for (isf in listOf(20.0, 48.0, 100.0)) {
                val best = candidatesU.minBy { candidate ->
                    // Same argument positions as SmbInstructionExecutor -> DetermineBasal hook.
                    engine.costFunction(basalUph, bg, 117.0, 30, isf, candidate)
                }
                assertEquals(basalUph, best, 1e-9)
            }
        }
    }

    @Test fun changingSweptDoseChangesOnlyThePenaltyNotTheGlucoseCost() {
        val basal = 1.125
        val a = 0.3
        val b = 1.2
        val expectedDifference = 0.5 * ((basal - b) * (basal - b) - (basal - a) * (basal - a))
        for (bg in listOf(80.0, 119.0, 200.0)) {
            val actual = engine.costFunction(basal, bg, 117.0, 30, 48.0, b) -
                engine.costFunction(basal, bg, 117.0, 30, 48.0, a)
            assertEquals(expectedDifference, actual, 1e-8)
        }
    }
}
