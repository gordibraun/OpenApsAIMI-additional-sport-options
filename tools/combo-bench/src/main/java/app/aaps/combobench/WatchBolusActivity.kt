package app.aaps.combobench

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.aaps.combobench.controller.ControllerHost
import app.aaps.pump.combowatch.protocol.Outcome
import app.aaps.pump.combowatch.regulation.WatchRegulator
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A meal entered on the watch with the phone away: the dose worked out here, shown line by line,
 * given only after the owner presses twice, and then the pump's own answer. Opened by the AAPS
 * watch app's carbohydrate screen; only an app holding the relay permission can open it.
 */
class WatchBolusActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var column: LinearLayout
    private lateinit var give: Button
    private lateinit var cancel: Button
    private lateinit var status: TextView
    private var advice: WatchRegulator.BolusAdvice? = null
    private var confirming = false
    private var confirmationAskedAt = 0L
    private var delivering = false
    private val cancelConfirmation = Runnable { confirming = false; render() }

    private val carbsG: Int get() = intent.getIntExtra("carbs", 0)
    private val foodType: String? get() = intent.getStringExtra("foodType")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.MANUAL_TARGET) { finish(); return }
        val dp = resources.displayMetrics.density
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (18 * dp).toInt(), (24 * dp).toInt(), (72 * dp).toInt())
        }
        val scroll = ScrollView(this).apply {
            addView(column); isFocusableInTouchMode = true
            setOnGenericMotionListener { _, event ->
                if (event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                    scrollBy(0, (-event.getAxisValue(MotionEvent.AXIS_SCROLL) * ViewConfiguration.get(this@WatchBolusActivity).scaledVerticalScrollFactor).toInt())
                    true
                } else false
            }
        }
        setContentView(scroll)
        scroll.requestFocus()
        advice = runCatching { ControllerHost.get(this).bolusAdvice(carbsG.coerceIn(0, MAX_CARBS_G)) }.getOrNull()
        render()
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
            setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()), intArrayOf(Color.WHITE, Color.GRAY)))
            backgroundTintList = ColorStateList.valueOf(color)
            setOnClickListener { action() }
            column.addView(this, LinearLayout.LayoutParams(-1, (52 * dp).toInt()).apply { topMargin = (6 * dp).toInt() })
        }
        line("Инсулин на еду (часы, без телефона)", 16f, bold = true)
        val advice = advice
        when {
            advice == null          -> line("Контроллер часов недоступен", 14f, Color.rgb(255, 140, 130))
            advice.refusal != null  -> {
                line("Рассчитать нельзя: ${advice.refusal}.", 14f, Color.rgb(255, 140, 130))
                line("Углеводы можно записать без инсулина кнопкой «Только углеводы».", 12f, Color.GRAY)
            }

            else                    -> advice.lines.forEachIndexed { index, text -> line(text, if (index == advice.lines.lastIndex) 17f else 13f, bold = index == advice.lines.lastIndex) }
        }
        status = line("", 13f, Color.rgb(255, 195, 50))
        if (advice != null && advice.refusal == null && !delivering) {
            val units = String.format(Locale.getDefault(), "%.1f", advice.tenthsIU / 10.0)
            give = button(
                when {
                    advice.tenthsIU == 0 -> "Записать $carbsG г без инсулина"
                    confirming           -> "Да, подать $units ЕД"
                    else                 -> "Подать $units ЕД и записать $carbsG г"
                },
                if (confirming) Color.rgb(150, 40, 30) else Color.rgb(20, 90, 70)
            ) { onGiveTapped() }
        }
        if (!delivering) cancel = button(if (delivering) "" else "Отмена", Color.rgb(45, 45, 45)) { finish() }
    }

    private fun onGiveTapped() {
        val advice = advice ?: return
        if (advice.tenthsIU > 0 && !confirming) {
            confirming = true
            confirmationAskedAt = SystemClock.elapsedRealtime()
            main.removeCallbacks(cancelConfirmation)
            main.postDelayed(cancelConfirmation, CONFIRMATION_WINDOW_MS)
            status.text = "Нажмите ещё раз, чтобы подать"
            render()
            status.text = "Нажмите ещё раз, чтобы подать"
            return
        }
        // The second tap of a double tap is not a confirmation: the question could not have been read yet.
        if (advice.tenthsIU > 0 && SystemClock.elapsedRealtime() - confirmationAskedAt < DOUBLE_TAP_GUARD_MS) return
        main.removeCallbacks(cancelConfirmation)
        confirming = false
        delivering = true
        render()
        status.text = if (advice.tenthsIU > 0) "Подаём… помпа может подождать окно сенсора до двух минут" else "Записываем…"
        val tenths = advice.tenthsIU
        val grams = carbsG.coerceIn(0, MAX_CARBS_G)
        val type = foodType
        worker.execute {
            val result = runCatching { ControllerHost.get(this).deliverOwnerBolus(grams, type, tenths) }
            main.post {
                delivering = false
                val text = result.fold(
                    onSuccess = { r ->
                        when {
                            tenths == 0            -> "Записано: $grams г без инсулина"
                            r == null              -> "Помпа не привязана; углеводы записаны"
                            r.outcome == Outcome.DONE -> "Подано ${String.format(Locale.getDefault(), "%.1f", (r.bolus?.tenthsIU ?: tenths) / 10.0)} ЕД, $grams г записаны"
                            r.outcome == Outcome.UNKNOWN -> "Исход не выяснен: часы сверятся с помпой при следующем чтении. Болюс не повторять!"
                            else                   -> "Не подано (${r.outcome}): ${r.reason}. Углеводы записаны"
                        }
                    },
                    onFailure = { "Ошибка: ${it.message ?: it.javaClass.simpleName}" }
                )
                this@WatchBolusActivity.advice = null
                render()
                status.text = text
                column.addView(Button(this).apply {
                    this.text = "Закрыть"; isAllCaps = false
                    setOnClickListener { finish() }
                })
            }
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(cancelConfirmation)
        super.onDestroy()
    }

    private companion object {

        const val MAX_CARBS_G = 300
        const val CONFIRMATION_WINDOW_MS = 20_000L
        const val DOUBLE_TAP_GUARD_MS = 1_500L
    }
}
