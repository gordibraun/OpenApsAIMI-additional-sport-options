package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.data.iob.Iob
import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.plugins.aps.autotune.data.LocalInsulin
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlannedInsulinActionTest {
    private val action = testInsulinAction()
    private val profile = mockk<OapsProfileAimi>(relaxed = true).also {
        every { it.carb_ratio } returns 10.0
    }

    private fun predict(food: String, smb: Double = 0.0, rate: Double = 1.0, delta: Double = 0.0) =
        AdvancedPredictionEngine.predict(
            currentBG = 180.0, iobArray = emptyArray(), finalSensitivity = 50.0,
            cobG = 10.0, profile = profile, selectedFoodType = food, delta = delta,
            plannedSmbU = smb, plannedRateUph = rate, profileBasalUph = 1.0,
            plannedDurationMin = 30, plannedInsulinAction = action
        )

    @Test fun `food type and glucose slope cannot change the incremental effect of identical insulin`() {
        val expected = action.effectsPer5Minutes(0.5, 0.6, 30, 48)
        for (food in listOf("fast", "balanced", "slow")) {
            for (delta in listOf(-3.0, 0.0, 4.0, 6.0)) {
                val baseline = predict(food, delta = delta)
                val proposed = predict(food, smb = 0.5, rate = 1.6, delta = delta)
                var drop = 0.0
                for (i in expected.indices) {
                    drop += expected[i] * 50.0
                    assertEquals(drop, baseline[i + 1] - proposed[i + 1], 1e-8, "$food delta=$delta at $i")
                }
            }
        }
        assertNotEquals(predict("fast")[9], predict("slow")[9])
    }

    @Test fun `bolus matches the active plugin residual IOB at every forecast point`() {
        val effects = action.effectsPer5Minutes(1.0, 0.0, 30, 72)
        val kernel = LocalInsulin("test", 75, 6.0)
        val dose = BS(timestamp = 0L, amount = 1.0, type = BS.Type.NORMAL)
        var acted = 0.0
        effects.forEachIndexed { i, effect ->
            acted += effect
            val remaining = kernel.iobCalcForTreatment(dose, (i + 1) * 300_000L).iobContrib
            assertEquals(1.0 - remaining, acted, 1e-9)
        }
        assertEquals(1.0, acted, 1e-9)
        assertTrue(effects.take(9).sum() < 0.5)
        assertTrue(effects.take(48).sum() < 1.0)
    }

    @Test fun `shorter forecast does not compress the insulin tail`() {
        val short = testInsulinAction(horizon = 60).effectsPer5Minutes(0.5, 0.6, 30, 12)
        val long = action.effectsPer5Minutes(0.5, 0.6, 30, 72)
        assertArrayEquals(long.take(12).toDoubleArray(), short, 1e-12)
    }

    @Test fun `basal is spread in time and matches separate small doses`() {
        val actual = action.effectsPer5Minutes(0.0, 2.0, 30, 48)
        val kernel = LocalInsulin("test", 75, 6.0)
        var cumulative = 0.0
        actual.forEachIndexed { i, effect ->
            cumulative += effect
            val time = (i + 1) * 300_000L
            val expected = (0 until 6).sumOf { part ->
                val timestamp = part * 300_000L + 150_000L
                if (timestamp >= time) 0.0 else {
                    val dose = BS(timestamp = timestamp, amount = 1.0 / 6.0, type = BS.Type.NORMAL)
                    dose.amount - kernel.iobCalcForTreatment(dose, time).iobContrib
                }
            }
            assertEquals(expected, cumulative, 1e-9)
        }
        assertTrue(actual.take(6).sum() < action.effectsPer5Minutes(1.0, 0.0, 0, 48).take(6).sum())
    }

    @Test fun `future basal does not act before it is scheduled and zero duration has no effect`() {
        val thirty = action.effectsPer5Minutes(0.0, 1.0, 30, 48)
        val sixty = action.effectsPer5Minutes(0.0, 1.0, 60, 48)
        assertArrayEquals(thirty.take(6).toDoubleArray(), sixty.take(6).toDoubleArray(), 1e-12)
        assertTrue(sixty.sum() > thirty.sum())
        assertTrue(action.effectsPer5Minutes(0.0, 1.0, 0, 48).all { it == 0.0 })
    }

    @Test fun `stopping basal is the signed counterpart not instant glucose relief`() {
        val plus = action.effectsPer5Minutes(0.0, 1.0, 30, 48)
        val minus = action.effectsPer5Minutes(0.0, -1.0, 30, 48)
        assertArrayEquals(plus.map { -it }.toDoubleArray(), minus, 1e-12)
        assertTrue(-minus[0] < 0.5 / 20)
        assertTrue(-minus.sum() < 0.5)
    }

    @Test fun `insulin duration and peak do affect action`() {
        val standard = action.effectsPer5Minutes(1.0, 0.0, 0, 48)
        val earlier = testInsulinAction(peak = 55).effectsPer5Minutes(1.0, 0.0, 0, 48)
        val shorter = testInsulinAction(dia = 5.0).effectsPer5Minutes(1.0, 0.0, 0, 48)
        assertTrue(earlier.take(12).sum() > standard.take(12).sum())
        assertTrue(shorter.sum() > standard.sum())
    }

    @Test fun `invalid or missing model cannot silently simulate a proposed dose`() {
        val insulin = mockk<Insulin>()
        every { insulin.iobCalcForTreatment(any(), any(), any()) } returns Iob(iobContrib = Double.NaN)
        assertThrows(IllegalArgumentException::class.java) { PlannedInsulinAction.from(insulin, 6.0) }
        assertThrows(IllegalArgumentException::class.java) { PlannedInsulinAction.from(insulin, 0.0) }
        assertThrows(IllegalArgumentException::class.java) {
            AdvancedPredictionEngine.predict(180.0, emptyArray(), 50.0, 10.0, profile, plannedSmbU = 0.5)
        }
    }
}
