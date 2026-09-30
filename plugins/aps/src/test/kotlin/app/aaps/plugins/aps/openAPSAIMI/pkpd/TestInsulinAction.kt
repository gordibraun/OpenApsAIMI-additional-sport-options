package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.plugins.aps.autotune.data.LocalInsulin
import io.mockk.every
import io.mockk.mockk

// Existing AAPS Oref kernel, not a food curve or a new test-only insulin formula.
fun testInsulinAction(dia: Double = 6.0, peak: Int = 75, horizon: Int = 360): PlannedInsulinAction {
    val kernel = LocalInsulin("test", peak, dia)
    val insulin = mockk<Insulin>()
    every { insulin.iobCalcForTreatment(any(), any(), dia) } answers {
        kernel.iobCalcForTreatment(firstArg(), secondArg())
    }
    return PlannedInsulinAction.from(insulin, dia, horizon)
}
