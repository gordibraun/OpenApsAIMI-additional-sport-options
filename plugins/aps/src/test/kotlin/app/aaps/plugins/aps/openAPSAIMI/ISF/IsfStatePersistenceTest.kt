package app.aaps.plugins.aps.openAPSAIMI.ISF

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.KalmanISFCalculator
import app.aaps.plugins.aps.openAPSAIMI.pkpd.IsfFusion
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IsfStatePersistenceTest {
    private val now = 1_800_000_000_000L
    private val context = "profile-and-settings"
    private val values = mutableMapOf<String, String>()
    private val sp = mockk<SP>(relaxed = true).also { sp ->
        every { sp.getStringOrNull(any<String>(), null) } answers { values[firstArg<String>()] }
        every { sp.putString(any<String>(), any()) } answers { values[firstArg()] = secondArg(); Unit }
    }
    private val logger = mockk<AAPSLogger>(relaxed = true)
    private fun store() = IsfStateStore(sp, logger)
    private fun state() = AdaptiveIsfState(
        timestamp = now, context = context, kalman = KalmanIsfState(42.0, 1.2),
        blender = RateLimitedIsfState(45.0, now), adjustment = RateLimitedIsfState(49.0, now),
        tddEma = 38.0, pkpdScale = 1.1
    )

    @Test fun stateRoundTripsAcrossStoreRecreation() {
        store().save(state())
        assertEquals(state(), store().adaptive(context, now + 60_000))
    }

    @Test fun changedProfileOrSettingsCannotRestoreOldFilter() {
        store().save(state())
        assertNull(store().adaptive("different-profile", now))
    }

    @Test fun staleFutureAndDifferentSchemaAreRejected() {
        store().save(state())
        assertNull(store().adaptive(context, now + ISF_STATE_MAX_AGE_MS + 1))
        assertNull(store().adaptive(context, now - 1))
        store().save(state().copy(schema = 2))
        assertNull(store().adaptive(context, now))
    }

    @Test fun corruptAndInvalidStateFallBackWithoutThrowing() {
        values["aimi_adaptive_isf_v1"] = "broken-json"
        assertNull(store().adaptive(context, now))
        store().save(state().copy(tddEma = -1.0))
        assertNull(store().adaptive(context, now))
        assertFalse(state().copy(kalman = KalmanIsfState(Double.NaN, 1.0)).isUsable(context, now))
        assertFalse(state().copy(adjustment = RateLimitedIsfState(40.0, now + 1)).isUsable(context, now))
    }

    @Test fun filtersContinueExactlyInsteadOfReinitializing() {
        val preferences = mockk<Preferences>(relaxed = true)
        val tdd = mockk<TddCalculator>(relaxed = true)
        every { preferences.get(DoubleKey.OApsAIMITDD7) } returns 40.0
        every { tdd.averageTDD(any()) } returns null
        fun kalman() = KalmanISFCalculator(tdd, preferences, logger)
        val originalKalman = kalman()
        val originalBlender = IsfBlender()
        val originalAdjustment = IsfAdjustmentEngine()
        repeat(12) { i ->
            originalKalman.calculateISF(110.0 + i * 5, 2.0, 3.0)
            originalBlender.blend(50.0, 30.0, 0.7, now - (11 - i) * 300_000)
            originalAdjustment.compute(165.0, 40.0, 55.0, 0.1, 0.2, now - (11 - i) * 300_000)
        }
        store().save(state().copy(
            kalman = originalKalman.snapshot()!!, blender = originalBlender.snapshot()!!,
            adjustment = originalAdjustment.snapshot()!!
        ))
        val saved = store().adaptive(context, now + 300_000)!!
        val restoredKalman = kalman().also { it.restore(saved.kalman) }
        val restoredBlender = IsfBlender().also { it.restore(saved.blender) }
        val restoredAdjustment = IsfAdjustmentEngine().also { it.restore(saved.adjustment) }
        assertEquals(originalKalman.calculateISF(180.0, 5.0, 3.0), restoredKalman.calculateISF(180.0, 5.0, 3.0), 1e-12)
        val expected = originalBlender.blend(50.0, 30.0, 0.7, now + 300_000)
        assertEquals(expected, restoredBlender.blend(50.0, 30.0, 0.7, now + 300_000), 1e-12)
        assertNotEquals(saved.blender.isf, expected, "Restoration must not freeze adaptation")
        assertEquals(
            originalAdjustment.compute(180.0, 42.0, 55.0, 0.1, 0.2, now + 300_000),
            restoredAdjustment.compute(180.0, 42.0, 55.0, 0.1, 0.2, now + 300_000), 1e-12
        )
    }

    @Test fun foodAverageIsIdenticalAfterRestartAndDatabaseReplay() {
        val original = IsfHistory()
        val samples = (0..15).map { IsfSample(now - it * 300_000, 120.0 + it, 40.0 + it) }
        samples.forEach { original.add(it, now) }
        store().save(original.snapshot(now))
        val restored = IsfHistory().also { it.restore(store().history(), now) }
        samples.reversed().forEach { restored.add(it, now) }
        assertEquals(original.snapshot(now), restored.snapshot(now))
        assertEquals(original.average(now), restored.average(now))
    }

    @Test fun newestSampleWinsRegardlessOfDatabaseOrdering() {
        val history = IsfHistory()
        history.add(IsfSample(now, 120.0, 42.0), now)
        history.add(IsfSample(now + 1000, 120.0, 43.0), now + 1000)
        history.add(IsfSample(now, 120.0, 41.0), now + 1000)
        assertEquals(43.0, history.average(now + 1000))
    }

    @Test fun foodHistoryRejectsExpiredInvalidAndFutureSamples() {
        val history = IsfHistory()
        history.add(IsfSample(now - ISF_STATE_MAX_AGE_MS - 1, 120.0, 40.0), now)
        history.add(IsfSample(now + 1, 120.0, 40.0), now)
        history.add(IsfSample(now, 120.0, Double.NaN), now)
        assertNull(history.average(now))
        history.add(IsfSample(now, 120.0, 45.0), now)
        assertEquals(45.0, history.average(now))
        assertNull(history.average(now + ISF_STATE_MAX_AGE_MS + 1))
    }

    @Test fun fusionRestoresItsOwnBaseNotTheFinalActivityAdjustedIsf() {
        val fusion = IsfFusion()
        val base = fusion.fused(50.0, 45.0, 1.1)
        store().saveFusion("decision", FusionIsfState(context = context, value = RateLimitedIsfState(base, now)))
        val restored = IsfFusion().also { it.restore(store().fusion("decision", context, now + 300_000)?.value?.isf) }
        assertEquals(fusion.fused(60.0, 55.0, 1.1), restored.fused(60.0, 55.0, 1.1), 1e-12)
        assertNull(store().fusion("plugin", context, now + 300_000))
    }
}
