package app.aaps.combobench.controller

import app.aaps.pump.combowatch.protocol.RegulationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's rules on trial as the watch shows them: new notes once, a buzz only for the early warning and only when switched on. */
class TrialNotesTest {

    private val cap = RegulationSnapshot.Trials.Note(RegulationSnapshot.Trials.KEY_CAP, 1_000L, "Проба: микродоза 2,4 → 0,5 ЕД. Подъём +14 за 5 мин виден только после пропущенного показания")
    private val warn = RegulationSnapshot.Trials.Note(RegulationSnapshot.Trials.KEY_WARN, 2_000L, "Проба: прогноз цикла опускается до 40 через 110 мин")
    private val switched = RegulationSnapshot.Trials.Note(RegulationSnapshot.Trials.KEY_WARN, 3_000L, "Раннее предупреждение о низком прогнозе: запись и жужжание на часах владельцем")

    private fun trials(mode: String, vararg notes: RegulationSnapshot.Trials.Note) = RegulationSnapshot.Trials(capOn = true, warnMode = mode, notes = notes.toList())

    @Test fun onlyNotesNewerThanTheLastSeenAreNewOldestFirst() {
        val all = trials(RegulationSnapshot.Trials.WARN_RECORD, warn, cap)
        assertEquals(listOf(cap, warn), TrialNotes.fresh(all, 0L))
        assertEquals(listOf(warn), TrialNotes.fresh(all, 1_000L))
        assertTrue(TrialNotes.fresh(all, 2_000L).isEmpty())
    }

    @Test fun recordedOnlyNothingBuzzes() {
        val recordOnly = trials(RegulationSnapshot.Trials.WARN_RECORD, cap, warn)
        assertFalse(TrialNotes.buzzes(recordOnly, warn))
        assertFalse(TrialNotes.buzzes(recordOnly, cap))
        assertEquals("🧪 Проба: низкий прогноз (запись)", TrialNotes.title(warn, buzz = false))
    }

    @Test fun switchedOnTheEarlyWarningBuzzesAndNothingElseDoes() {
        val buzzing = trials(RegulationSnapshot.Trials.WARN_BUZZ, cap, warn, switched)
        assertTrue(TrialNotes.buzzes(buzzing, warn))
        assertFalse(TrialNotes.buzzes(buzzing, cap))
        // The owner flipping the switch is news, not an alarm.
        assertFalse(TrialNotes.buzzes(buzzing, switched))
        assertEquals("🧪 Пробы: изменено на телефоне", TrialNotes.title(switched, buzz = false))
        assertEquals("🧪 Прогноз низкий: проверьте сахар", TrialNotes.title(warn, buzz = true))
    }

    @Test fun theJournalScreenSaysWhichRulesAreOn() {
        val lines = TrialNotes.state(RegulationSnapshot.Trials(capOn = true, warnMode = RegulationSnapshot.Trials.WARN_RECORD))
        assertTrue(lines[0].endsWith("включено"))
        assertTrue(lines[1].endsWith("только запись"))
        val off = TrialNotes.state(RegulationSnapshot.Trials(capOn = false, warnMode = RegulationSnapshot.Trials.WARN_OFF))
        assertTrue(off[0].endsWith("выключено"))
        assertTrue(off[1].endsWith("выключено"))
    }
}
