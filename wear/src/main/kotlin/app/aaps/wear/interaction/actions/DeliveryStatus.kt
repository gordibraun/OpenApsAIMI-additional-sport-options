package app.aaps.wear.interaction.actions

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Doses the watch has asked the phone to deliver, and what the phone said back, for the status
 * line on the input screens.
 *
 * The phone answers a confirmed request minutes later, once the pump has finished, with a plain
 * "Готово" or "Не выполнено" that used to open a screen of its own on top of whatever was being
 * entered and wipe the calculation in progress (19 Oct 2026). The answer now settles the oldest
 * request still in flight - the phone's queue delivers them in order - and the screens show the
 * state: sent, delivered, or failed. Kept in preferences so an answer that arrives while no screen
 * is open still shows on the next one.
 */
object DeliveryStatus {

    enum class State { SENT, DONE, FAILED }

    class Entry(
        val sentAtEpochMs: Long,
        val decision: String,
        var state: State,
        var answer: String = "",
        var answeredAtEpochMs: Long = 0L
    )

    private const val PREFS = "watch_delivery_status"
    private const val KEY_ENTRIES = "entries"
    private const val DONE_SHOWN_MS = 3 * 60_000L
    private const val FAILED_SHOWN_MS = 10 * 60_000L
    private const val MAX_LINE_CHARS = 56
    private const val NO_ANSWER_WARN_MS = 10 * 60_000L
    private const val NO_ANSWER_GIVE_UP_MS = 60 * 60_000L
    private const val MAX_ENTRIES = 4
    private const val SUCCESS_TITLE = "Готово"

    /** A refusal is the phone's answer at once, before the pump is asked; a delivery takes longer than this. */
    internal const val REFUSAL_IS_PROMPT_MS = 15_000L

    /** How long a confirmed dose is taken to be on its way: the phone drops a bolus the watch has not started within two minutes. */
    const val IN_FLIGHT_MAX_MS = 150_000L
    private val UNITS = Regex("""([0-9]+(?:[.,][0-9]+)?)\s*Е""")

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners.add(listener) }

    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    /** The one line of the phone's confirmation text worth keeping: the dose if there is one, else the first line. */
    fun shortDecision(message: String): String {
        val lines = message.substringBefore("\n\n").lines().map { it.trim() }.filter { it.isNotEmpty() }
        return (lines.firstOrNull { it.startsWith("Подать инсулин") } ?: lines.firstOrNull() ?: message.trim()).trimEnd('.')
    }

    internal fun shortAnswer(message: String): String {
        val lines = message.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return (lines.firstOrNull { it.contains("инсулина") } ?: lines.firstOrNull() ?: message.trim()).trimEnd('.')
    }

    @Synchronized fun sent(context: Context, decision: String) {
        val now = System.currentTimeMillis()
        save(context, (live(load(context), now) + Entry(now, decision, State.SENT)).takeLast(MAX_ENTRIES))
        notifyListeners()
    }

    /**
     * The request an answer belongs to. The phone's answers carry no name, so: the one request in
     * flight if there is one; else the one whose dose the answer repeats; else, for a refusal that
     * comes right after a confirmation, that confirmation - a refusal is prompt, while the dose ahead
     * of it is still being delivered (12.11.2026: the refusal of a second calculation was booked as
     * the first dose having failed, and the first dose's «Готово» as the second having gone through);
     * else the oldest, because the phone's queue delivers in order.
     */
    internal fun pick(sent: List<Entry>, title: String, message: String, now: Long): Entry? {
        if (sent.size <= 1) return sent.firstOrNull()
        val amount = UNITS.find(shortAnswer(message))?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        if (amount != null) {
            val same = sent.filter { entry ->
                UNITS.find(entry.decision)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()?.let { kotlin.math.abs(it - amount) < 0.0001 } == true
            }
            if (same.size == 1) return same.first()
        }
        val newest = sent.maxByOrNull { it.sentAtEpochMs }
        if (title != SUCCESS_TITLE && newest != null && now - newest.sentAtEpochMs in 0..REFUSAL_IS_PROMPT_MS) return newest
        return sent.minByOrNull { it.sentAtEpochMs }
    }

    /** The dose confirmed a moment ago and not answered yet, if any: a new calculation waits for it. */
    @Synchronized fun inFlight(context: Context, now: Long = System.currentTimeMillis()): Entry? =
        live(load(context), now).filter { it.state == State.SENT && now - it.sentAtEpochMs in 0 until IN_FLIGHT_MAX_MS }
            .minByOrNull { it.sentAtEpochMs }

    /** Settles the request the answer belongs to, see [pick]. False when nothing was waiting. */
    @Synchronized fun answered(context: Context, title: String, message: String): Boolean {
        val now = System.currentTimeMillis()
        val entries = live(load(context), now)
        val waiting = pick(entries.filter { it.state == State.SENT }, title, message, now)
        if (waiting == null) {
            save(context, entries)
            return false
        }
        waiting.state = if (title == SUCCESS_TITLE) State.DONE else State.FAILED
        waiting.answer = shortAnswer(message)
        waiting.answeredAtEpochMs = now
        save(context, entries)
        notifyListeners()
        return true
    }

    /** What the status line says now, or null when there is nothing to tell. */
    @Synchronized fun line(context: Context): String? {
        val now = System.currentTimeMillis()
        val entries = live(load(context), now)
        val inFlight = entries.filter { it.state == State.SENT }
        if (inFlight.isNotEmpty()) {
            val first = inFlight.first()
            val minutes = (now - first.sentAtEpochMs) / 60_000L
            val queued = if (inFlight.size > 1) " (+${inFlight.size - 1} в очереди)" else ""
            val late = if (now - first.sentAtEpochMs >= NO_ANSWER_WARN_MS) " — телефон не отвечает $minutes мин" else ""
            return "⏳ Подаётся: ${first.decision}$queued$late"
        }
        val last = entries.filter { it.state != State.SENT }.maxByOrNull { it.answeredAtEpochMs } ?: return null
        // One short line: the phone's full text is in the notification. A long refusal wrapped to
        // three lines here and pushed the bottom button off the round screen (21 Oct 2026).
        val text = if (last.state == State.DONE) "✓ ${last.answer}" else "✗ Не подано: ${last.answer.removeSuffix(" Инсулин не отправлен").trimEnd('.')}"
        return if (text.length <= MAX_LINE_CHARS) text else text.take(MAX_LINE_CHARS - 1) + "…"
    }

    private fun live(entries: List<Entry>, now: Long): MutableList<Entry> = entries.filter { entry ->
        when (entry.state) {
            State.SENT   -> now - entry.sentAtEpochMs < NO_ANSWER_GIVE_UP_MS
            State.DONE   -> now - entry.answeredAtEpochMs < DONE_SHOWN_MS
            State.FAILED -> now - entry.answeredAtEpochMs < FAILED_SHOWN_MS
        }
    }.toMutableList()

    private fun notifyListeners() = listeners.forEach { runCatching { it() } }

    private fun load(context: Context): MutableList<Entry> = runCatching {
        val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENTRIES, "[]") ?: "[]")
        MutableList(array.length()) { index ->
            val json = array.getJSONObject(index)
            Entry(
                json.getLong("sentAt"), json.getString("decision"), State.valueOf(json.getString("state")),
                json.optString("answer", ""), json.optLong("answeredAt", 0L)
            )
        }
    }.getOrDefault(mutableListOf())

    private fun save(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach {
            array.put(
                JSONObject().put("sentAt", it.sentAtEpochMs).put("decision", it.decision).put("state", it.state.name)
                    .put("answer", it.answer).put("answeredAt", it.answeredAtEpochMs)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ENTRIES, array.toString()).apply()
    }
}
