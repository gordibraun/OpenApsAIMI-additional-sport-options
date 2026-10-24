package app.aaps.combobench

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.aaps.combobench.controller.ControllerHost
import app.aaps.pump.combowatch.regulation.InsulinEffectiveness
import app.aaps.pump.combowatch.regulation.WatchRegulator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Insulin not acting: what the watch sees, and the owner's answer to it.
 *
 * Opened from the notice the watch gives when glucose sits far above what the forecasts promised
 * (see [InsulinEffectiveness]), and from the pump screen at any time - the owner may know they are
 * ill before the numbers do. The data cannot tell a failed set from a day in bed; the owner can, so
 * the screen asks, says what to do for each answer, and records the answer: a set change buys quiet
 * and a look an hour on; illness or a day spent sitting switches on the resistance mode, in which
 * the watch asks more insulin per gram and per mg/dL under wider caps, for a set time.
 */
class TriageActivity : Activity() {

    private enum class Screen { HOME, SET, RESIST, ADVICE, DONE }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var column: LinearLayout
    private var screen = Screen.HOME
    private var facts: ControllerHost.TriageFacts? = null
    private var factsFailure: String? = null
    private var doneTitle = ""
    private var doneText = ""
    private var extraPercent = 20
    private var hours = 12
    private var confirming = false
    private var busy = false
    private val cancelConfirmation = Runnable { confirming = false; render() }

    private val dp: Float get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.MANUAL_TARGET) { finish(); return }
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (18 * dp).toInt(), (24 * dp).toInt(), (72 * dp).toInt())
        }
        val scroll = ScrollView(this).apply {
            addView(column); isFocusableInTouchMode = true
            setOnGenericMotionListener { _, event ->
                if (event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                    scrollBy(0, (-event.getAxisValue(MotionEvent.AXIS_SCROLL) * ViewConfiguration.get(this@TriageActivity).scaledVerticalScrollFactor).toInt())
                    true
                } else false
            }
        }
        setContentView(scroll)
        scroll.requestFocus()
        // For checking the screens from adb on a docked watch, whose charging overlay swallows touches:
        //   am start -n <pkg>/app.aaps.combobench.TriageActivity --es screen RESIST
        intent.getStringExtra("screen")?.let { name -> runCatching { Screen.valueOf(name) }.getOrNull() }?.let { screen = it }
        render()
        loadFacts()
    }

    override fun onDestroy() {
        main.removeCallbacks(cancelConfirmation)
        worker.shutdown()
        super.onDestroy()
    }

    private fun loadFacts() {
        worker.execute {
            val loaded = runCatching { ControllerHost.get(this).triageFacts() }
            main.post {
                if (isFinishing || isDestroyed) return@post
                facts = loaded.getOrNull()
                factsFailure = loaded.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message ?: ""}" }
                render()
            }
        }
    }

    /** What the host does with the owner's answer, off the main thread; then the closing screen. */
    private fun act(title: String, text: String, action: ControllerHost.() -> Unit) {
        if (busy) return
        busy = true
        worker.execute {
            val failure = runCatching { ControllerHost.get(this).action() }.exceptionOrNull()
            if (failure != null) android.util.Log.e("Triage", "answer not recorded", failure)
            main.post {
                busy = false
                if (isFinishing || isDestroyed) return@post
                doneTitle = if (failure == null) title else "Не удалось записать"
                doneText = if (failure == null) text else "${failure.javaClass.simpleName}: ${failure.message ?: ""}"
                screen = Screen.DONE
                render()
            }
        }
    }

    // ---- drawing ---------------------------------------------------------------------------------

    private fun line(text: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
        column.addView(this)
    }

    private fun button(text: String, color: Int = DARK, into: LinearLayout = column, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 14f
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()), intArrayOf(Color.WHITE, Color.GRAY)))
        backgroundTintList = ColorStateList.valueOf(color)
        setOnClickListener { action() }
        if (into === column) into.addView(this, LinearLayout.LayoutParams(-1, (50 * dp).toInt()).apply { topMargin = (6 * dp).toInt() })
        else into.addView(this, LinearLayout.LayoutParams(0, (46 * dp).toInt(), 1f).apply { marginEnd = (4 * dp).toInt() })
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        column.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun u(value: Double) = String.format(Locale.getDefault(), "%.1f", value)
    private fun time(epochMs: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

    private fun render() {
        column.removeAllViews()
        when (screen) {
            Screen.HOME   -> renderHome()
            Screen.SET    -> renderSet()
            Screen.RESIST -> renderResist()
            Screen.ADVICE -> renderAdvice()
            Screen.DONE   -> renderDone()
        }
    }

    private fun renderHome() {
        line("Инсулин не действует?", 16f, bold = true)
        val facts = facts
        when {
            facts == null && factsFailure == null -> line("Смотрю показания…", 12f, Color.GRAY)
            facts == null                         -> line("Контроллер часов недоступен: $factsFailure", 12f, RED_TEXT)
            else                                  -> {
                val parts = mutableListOf<String>()
                facts.bgMgdl?.let { bg ->
                    parts += "сахар $bg" + (facts.deltaPer5?.let { d -> " (${if (d >= 0) "+" else "−"}${abs(d).roundToInt()} за 5 мин)" } ?: "")
                }
                facts.iobU?.let { parts += "IOB ${u(it)} ЕД" }
                parts += "болюсов за 2 ч ${u(facts.bolusesLast2hU)} ЕД"
                line(parts.joinToString(" · ").replaceFirstChar { it.uppercase() }, 13f)
                if (facts.verdict.level != InsulinEffectiveness.Level.NONE) line(facts.verdict.text, 13f, ORANGE)
                else line("Сейчас признаков нет: сахар идёт по прогнозу, или данных для сравнения пока мало.", 12f, Color.GRAY)
                facts.resistance?.let { mode ->
                    line("Режим «инсулин работает хуже» +${mode.extraPercent} % до ${time(mode.untilEpochMs)}", 13f, GREEN_TEXT)
                    button("Выключить режим") { act("Режим выключен", "Часы снова считают с обычными коэффициентами и капами.") { triageEndResistance() } }
                }
                if (facts.quietUntilEpochMs > System.currentTimeMillis()) line("Часы молчат об инсулине до ${time(facts.quietUntilEpochMs)}", 12f, Color.GRAY)
            }
        }
        line("Что происходит?", 14f, bold = true)
        button("Набор или канюля") { screen = Screen.SET; render() }
        button("Болею / весь день сижу") { confirming = false; screen = Screen.RESIST; render() }
        button("Съел больше / углеводы не ввёл") { openCarbs() }
        button("Пока не знаю") { screen = Screen.ADVICE; render() }
        button("Закрыть") { finish() }
    }

    private fun renderSet() {
        line("Набор и канюля", 16f, bold = true)
        line("1. Снимите набор и посмотрите канюлю: кровь, перегиб, мокрое место или запах инсулина на коже — набор не работал.", 13f)
        line("2. Поставьте новый набор на другое место и заполните канюлю.", 13f)
        line("3. Инсулину в картридже больше 6 дней или он был в жаре — замените и его.", 13f)
        line("4. Коррекцию после смены вводите как обычно (телефон, помпа или часы); не удваивайте дозу.", 13f)
        line("5. Пейте воду. Сахар выше 250 держится — проверьте кетоны.", 13f)
        button("Набор сменил", GREEN) {
            act("Записано", "Часы помолчат об инсулине 90 мин, а через час скажут, пошёл ли сахар вниз. Телефону отправлена заметка.") { triageSetChanged() }
        }
        button("Назад") { screen = Screen.HOME; render() }
    }

    private fun renderResist() {
        line("Болезнь или день без движения", 16f, bold = true)
        line(
            "Инсулин в такие дни работает хуже. Часы будут считать дозы на еду и коррекции с усилением и шире поднимать базал. " +
                "На телефоне поставьте профиль с тем же процентом на то же время.", 12f, Color.LTGRAY
        )
        line("Насколько усилить?", 13f, bold = true)
        row().let { r -> for (p in listOf(10, 20, 30)) button("+$p %", if (extraPercent == p) GREEN else DARK, r) { extraPercent = p; confirming = false; render() } }
        line("На сколько часов?", 13f, bold = true)
        row().let { r -> for (h in listOf(6, 12, 24)) button("$h ч", if (hours == h) GREEN else DARK, r) { hours = h; confirming = false; render() } }
        val caps = WatchRegulator.resistantCaps(1.0 + extraPercent / 100.0)
        line(
            "Капы часов: базал до ${caps.maxRaisePercent} %, микродоза до ${u(caps.maxSmbU)} ЕД, за час до ${u(caps.maxSmbPerHourU)} ЕД, IOB до ${u(caps.maxIobU)} ЕД.",
            11f, Color.GRAY
        )
        button(if (confirming) "Да, включить +$extraPercent % на $hours ч" else "Включить +$extraPercent % на $hours ч", if (confirming) RED else GREEN) {
            if (!confirming) {
                confirming = true
                main.removeCallbacks(cancelConfirmation)
                main.postDelayed(cancelConfirmation, CONFIRMATION_WINDOW_MS)
                render()
            } else {
                main.removeCallbacks(cancelConfirmation)
                confirming = false
                val percent = extraPercent
                val forHours = hours
                val until = time(System.currentTimeMillis() + forHours * 60 * 60_000L)
                act(
                    "Режим включён до $until",
                    "Часы считают дозы и коррекции с усилением +$percent %: базал до ${caps.maxRaisePercent} %, микродоза до ${u(caps.maxSmbU)} ЕД. " +
                        "На телефоне поставьте профиль ${100 + percent} % до $until."
                ) { triageResistance(percent, forHours, "болезнь или день без движения") }
            }
        }
        button("Назад") { confirming = false; screen = Screen.HOME; render() }
    }

    private fun renderAdvice() {
        line("Пока не ясно", 16f, bold = true)
        line("Сахар высокий, инсулин на борту есть, а он не падает. Чаще всего это:", 13f)
        line("• набор или канюля (кровь, перегиб, вытекание) — самая частая причина;", 13f)
        line("• инсулин: больше 6 дней в картридже или был в жаре;", 13f)
        line("• болезнь, стресс, день без движения — инсулина нужно на 10–30 % больше;", 13f)
        line("• еды было больше, чем введено.", 13f)
        line("Что сделать: проверьте канюлю и место; выпейте воду; через 30–45 мин посмотрите сахар. Выше 250 и не падает — кетоны и смена набора.", 13f, ORANGE)
        button("Напомнить через 45 мин") { act("Напомню", "Часы помолчат об инсулине 45 мин и скажут снова, если сахар всё ещё не идёт по прогнозу.") { triageQuiet(45, "пока не знаю") } }
        button("Назад") { screen = Screen.HOME; render() }
    }

    private fun renderDone() {
        line(doneTitle, 16f, GREEN_TEXT, bold = true)
        line(doneText, 13f)
        button("Закрыть") { finish() }
    }

    /** Food is the AAPS watch app's business: its carbohydrate screen is opened, and the watch keeps quiet about insulin for a while. */
    private fun openCarbs() {
        val opened = runCatching {
            startActivity(Intent().setClassName(AAPS_WEAR_PACKAGE, AAPS_CARBS_SCREEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
        act(
            if (opened) "Углеводы" else "Экран углеводов не открылся",
            if (opened) "Введите, что съели, в открывшемся экране AAPS. Часы помолчат об инсулине 30 мин."
            else "Введите углеводы в приложении AAPS на часах. Часы помолчат об инсулине 30 мин."
        ) { triageQuiet(30, "съел больше / углеводы не введены") }
        if (opened) finish()
    }

    private companion object {
        const val CONFIRMATION_WINDOW_MS = 8_000L
        const val AAPS_WEAR_PACKAGE = "info.nightscout.androidaps"
        const val AAPS_CARBS_SCREEN = "app.aaps.wear.interaction.actions.WatchCarbsShortcut"
        val DARK = Color.rgb(45, 45, 45)
        val GREEN = Color.rgb(20, 90, 70)
        val RED = Color.rgb(150, 40, 30)
        val ORANGE = Color.rgb(255, 195, 50)
        val RED_TEXT = Color.rgb(255, 140, 130)
        val GREEN_TEXT = Color.rgb(120, 220, 160)
    }
}
