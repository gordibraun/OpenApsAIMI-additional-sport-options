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
 *
 * 20.11.2026: between «Подтвердить» and the phone's answer - a minute and forty seconds that day - the
 * watch showed nothing but a line cut short («⏳ Подаётся: Подать инсулин: 2.…») over an input form
 * with all its buttons live, which the owner read as "it did not go through, enter it again". So the
 * state is now given as a [Progress] that the screens show large: a screen of its own right after the
 * confirmation ([DeliveryActivity]), and the input forms closed behind it while the dose is on its way.
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
    private const val NO_ANSWER_GIVE_UP_MS = 60 * 60_000L
    private const val MAX_ENTRIES = 4
    private const val SUCCESS_TITLE = "Готово"

    /** A refusal is the phone's answer at once, before the pump is asked; a delivery takes longer than this. */
    internal const val REFUSAL_IS_PROMPT_MS = 15_000L

    /** How long a confirmed dose is taken to be on its way: the phone drops a bolus the watch has not started within two minutes. */
    const val IN_FLIGHT_MAX_MS = 150_000L
    private val UNITS = Regex("""([0-9]+(?:[.,][0-9]+)?)\s*Е""")

    /** Where the request the screens tell about stands. */
    enum class Phase {

        /** Confirmed and on its way: the pump is being reached, or is delivering. */
        IN_FLIGHT,

        /** Longer than a dose takes and no answer: it may have been given, or not. */
        NO_ANSWER,
        DONE,
        FAILED
    }

    /** What to tell about the request confirmed last. [unitsU] is the dose, when the request is one. */
    class Progress(
        val phase: Phase, val unitsU: Double?, val decision: String, val answer: String,
        val sentAtEpochMs: Long, val answeredAtEpochMs: Long, val queued: Int
    ) {

        /** A dose of insulin, as opposed to carbohydrates or a walk recorded through the phone. */
        val isDose: Boolean get() = (unitsU ?: 0.0) > 0.0

        /** True while the answer is still awaited: nothing is known to have failed, and nothing to have landed. */
        val pending: Boolean get() = phase == Phase.IN_FLIGHT || phase == Phase.NO_ANSWER
    }

    /** The dose a text of the phone's names («Подать инсулин: 2.0 Е»), if it names one. */
    internal fun unitsIn(text: String): Double? = UNITS.find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()

    /** «2,0 ЕД» */
    internal fun dose(unitsU: Double): String = String.format(java.util.Locale.US, "%.1f", unitsU).replace('.', ',') + " ЕД"

    /**
     * What the screens tell: the request on its way if there is one (the oldest, as the phone delivers in
     * order); else whichever is newer - a request that has had no answer for longer than a dose takes, or the
     * answer that came last.
     */
    internal fun progressOf(entries: List<Entry>, now: Long): Progress? {
        val sent = entries.filter { it.state == State.SENT }
        val flying = sent.filter { now - it.sentAtEpochMs in 0 until IN_FLIGHT_MAX_MS }
        flying.minByOrNull { it.sentAtEpochMs }?.let {
            return Progress(Phase.IN_FLIGHT, unitsIn(it.decision), it.decision, "", it.sentAtEpochMs, 0L, flying.size - 1)
        }
        val silent = sent.maxByOrNull { it.sentAtEpochMs }
        val answered = entries.filter { it.state != State.SENT }.maxByOrNull { it.answeredAtEpochMs }
        if (silent != null && (answered == null || silent.sentAtEpochMs > answered.answeredAtEpochMs))
            return Progress(Phase.NO_ANSWER, unitsIn(silent.decision), silent.decision, "", silent.sentAtEpochMs, 0L, 0)
        answered ?: return null
        return Progress(
            if (answered.state == State.DONE) Phase.DONE else Phase.FAILED,
            unitsIn(answered.decision) ?: unitsIn(answered.answer), answered.decision, answered.answer, answered.sentAtEpochMs, answered.answeredAtEpochMs, 0
        )
    }

    @Synchronized fun progress(context: Context, now: Long = System.currentTimeMillis()): Progress? = progressOf(live(load(context), now), now)

    /** The heading of the large status: one glance must tell "on its way" from "given" from "not given". */
    internal fun headline(p: Progress): String = when (p.phase) {
        Phase.IN_FLIGHT -> if (p.isDose) "⏳ Подаётся ${dose(p.unitsU!!)}" else "⏳ Отправлено телефону"
        Phase.NO_ANSWER -> "❓ Нет ответа"
        Phase.DONE      -> if (p.isDose) "✅ Подано ${dose(p.unitsU!!)}" else "✅ Готово"
        Phase.FAILED    -> "❌ Не подано"
    }

    /** What stands under the heading. */
    internal fun detail(p: Progress, now: Long): String {
        val seconds = ((now - p.sentAtEpochMs) / 1000L).coerceAtLeast(0L)
        val waited = if (seconds < 90) "$seconds с" else "${seconds / 60} мин"
        return when (p.phase) {
            Phase.IN_FLIGHT ->
                (if (p.isDose) "Доза уже отправлена на помпу, идёт $waited. Повторять ввод не нужно." else "${p.decision}. Идёт $waited.") +
                    (if (p.queued > 0) " Ещё в очереди: ${p.queued}." else "")

            Phase.NO_ANSWER ->
                if (p.isDose) "Телефон не ответил за $waited. Доза ${dose(p.unitsU!!)} могла быть подана. Не вводите её заново, пока не проверите помпу."
                else "Телефон не ответил за $waited: ${p.decision}."

            Phase.DONE      -> p.answer
            Phase.FAILED    -> {
                val why = p.answer.ifEmpty { "Телефон отказал" }.trimEnd('.') + "."
                // The phone's own refusals end in «Инсулин не отправлен»; others (the pump did not answer) do not say it.
                if (p.isDose && !why.contains("Инсулин не", ignoreCase = true)) "$why Инсулин не введён." else why
            }
        }
    }

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
    @Synchronized fun inFlight(context: Context, now: Long = System.currentTimeMillis()): Entry? = doseInFlight(live(load(context), now), now)

    /** Only insulin counts: carbohydrates or a walk recorded through the phone hold nothing back. */
    internal fun doseInFlight(entries: List<Entry>, now: Long): Entry? =
        entries.filter { entry ->
            entry.state == State.SENT && now - entry.sentAtEpochMs in 0 until IN_FLIGHT_MAX_MS &&
                (UNITS.find(entry.decision)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0) > 0.0
        }.minByOrNull { it.sentAtEpochMs }

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
        return lineOf(progressOf(live(load(context), now), now) ?: return null, now)
    }

    /**
     * One short line: the phone's full text is in the notification. A long refusal wrapped to three
     * lines here and pushed the bottom button off the round screen (21 Oct 2026); a line that began
     * with the phone's own heading had its dose cut off (20 Nov 2026: «⏳ Подаётся: Подать инсулин: 2.…»).
     */
    internal fun lineOf(p: Progress, now: Long): String {
        val minutes = (now - p.sentAtEpochMs) / 60_000L
        val text = when (p.phase) {
            Phase.IN_FLIGHT -> (if (p.isDose) "⏳ Подаётся ${dose(p.unitsU!!)}" else "⏳ Отправлено: ${p.decision}") + (if (p.queued > 0) " (+${p.queued} в очереди)" else "")
            Phase.NO_ANSWER -> if (p.isDose) "❓ Нет ответа о дозе ${dose(p.unitsU!!)}, $minutes мин" else "❓ Нет ответа $minutes мин: ${p.decision}"
            Phase.DONE      -> "✓ ${p.answer}"
            Phase.FAILED    -> "✗ Не подано: ${p.answer.removeSuffix(" Инсулин не отправлен").trimEnd('.')}"
        }
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

    /** For [DeliveryDebugReceiver] only: what the screens have to show, set outright. Nothing is sent anywhere. */
    @Synchronized internal fun replaceForDisplayTest(context: Context, entries: List<Entry>) {
        save(context, entries)
        notifyListeners()
    }

    /** What the screens show about a dose is written to the log under this tag whenever it changes. */
    const val LOG_TAG = "WatchDelivery"

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
