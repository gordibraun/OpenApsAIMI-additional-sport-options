package app.aaps.pump.combowatch.executor

import app.aaps.pump.combowatch.protocol.Outcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandJournalTest {

    @Test fun `a started command counts as unresolved until it finishes`() {
        val journal = SimpleCommandJournal()
        journal.markStarted("a", 1L)
        assertTrue(journal.hasUnresolved())
        assertNull(journal.recordedOutcome("a"))

        journal.markFinished("a", Outcome.DONE, null)
        assertFalse(journal.hasUnresolved())
        assertEquals(Outcome.DONE, journal.recordedOutcome("a")?.outcome)
    }

    @Test fun `an unknown outcome stays unresolved, because the pump has not been read back`() {
        val journal = SimpleCommandJournal()
        journal.markStarted("a", 1L)
        journal.markFinished("a", Outcome.UNKNOWN, "link lost")
        assertTrue(journal.hasUnresolved())
        assertEquals("link lost", journal.unresolved()?.reason)
    }

    @Test fun `a refusal is resolved, since nothing was sent to the pump`() {
        val journal = SimpleCommandJournal()
        journal.markStarted("a", 1L)
        journal.markFinished("a", Outcome.REFUSED, "no lease")
        assertFalse(journal.hasUnresolved())
    }

    @Test fun `every change is persisted, so a crash right after starting is remembered`() {
        val saved = mutableListOf<List<CommandJournal.Entry>>()
        val journal = SimpleCommandJournal(persist = { saved.add(it) })
        journal.markStarted("a", 1L)
        assertEquals(1, saved.size)
        assertNull(saved.last().single().outcome)

        journal.markFinished("a", Outcome.DONE, null)
        assertEquals(2, saved.size)
        assertEquals(Outcome.DONE, saved.last().single().outcome)
    }

    @Test fun `a journal restored from storage still blocks on what it was doing`() {
        val restored = SimpleCommandJournal(
            initial = listOf(CommandJournal.Entry("a", 1L, outcome = null, reason = null))
        )
        assertTrue(restored.hasUnresolved())
        assertEquals("a", restored.unresolved()?.id)
    }

    @Test fun `trimming drops finished entries and never the unresolved one`() {
        val journal = SimpleCommandJournal(maxEntries = 3)
        journal.markStarted("old", 1L)
        journal.markFinished("old", Outcome.UNKNOWN, "link lost")
        repeat(10) {
            journal.markStarted("c$it", it.toLong())
            journal.markFinished("c$it", Outcome.DONE, null)
        }
        assertNotNull(journal.entry("old"))
        assertEquals("old", journal.unresolved()?.id)
    }
}
