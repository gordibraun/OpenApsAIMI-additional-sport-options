package app.aaps.core.objects.wizard

import app.aaps.core.interfaces.aps.CarbSplit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CarbSplitTest {

    @Test
    fun `one part keeps its own type, a clear majority sets the tone, a mix is balanced`() {
        assertEquals("fast", CarbSplit(fast = 25).dominantType)
        assertEquals("slow", CarbSplit(slow = 40).dominantType)
        assertEquals("slow", CarbSplit(fast = 5, slow = 45).dominantType)
        assertEquals("balanced", CarbSplit(fast = 25, balanced = 30).dominantType)
        assertEquals("balanced", CarbSplit(fast = 30, slow = 30).dominantType)
        assertEquals("balanced", CarbSplit().dominantType)
    }

    @Test
    fun `parts come dominant first and skip empty ones`() {
        assertEquals(listOf("slow" to 45, "fast" to 5), CarbSplit(fast = 5, slow = 45).parts())
        assertEquals(listOf("balanced" to 30, "fast" to 25), CarbSplit(fast = 25, balanced = 30).parts())
        assertEquals(listOf("fast" to 30, "slow" to 30), CarbSplit(fast = 30, slow = 30).parts())
    }

    @Test
    fun `handled grams go to the fast part first`() {
        val split = CarbSplit(fast = 20, balanced = 40, slow = 10)
        assertEquals(mapOf("fast" to 20, "balanced" to 10, "slow" to 0), split.allocateHandled(30))
        assertEquals(mapOf("fast" to 20, "balanced" to 40, "slow" to 10), split.allocateHandled(70))
        assertEquals(mapOf("fast" to 20, "balanced" to 40, "slow" to 10), split.allocateHandled(500))
        assertEquals(mapOf("fast" to 0, "balanced" to 0, "slow" to 0), split.allocateHandled(0))
    }

    @Test
    fun `scaling keeps the proportions and lands on the new total`() {
        val scaled = CarbSplit(fast = 12, balanced = 58).scaledTo(80)
        assertEquals(80, scaled.total)
        assertEquals(14, scaled.fast)
        assertEquals(66, scaled.balanced)
        assertEquals(CarbSplit(balanced = 30), CarbSplit().scaledTo(30))
        assertEquals(CarbSplit(), CarbSplit(fast = 10).scaledTo(0))
    }

    @Test
    fun `the words a person reads`() {
        assertEquals("12 быстрых, 58 обычных", CarbSplit(fast = 12, balanced = 58).describe())
        assertEquals("40 медленных", CarbSplit(slow = 40).describe())
        assertTrue(CarbSplit().describe().isEmpty())
        assertEquals(CarbSplit(slow = 7), CarbSplit.single("SLOW", 7))
        assertEquals(CarbSplit(balanced = 7), CarbSplit.single("normal", 7))
    }
}
