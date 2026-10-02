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

    @Test fun `giving a pump up releases the hold without claiming to know what happened`() {
        val saved = mutableListOf<List<CommandJournal.Entry>>()
        val journal = SimpleCommandJournal(persist = { saved.add(it) })
        journal.markStarted("unknown", 1L)
        journal.markFinished("unknown", Outcome.UNKNOWN, "link lost")
        journal.markStarted("crashed", 2L)
        journal.markStarted("done", 3L)
        journal.markFinished("done", Outcome.DONE, null)
        saved.clear()

        val given = journal.abandonUnresolved("pump unpaired")
        assertEquals(listOf("unknown", "crashed"), given.map { it.id })
        assertFalse(journal.hasUnresolved())

        // The answer to a resend stays "unknown": nothing was learned about either of them.
        assertEquals(Outcome.UNKNOWN, journal.recordedOutcome("unknown")?.outcome)
        assertEquals("link lost; pump unpaired", journal.recordedOutcome("unknown")?.reason)
        assertEquals(Outcome.UNKNOWN, journal.recordedOutcome("crashed")?.outcome)
        assertEquals("pump unpaired", journal.recordedOutcome("crashed")?.reason)
        // The one that had finished is untouched.
        assertEquals(Outcome.DONE, journal.recordedOutcome("done")?.outcome)
        assertFalse(journal.entry("done")!!.abandoned)

        assertEquals(1, saved.size)
        assertTrue(saved.last().first { it.id == "unknown" }.abandoned)
    }

    @Test fun `giving up with nothing unresolved changes nothing`() {
        val saved = mutableListOf<List<CommandJournal.Entry>>()
        val journal = SimpleCommandJournal(persist = { saved.add(it) })
        journal.markStarted("done", 1L)
        journal.markFinished("done", Outcome.DONE, null)
        saved.clear()
        assertTrue(journal.abandonUnresolved("pump unpaired").isEmpty())
        assertTrue(saved.isEmpty())
    }

    @Test fun `an abandoned entry restored from storage does not hold therapy back again`() {
        val restored = SimpleCommandJournal(
            initial = listOf(CommandJournal.Entry("a", 1L, outcome = Outcome.UNKNOWN, reason = "pump unpaired", abandoned = true))
        )
        assertFalse(restored.hasUnresolved())
        assertNull(restored.unresolved())
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
