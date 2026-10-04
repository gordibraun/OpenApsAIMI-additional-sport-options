package app.aaps.combobench

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.aaps.combobench.controller.ControllerHost
import app.aaps.combobench.controller.LeadershipLog
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The plain story of the basal, newest first: who led it when and why that changed (the phone by
 * its lease, the watch once the phone fell silent), and every decision the watch made by itself -
 * the rule that asked, what was set, or the rule that held it back. The phone's own decisions are
 * not here; the phone explains those itself.
 */
class ControlLogActivity : Activity() {

    private lateinit var column: LinearLayout
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.MANUAL_TARGET) { finish(); return }
        val dp = resources.displayMetrics.density
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * dp).toInt(), (18 * dp).toInt(), (22 * dp).toInt(), (72 * dp).toInt())
        }
        val scroll = ScrollView(this).apply {
            addView(column); isFocusableInTouchMode = true
            setOnGenericMotionListener { _, event ->
                if (event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                    scrollBy(0, (-event.getAxisValue(MotionEvent.AXIS_SCROLL) * ViewConfiguration.get(this@ControlLogActivity).scaledVerticalScrollFactor).toInt())
                    true
                } else false
            }
        }
        setContentView(scroll)
        scroll.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        column.removeAllViews()
        val dp = resources.displayMetrics.density
        fun line(text: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (5 * dp).toInt(), 0, (5 * dp).toInt())
            column.addView(this)
        }
        line("Часы и телефон", 18f, bold = true)
        val host = ControllerHost.get(this)
        val now = System.currentTimeMillis()
        runCatching { host.faceFacts() }.getOrNull()?.let { line(summary(it, now), 13f, Color.rgb(255, 195, 50)) }
        val items = mutableListOf<Triple<Long, String, Int>>()
        for (entry in runCatching { host.leadershipEntries() }.getOrDefault(emptyList()))
            items += Triple(entry.atEpochMs, "▶ ${clock.format(Date(entry.atEpochMs))} ${entry.text}", Color.rgb(93, 193, 238))
        for (entry in runCatching { host.autonomy.journal() }.getOrDefault(emptyList()))
            items += Triple(entry.optLong("at"), decisionLine(entry), Color.WHITE)
        if (items.isEmpty()) line("Записей ещё нет: телефон пока не передавал управление", 12f, Color.GRAY)
        items.sortedByDescending { it.first }.take(LINES).forEach { (_, text, color) -> line(text, 12f, color) }
    }

    private fun summary(facts: app.aaps.combobench.controller.FaceFacts, now: Long): String {
        val minutes = { since: Long -> ((now - since) / 60_000L).coerceAtLeast(0L) }
        return when (facts.leader) {
            LeadershipLog.Leader.PHONE           ->
                if (facts.phoneHeardEpochMs > 0L) "Сейчас ведёт телефон; на связи ${minutes(facts.phoneHeardEpochMs)} мин назад." else "Сейчас ведёт телефон."
            LeadershipLog.Leader.WATCH           ->
                "Сейчас базал ведут часы, уже ${minutes(facts.leaderSinceEpochMs)} мин." + heardSince(facts)
            LeadershipLog.Leader.WATCH_OBSERVING ->
                "Часы одни ${minutes(facts.leaderSinceEpochMs)} мин и только наблюдают." + heardSince(facts)
            LeadershipLog.Leader.NOBODY          -> "Телефона нет, часы базал не ведут: см. последнюю запись." + heardSince(facts)
        }
    }

    private fun heardSince(facts: app.aaps.combobench.controller.FaceFacts): String =
        if (facts.phoneHeardEpochMs > 0L) " Телефона не слышно с ${clock.format(Date(facts.phoneHeardEpochMs))}." else ""

    /** One decision of the watch: the rule that asked, and what came of it. */
    private fun decisionLine(entry: JSONObject): String {
        val when_ = clock.format(Date(entry.optLong("at")))
        val text = entry.optString("text")
        val action = entry.optString("action")
        val outcome = when {
            action == "ACTIVITY"                     -> ""
            action == "LEAVE"                        -> {
                val hold = entry.optString("hold").takeIf { it.isNotBlank() && it != "null" }
                if (hold != null) " Оставили как есть: $hold." else " Ничего менять не нужно."
            }

            entry.optString("mode") == "OBSERVE"     -> " Хотели ${russian(action)}, но в режиме наблюдения помпу не трогали."
            entry.optBoolean("done")                 -> " Поставили ${russian(action)} ✓"
            else                                     -> " Хотели ${russian(action)}, не удалось: ${entry.optString("reason").ifBlank { "помпа не ответила" }} ✗"
        }
        return "$when_ $text.$outcome"
    }

    /** "TBR 50 % 15 min" as the owner would say it. */
    private fun russian(action: String): String {
        val match = Regex("TBR (\\d+) % (\\d+) min").find(action) ?: return action
        return "базал ${match.groupValues[1]} % на ${match.groupValues[2]} мин"
    }

    private companion object {

        const val LINES = 80
    }
}
