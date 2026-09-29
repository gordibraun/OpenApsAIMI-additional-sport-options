package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PkPdRuntimeLearningTest {
    private val preferences = mockk<Preferences>(relaxed = true)
    private val values = mutableMapOf<DoublePreferenceKey, Double>(
        DoubleKey.OApsAIMIPkpdStateDiaH to 7.55,
        DoubleKey.OApsAIMIPkpdStatePeakMin to 76.0
    )
    init {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        every { preferences.get(any<DoublePreferenceKey>()) } answers { values[firstArg()] ?: firstArg<DoublePreferenceKey>().defaultValue }
        every { preferences.put(any<DoublePreferenceKey>(), any()) } answers { values[firstArg()] = secondArg(); Unit }
    }
    private fun runtime(integration: PkPdIntegration, time: Long = 1_800_000_000_000) = integration.computeRuntime(
        time, 120.0, -5.0, 3.0, 0.0, 60, false, 54.0, 40.0
    )!!

    @Test
    fun `runtime snapshots cannot train on mixed remaining IOB`() {
        val integration = PkPdIntegration(preferences)
        val initial = runtime(integration).params
        repeat(288) { assertEquals(initial, runtime(integration, 1_800_000_000_000 + it * 300_000L).params) }
        assertEquals(PkPdParams(7.55, 76.0), initial)
    }

    @Test
    fun `external parameter changes and restart use the saved state`() {
        val integration = PkPdIntegration(preferences)
        runtime(integration)
        values[DoubleKey.OApsAIMIPkpdStatePeakMin] = 90.0
        assertEquals(90.0, runtime(integration).params.peakMin)
        assertEquals(runtime(integration).params, runtime(PkPdIntegration(preferences)).params)
    }

    @Test
    fun `changing persisted peak does not reset the sensitivity rate limiter`() {
        values[DoubleKey.OApsAIMIIsfFusionMaxChangePerTick] = 0.01
        val integration = PkPdIntegration(preferences)
        val initial = runtime(integration).fusedIsf
        values[DoubleKey.OApsAIMIPkpdStatePeakMin] = 120.0
        val next = runtime(integration).fusedIsf
        assertTrue(next in initial * 0.99..initial * 1.01)
    }
}
