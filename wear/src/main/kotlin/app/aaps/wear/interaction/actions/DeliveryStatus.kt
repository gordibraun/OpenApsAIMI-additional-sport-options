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
    private const val FAILED_SHOWN_MS = 30 * 60_000L
    private const val NO_ANSWER_WARN_MS = 10 * 60_000L
    private const val NO_ANSWER_GIVE_UP_MS = 60 * 60_000L
    private const val MAX_ENTRIES = 4
    private const val SUCCESS_TITLE = "Готово"

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners.add(listener) }

    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    /** The one line of the phone's confirmation text worth keeping: the dose if there is one, else the first line. */
    fun shortDecision(message: String): String {
        val lines = message.substringBefore("\n\n").lines().map { it.trim() }.filter { it.isNotEmpty() }
        return (lines.firstOrNull { it.startsWith("Подать инсулин") } ?: lines.firstOrNull() ?: message.trim()).trimEnd('.')
    }

    private fun shortAnswer(message: String): String {
        val lines = message.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return (lines.firstOrNull { it.contains("инсулина") } ?: lines.firstOrNull() ?: message.trim()).trimEnd('.')
    }

    @Synchronized fun sent(context: Context, decision: String) {
        val now = System.currentTimeMillis()
        save(context, (live(load(context), now) + Entry(now, decision, State.SENT)).takeLast(MAX_ENTRIES))
        notifyListeners()
    }

    /** Settles the oldest request still awaiting an answer. False when nothing was waiting. */
    @Synchronized fun answered(context: Context, title: String, message: String): Boolean {
        val now = System.currentTimeMillis()
        val entries = live(load(context), now)
        val waiting = entries.firstOrNull { it.state == State.SENT }
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
        return if (last.state == State.DONE) "✓ ${last.answer}" else "✗ ${last.answer}"
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
