package app.aaps.combobench

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.aaps.combobench.controller.ControllerHost
import app.aaps.pump.combowatch.protocol.Outcome
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Обновить с помпы»: the pump is read once, now, and the screen says what its history added - a
 * bolus given with the pump's own buttons above all - and what insulin on board is with it counted.
 * Asked for before a dose is worked out, on the watch or on the phone. Nothing on the pump is
 * changed. Opened by the AAPS watch app's entry screens and from this app's own journal screen;
 * from outside only an app holding the relay permission can open it.
 */
class PumpReadActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var column: LinearLayout
    private var read: ControllerHost.OwnerPumpRead? = null
    private var failure: String? = null
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val watch = object : Runnable {
        override fun run() {
            render()
            if (read?.finished != true && failure == null) main.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.MANUAL_TARGET) { finish(); return }
        // The reading takes half a minute; the screen stays on for it, so that the answer is seen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dp = resources.displayMetrics.density
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (18 * dp).toInt(), (24 * dp).toInt(), (72 * dp).toInt())
        }
        val scroll = ScrollView(this).apply {
            addView(column); isFocusableInTouchMode = true
            setOnGenericMotionListener { _, event ->
                if (event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                    scrollBy(0, (-event.getAxisValue(MotionEvent.AXIS_SCROLL) * ViewConfiguration.get(this@PumpReadActivity).scaledVerticalScrollFactor).toInt())
                    true
                } else false
            }
        }
        setContentView(scroll)
        scroll.requestFocus()
        start()
    }

    private fun start() {
        failure = null
        read = runCatching { ControllerHost.get(this).askPumpRead() }
            .onFailure { failure = "${it.javaClass.simpleName}: ${it.message ?: ""}" }
            .getOrNull()
        main.removeCallbacks(watch)
        main.post(watch)
    }

    private fun render() {
        column.removeAllViews()
        val dp = resources.displayMetrics.density
        fun line(text: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            column.addView(this)
        }
        fun button(text: String, color: Int, action: () -> Unit) = Button(this).apply {
            this.text = text; isAllCaps = false; textSize = 15f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(color)
            setOnClickListener { action() }
            column.addView(this, LinearLayout.LayoutParams(-1, (52 * dp).toInt()).apply { topMargin = (6 * dp).toInt() })
        }
        line("Обновление с помпы", 16f, bold = true)
        val read = read
        when {
            failure != null || read == null -> {
                line("Контроллер часов недоступен", 14f, WARN)
                failure?.let { line(it, 11f, Color.GRAY) }
                button("Повторить", GREEN) { start() }
            }

            !read.finished                  -> {
                val seconds = (System.currentTimeMillis() - read.startedAtEpochMs) / 1000
                line("Читаю помпу… $seconds с", 17f, AMBER, bold = true)
                line("Обычно полминуты. Если сенсор вот-вот выйдет на связь, помпа подождёт его, до двух минут.", 12f, Color.LTGRAY)
            }

            read.outcome == Outcome.DONE    -> {
                line("Помпа прочитана в ${clock.format(Date(read.finishedAtEpochMs))}", 17f, OK, bold = true)
                if (read.boluses.isEmpty()) line("Новых болюсов нет", 15f)
                else {
                    line(if (read.boluses.size == 1) "Найден болюс, о котором часы не знали:" else "Найдены болюсы, о которых часы не знали:", 13f, AMBER)
                    read.boluses.forEach { (at, units) -> line("${units(units)} ЕД в ${clock.format(Date(at))}", 17f, AMBER, bold = true) }
                }
                read.iobU?.let { line("Инсулин на борту: ${units(it, 2)} ЕД", 15f, bold = true) }
                read.snapshot?.let { pump ->
                    line(
                        (if (pump.tbrRunning && pump.tbrPercentage != null) "Базал ${pump.tbrPercentage} %, ещё ${pump.tbrRemainingMinutes ?: 0} мин" else "Базал по профилю") +
                            (pump.reservoirUnits?.let { ", в резервуаре $it ЕД" } ?: ""),
                        12f, Color.LTGRAY
                    )
                }
                line(
                    if (read.phoneInTouch) "Телефону передано: его расчёт дозы это учтёт."
                    else "Телефона рядом нет: он узнает, когда вернётся. Расчёт на часах это уже учитывает.",
                    12f, Color.LTGRAY
                )
            }

            else                            -> {
                line("Помпа не прочитана", 17f, WARN, bold = true)
                line(why(read), 13f, Color.LTGRAY)
                line("На помпе ничего не менялось.", 12f, Color.GRAY)
                button("Повторить", GREEN) { start() }
            }
        }
        if (read?.finished == true || failure != null) button("Закрыть", GREY) { finish() }
    }

    /** Why the reading did not happen, in the owner's words; the driver's own words stay beside them for the record. */
    private fun why(read: ControllerHost.OwnerPumpRead): String {
        val reason = read.reason.orEmpty()
        val plain = when {
            read.outcome == null                         -> "К часам не привязана помпа."
            reason.contains("bench is using")            -> "Стенд сейчас сам работает с помпой. Повторите через минуту."
            reason.contains("executor busy")             -> "Помпа занята другой командой. Повторите через минуту."
            reason.startsWith("pump not reached")        -> "Помпа не ответила часам: далеко от часов, занята или выключен Bluetooth."
            read.outcome == Outcome.UNKNOWN              -> "Связь с помпой оборвалась посреди чтения."
            else                                         -> "Чтение не удалось."
        }
        return if (reason.isBlank() || read.outcome == null) plain else "$plain\n($reason)"
    }

    private fun units(value: Double, digits: Int = 1) = String.format(Locale.getDefault(), "%.${digits}f", value)

    override fun onDestroy() {
        main.removeCallbacks(watch)
        super.onDestroy()
    }

    private companion object {

        const val POLL_MS = 500L
        val OK = Color.rgb(120, 220, 160)
        val AMBER = Color.rgb(255, 195, 50)
        val WARN = Color.rgb(255, 140, 130)
        val GREEN = Color.rgb(20, 90, 70)
        val GREY = Color.rgb(45, 45, 45)
    }
}
