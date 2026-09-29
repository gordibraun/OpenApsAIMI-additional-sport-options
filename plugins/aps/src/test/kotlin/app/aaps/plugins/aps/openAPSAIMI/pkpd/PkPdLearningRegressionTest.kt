package app.aaps.plugins.aps.openAPSAIMI.pkpd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PkPdLearningRegressionTest {
    private val flatKernel = object : Kernel {
        override fun actionAt(minFromDose: Double, p: PkPdParams) = 1.0 / 60.0
        override fun cdf(minFromDose: Double, p: PkPdParams) = (minFromDose / 60.0).coerceIn(0.0, 1.0)
    }

    @Test
    fun `per minute action converts to five minute glucose change`() {
        val estimator = AdaptivePkPdEstimator(flatKernel)
        val initial = estimator.params()
        estimator.update(1000, 120.0, -5.0, 1.0, 0.0, 30, false, isfMgDlPerU = 60.0)
        assertEquals(initial, estimator.params())
    }

    @Test
    fun `same observation cannot train twice`() {
        val estimator = AdaptivePkPdEstimator()
        estimator.update(1000, 120.0, -5.0, 1.0, 0.0, 60, false)
        val once = estimator.params()
        repeat(20) { estimator.update(1000, 120.0, -5.0, 1.0, 0.0, 60, false) }
        assertEquals(once, estimator.params())
    }

    @Test
    fun `five minutes cannot spend an entire daily change budget`() {
        val estimator = AdaptivePkPdEstimator()
        estimator.update(1000, 120.0, -5.0, 1.0, 0.0, 60, false)
        val before = estimator.params()
        estimator.update(1005, 120.0, -5.0, 1.0, 0.0, 60, false)
        assertTrue(kotlin.math.abs(estimator.params().diaHrs - before.diaHrs) <= 5.0 / 1440 + 1e-9)
    }

    @Test
    fun `full day stays within its daily DIA and peak budgets`() {
        val estimator = AdaptivePkPdEstimator()
        val initial = estimator.params()
        repeat(288) { estimator.update(1000 + it * 5L, 120.0, -5.0, 1.0, 0.0, 60, false) }
        assertTrue(kotlin.math.abs(estimator.params().diaHrs - initial.diaHrs) <= 1.0 + 1e-9)
        assertTrue(kotlin.math.abs(estimator.params().peakMin - initial.peakMin) <= 10.0 + 1e-9)
    }

    @Test
    fun `non finite food and out of order observations do not train`() {
        val estimator = AdaptivePkPdEstimator()
        estimator.update(1000, 120.0, -5.0, 1.0, 0.0, 60, false)
        val initial = estimator.params()
        estimator.update(999, 120.0, -5.0, 1.0, 0.0, 60, false)
        estimator.update(1005, 120.0, Double.NaN, 1.0, 0.0, 60, false)
        estimator.update(1005, 120.0, -5.0, 1.0, 1.0, 60, false)
        assertEquals(initial, estimator.params())
    }
}
