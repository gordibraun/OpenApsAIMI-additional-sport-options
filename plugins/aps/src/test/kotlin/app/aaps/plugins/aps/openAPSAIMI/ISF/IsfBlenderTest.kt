package app.aaps.plugins.aps.openAPSAIMI.ISF

import org.junit.Assert.assertEquals
import org.junit.jupiter.api.Test

class IsfBlenderTest {

    @Test
    fun `test blend rate limiting`() {
        val blender = IsfBlender(maxStepPctPerLoop = 0.05, maxStepPctPerHour = 0.20)
        val now = 1000000L
        
        // No elapsed time: initialize from the slow estimate, not the fast target.
        val result1 = blender.blend(50.0, 100.0, 0.5, now)
        assertEquals(50.0, result1, 0.01)
        
        // Immediate next call with same time: should be clamped to previous
        // Even if inputs change drastically
        val result2 = blender.blend(100.0, 200.0, 0.5, now)
        assertEquals(50.0, result2, 0.01)
        
        // Both limits apply: after an hour the per-loop 5% limit is tighter than 20%/h.
        val later = now + 3600000L
        val result3 = blender.blend(100.0, 200.0, 0.5, later)
        assertEquals(52.5, result3, 0.01)
    }
}
