package app.aaps.combobench.controller

import app.aaps.pump.combowatch.protocol.RegulationSnapshot

/**
 * What the watch makes of the phone's rules on trial: which of their notes are new, which of them buzz, and
 * how each is headed. The rules themselves live on the phone; see TrialRules there.
 */
internal object TrialNotes {

    /** Notes the watch has not seen yet, oldest first; [seenUpToEpochMs] is the time of the last one it has. */
    fun fresh(trials: RegulationSnapshot.Trials, seenUpToEpochMs: Long): List<RegulationSnapshot.Trials.Note> =
        trials.notes.filter { it.atEpochMs > seenUpToEpochMs }.sortedBy { it.atEpochMs }

    /** The owner switching a rule on or off is a note too, and never a reason to buzz. */
    fun isSwitch(note: RegulationSnapshot.Trials.Note): Boolean = note.text.endsWith("владельцем")

    /** Only the early warning buzzes, and only while the owner has that switched on. */
    fun buzzes(trials: RegulationSnapshot.Trials, note: RegulationSnapshot.Trials.Note): Boolean =
        note.key == RegulationSnapshot.Trials.KEY_WARN && trials.warnMode == RegulationSnapshot.Trials.WARN_BUZZ && !isSwitch(note)

    fun title(note: RegulationSnapshot.Trials.Note, buzz: Boolean): String = when {
        isSwitch(note)                                   -> "🧪 Пробы: изменено на телефоне"
        note.key == RegulationSnapshot.Trials.KEY_WARN   -> if (buzz) "🧪 Прогноз низкий: проверьте сахар" else "🧪 Проба: низкий прогноз (запись)"
        note.key == RegulationSnapshot.Trials.KEY_CAP    -> "🧪 Проба: микродоза ограничена"
        else                                             -> "🧪 Проба"
    }

    /** The two lines of the journal screen that say which rules are on. */
    fun state(trials: RegulationSnapshot.Trials): List<String> = listOf(
        "• Первая микродоза на неподтверждённом скачке ≤ 0,5 ЕД: " + (if (trials.capOn) "включено" else "выключено"),
        "• Раннее предупреждение о низком прогнозе: " + when (trials.warnMode) {
            RegulationSnapshot.Trials.WARN_BUZZ   -> "запись и жужжание"
            RegulationSnapshot.Trials.WARN_RECORD -> "только запись"
            else                                  -> "выключено"
        }
    )
}
