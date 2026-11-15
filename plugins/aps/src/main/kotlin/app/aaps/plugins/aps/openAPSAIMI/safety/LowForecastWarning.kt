package app.aaps.plugins.aps.openAPSAIMI.safety

/**
 * ON TRIAL since 15.11.2026, at the owner's «да»: the loop's own forecast going below [LOW_MGDL] within
 * [HORIZON_MIN] on two readings in a row is said out loud, long before glucose is low.
 *
 * Night of 15.11.2026: three minutes after a 2.4 U microbolus the loop forecast 46, and went on forecasting
 * 40-46 for a quarter of an hour, with «8-9 g within 120 min» - and nobody was told until the low itself, an
 * hour and a half later. This rule would have spoken at 00:33, at a glucose of 125.
 *
 * It is recorded by default and buzzes the wrist only if the owner switches that on, because of what the
 * replay over four weeks of this owner's data showed: it would have fired 66 times, a reading below 70
 * followed 8 of them within two and a half hours, and of the 24 real lows it would have announced 6; at night
 * 14 times, 3 of them followed by a low. The forecast's far tail after a meal bolus is pessimistic far more
 * often than it is right. What it is worth is to be seen from the record before anybody is woken by it.
 */
object LowForecastWarning {

    /** One loop run: when, the reading it was made on, and its forecast every five minutes from now on. */
    data class Run(val atEpochMs: Long, val readingAtEpochMs: Long, val forecastMgdl: List<Int>)

    /** What is carried from run to run. */
    data class State(
        /** The reading the last low forecast was made on; zero after a run that forecast no low. */
        val lowReadingAtEpochMs: Long = 0L,
        /** When the forecast last counted as low twice in a row; a new warning comes only after [QUIET_MS] without. */
        val warnedLastAtEpochMs: Long = 0L
    )

    data class Warning(val minMgdl: Int, val minInMinutes: Int, val below70InMinutes: Int?)

    data class Verdict(val state: State, val warning: Warning?)

    const val LOW_MGDL = 55
    const val HORIZON_MIN = 180

    /** Two readings count as in a row when no more than this lies between them. */
    private const val IN_A_ROW_MS = 12 * 60_000L

    /** After this long without a low forecast twice in a row, the next one is a new warning. */
    const val QUIET_MS = 30 * 60_000L

    fun evaluate(state: State, run: Run): Verdict {
        val points = run.forecastMgdl.take(HORIZON_MIN / 5 + 1)
        val min = points.minOrNull()
        if (min == null || min >= LOW_MGDL) return Verdict(state.copy(lowReadingAtEpochMs = 0L), null)
        // The same reading looked at again says nothing new.
        if (state.lowReadingAtEpochMs == run.readingAtEpochMs) return Verdict(state, null)
        val inARow = state.lowReadingAtEpochMs != 0L && run.readingAtEpochMs - state.lowReadingAtEpochMs in 1..IN_A_ROW_MS
        if (!inARow) return Verdict(state.copy(lowReadingAtEpochMs = run.readingAtEpochMs), null)
        val fresh = state.warnedLastAtEpochMs == 0L || run.atEpochMs - state.warnedLastAtEpochMs > QUIET_MS
        val next = State(lowReadingAtEpochMs = run.readingAtEpochMs, warnedLastAtEpochMs = run.atEpochMs)
        val warning = if (fresh) Warning(min, points.indexOf(min) * 5, points.indexOfFirst { it < 70 }.takeIf { it >= 0 }?.let { it * 5 }) else null
        return Verdict(next, warning)
    }
}
