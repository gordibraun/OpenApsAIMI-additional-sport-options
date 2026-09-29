package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BasalLearningEligibilityTest {
    @TempDir lateinit var directory: File
    private val now = 1_800_000_000_000L

    private fun reason(cob: Double = 0.0, meal: Boolean = false, exercise: Boolean = false,
                       bolusIob: Double = 0.0, basalIob: Double = 0.0, temp: Boolean = false,
                       carbTime: Long = 0, noise: Double = 0.0, sample: Long = now) =
        BasalLearningEligibility.skipReason(now, sample, 120.0, 1.0, noise, false, cob, meal,
            exercise, carbTime, 6.0, bolusIob, basalIob, temp)

    @Test
    fun `exclude known food insulin activity and unreliable readings`() {
        assertNull(reason())
        assertNotNull(reason(cob = 1.0))
        assertNotNull(reason(meal = true))
        assertNotNull(reason(exercise = true))
        assertNotNull(reason(bolusIob = 0.3))
        assertNotNull(reason(basalIob = -0.3))
        assertNotNull(reason(temp = true))
        assertNotNull(reason(carbTime = now - 30 * 60_000))
        assertNotNull(reason(noise = 3.0))
        assertNotNull(reason(noise = Double.NaN))
        assertNotNull(reason(sample = now - 15 * 60_000))
        assertNotNull(reason(sample = now + 1))
    }

    @Test
    fun `food samples cannot adjust any basal multiplier`() {
        val context = mockk<Context> { every { filesDir } returns directory }
        val learner = BasalLearner(context, mockk(relaxed = true))
        repeat(300) {
            val time = now + it * 300_000L
            learner.process(200.0, 5.0, 40.0, Double.NaN, false, time, time, reason(cob = 20.0))
        }
        assertEquals(1.0, learner.getMultiplier(), 1e-9)
    }

    @Test
    fun `same CGM cannot populate a learning window even after restart`() {
        val context = mockk<Context> { every { filesDir } returns directory }
        val learner = BasalLearner(context, mockk(relaxed = true))
        repeat(20) { learner.process(160.0, 2.0, 40.0, Double.NaN, true, now, now, null) }
        assertEquals(1.0, learner.getMultiplier(), 1e-9)
        val restarted = BasalLearner(context, mockk(relaxed = true))
        val message = restarted.process(160.0, 2.0, 40.0, Double.NaN, true, now, now, null)
        assertEquals("Обучение базала пропущено: это измерение уже учтено.", message)
    }

    @Test
    fun `distinct clean observations can still train during daytime`() {
        val context = mockk<Context> { every { filesDir } returns directory }
        val learner = BasalLearner(context, mockk(relaxed = true))
        repeat(6) {
            val time = now + it * 300_000L
            learner.process(160.0, 2.0, 40.0, Double.NaN, false, time, time, null)
        }
        org.junit.jupiter.api.Assertions.assertTrue(learner.getMultiplier() > 1.0)
    }

    @Test
    fun `long term samples accumulate until the existing minimum is reached`() {
        val context = mockk<Context> { every { filesDir } returns directory }
        val learner = BasalLearner(context, mockk(relaxed = true))
        repeat(11) {
            val time = now + it * 300_000L
            learner.process(160.0, 2.0, 40.0, Double.NaN, true, time, time, null)
        }
        val saved = org.json.JSONObject(File(directory, "aimi_basal_learner.json").readText())
        org.junit.jupiter.api.Assertions.assertTrue(saved.getDouble("longTermMultiplier") > 1.0)
    }
}
