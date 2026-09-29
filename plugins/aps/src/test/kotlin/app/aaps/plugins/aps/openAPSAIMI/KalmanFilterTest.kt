package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.jupiter.api.Test

class KalmanFilterTest {

    @Test
    fun `test KalmanFilter update`() {
        val filter = KalmanFilter(
            stateEstimate = 10.0,
            estimationError = 5.0,
            processVariance = 1.0,
            measurementVariance = 1.0
        )
        // Prediction: 10.0
        // Pred Error: 5 + 1 = 6
        // Gain: 6 / (6 + 1) = 6/7 approx 0.857
        // Update: 10 + 0.857 * (20 - 10) = 18.57
        val newValue = filter.update(20.0)
        assertEquals(18.57, newValue, 0.1)
    }

    @Test
    fun `test KalmanISFCalculator`() {
        val tddCalculator = mockk<TddCalculator>(relaxed = true)
        val preferences = mockk<Preferences>(relaxed = true)
        val logger = mockk<AAPSLogger>(relaxed = true)
        
        // Mock TDD
        every { preferences.get(DoubleKey.OApsAIMITDD7) } returns 50.0
        every { tddCalculator.averageTDD(any()) } returns null
        // Mock TDD calculator to return null so it falls back to prefs or just mock it to return something
        // The code calls tddCalculator.averageTDD(...)
        // Let's just rely on the fallback to TDD7P which is 50.0
        
        val calculator = KalmanISFCalculator(tddCalculator, preferences, logger)
        
        // Test with BG 100. Glucose is represented once by the logarithmic formula.
        // TDD 50.
        // Raw ISF = 1800 / (50 * ln(100/75 + 1))
        // ln(1.33 + 1) = ln(2.33) approx 0.84
        // 1800 / (50 * 0.84) = 1800 / 42 = 42.8
        
        val isf = calculator.calculateISF(100.0, 0.0, 0.0)
        
        assertTrue(isf > 40.0)
        assertTrue(isf < 45.0)
    }

    @Test
    fun `rapid hyperglycaemia does not collapse ISF`() {
        val tddCalculator = mockk<TddCalculator>(relaxed = true)
        val preferences = mockk<Preferences>(relaxed = true)
        val logger = mockk<AAPSLogger>(relaxed = true)
        every { preferences.get(DoubleKey.OApsAIMITDD7) } returns 50.0
        every { tddCalculator.averageTDD(any()) } returns null
        val calculator = KalmanISFCalculator(tddCalculator, preferences, logger)

        val incidentSequence = listOf(
            Triple(137.0, 1.3, 1.3),
            Triple(143.0, 4.0, 4.0),
            Triple(154.0, 9.3, 9.3),
            Triple(171.0, 15.0, 15.0),
            Triple(189.0, 9.0, 9.0),
            Triple(208.0, 15.7, 15.7),
            Triple(219.0, 13.7, 13.7)
        )

        val estimates = incidentSequence.map { (bg, delta, predicted) ->
            calculator.calculateISF(bg, delta, predicted)
        }

        assertTrue("ISF must not collapse during a rapid rise: $estimates", estimates.all { it > 20.0 })
        assertTrue("Final ISF must remain physiologically anchored: ${estimates.last()}", estimates.last() > 25.0)
    }
}
