package app.aaps.wear.interaction.actions

import android.content.Intent
import app.aaps.wear.combo.ComboWatchMode
import app.aaps.wear.combo.ComboRelay
import android.content.ComponentName
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventWearToMobile
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.wear.R
import app.aaps.wear.interaction.utils.RotaryScrollView
import dagger.android.DaggerActivity
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt

/** Only requests a phone-side preview. Delivery requires the phone's separate confirmation. */
class WatchControlActivity : DaggerActivity() {
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var sp: SP
    private val handler = Handler(Looper.getMainLooper())
    private var amount = 0
    private var mode = "WALK"
    private var duration = 30
    private var start = 0
    private var carbType = "fast"
    private var foodType = "balanced"
    private lateinit var root: LinearLayout
    private lateinit var scroll: RotaryScrollView

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        (::scroll.isInitialized && scroll.handleRotary(event)) || super.dispatchGenericMotionEvent(event)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handler.removeCallbacksAndMessages(null)
        // A watch-face shortcut may reuse the task of a different input form.
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = WatchControlDestination.resolve(intent.component?.className, intent.getStringExtra("kind"))
        val compact = kind == "CARBS"
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (compact) setPadding(dp(26), dp(16), dp(26), dp(16))
            else setPadding(dp(32), dp(30), dp(32), dp(40))
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        scroll = RotaryScrollView(this).apply { id = R.id.watch_control_scroll; addView(root) }
        setContentView(scroll)
        label(when (kind) { "INSULIN" -> "Инсулин"; "ACTIVITY" -> "Нагрузка"; else -> "Углеводы" }, if (compact) 14f else 18f).apply {
            if (compact) layoutParams = LinearLayout.LayoutParams(-1, dp(18))
        }
        if (kind == "ACTIVITY") {
            choices("Режим", listOf("Прогулка", "Спорт")) { mode = if (it == 0) "WALK" else "SPORT" }
            choices("Длительность", listOf("30 мин", "50 мин", "90 мин")) { duration = listOf(30, 50, 90)[it] }
            choices("Начало", listOf("Сейчас", "Через 20 мин", "Через 30 мин", "Через 50 мин", "Через 60 мин")) { start = listOf(0, 20, 30, 50, 60)[it] }
            choices("Если нужны углеводы", listOf("Быстрые", "Обычные")) { carbType = if (it == 0) "fast" else "balanced" }
        } else {
            val maximum = if (kind == "INSULIN") (sp.getDouble(getString(R.string.key_treatments_safety_max_bolus), 3.0) * 10).roundToInt()
                else sp.getInt(getString(R.string.key_treatments_safety_max_carbs), 48)
            val amountView = TextView(this).apply {
                id = R.id.watch_control_amount
                textSize = if (compact) 20f else 23f
                gravity = Gravity.CENTER; setTextColor(android.graphics.Color.WHITE)
            }
            fun refresh() { amountView.text = if (kind == "INSULIN") "${amount / 10.0} Е" else "$amount г" }
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
            fun step(icon: Int, change: Int, description: String) = ImageButton(this).apply {
                id = if (change < 0) R.id.watch_control_minus else R.id.watch_control_plus
                setImageResource(icon); contentDescription = description
                if (compact) setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener { amount = (amount + change).coerceIn(0, maximum.coerceAtLeast(0)); refresh() }
            }
            row.addView(step(R.drawable.ic_action_minus, -1, "Уменьшить"), LinearLayout.LayoutParams(dp(44), dp(if (compact) 44 else 48)))
            row.addView(amountView, LinearLayout.LayoutParams(0, dp(if (compact) 44 else 64), 1f))
            row.addView(step(R.drawable.ic_action_add, 1, "Увеличить"), LinearLayout.LayoutParams(dp(44), dp(if (compact) 44 else 48)))
            root.addView(row); refresh()
        }
        if (kind == "CARBS") {
            root.addView(RadioGroup(this).apply {
                id = R.id.watch_control_food_type
                // A fresh form resets the amount and food type together, including after recreation.
                isSaveFromParentEnabled = false
                orientation = RadioGroup.HORIZONTAL
                contentDescription = "Тип еды"
                val options = listOf(R.id.watch_food_balanced to "Обычная", R.id.watch_food_fast to "Быстрая")
                options.forEachIndexed { index, (optionId, title) ->
                    addView(RadioButton(this@WatchControlActivity).apply {
                        id = optionId; text = title; contentDescription = "$title еда"
                        textSize = 12f; letterSpacing = 0f; gravity = Gravity.CENTER
                        buttonDrawable = null
                        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
                        setPadding(dp(4), 0, dp(4), 0)
                        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                            intArrayOf(Color.BLACK, Color.WHITE)))
                        fun surface(color: Int) = GradientDrawable().apply {
                            setColor(color); cornerRadius = dp(5).toFloat()
                        }
                        val states = StateListDrawable().apply {
                            addState(intArrayOf(android.R.attr.state_checked), surface(0xFFF2C14E.toInt()))
                            addState(intArrayOf(), surface(0xFF303334.toInt()))
                        }
                        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), states, null)
                    }, RadioGroup.LayoutParams(0, -1, 1f).apply { if (index > 0) marginStart = dp(4) })
                }
                setOnCheckedChangeListener { _, checkedId ->
                    when (checkedId) {
                        R.id.watch_food_balanced -> foodType = "balanced"
                        R.id.watch_food_fast -> foodType = "fast"
                    }
                }
                check(R.id.watch_food_balanced)
            }, LinearLayout.LayoutParams(-1, dp(36)))
        }
        val status = TextView(this).apply {
            textSize = 13f; gravity = Gravity.CENTER; setTextColor(android.graphics.Color.LTGRAY)
            visibility = if (compact) View.GONE else View.VISIBLE
        }
        fun showStatus(message: String) { status.text = message; status.visibility = View.VISIBLE }
        val buttons = mutableListOf<Button>()
        fun action(title: String, requestKind: String) = Button(this).apply {
            text = title; isAllCaps = false; textSize = 14f
            if (compact) {
                textSize = 13f; letterSpacing = 0f
                minHeight = 0; minimumHeight = 0
                setPadding(dp(6), 0, dp(6), 0)
                includeFontPadding = false
            }
            buttons.add(this)
            setOnClickListener {
                // With the pump driven through this watch, carbohydrates alone are the watch's
                // business: its pump controller keeps them for its own forecast and hands them to
                // the phone's records when the phone is in touch. Insulin still goes to the phone.
                if (requestKind == "CARBS" && amount > 0 && ComboWatchMode.isWatchMode(this@WatchControlActivity)) {
                    buttons.forEach { it.isEnabled = false }
                    runCatching {
                        sendBroadcast(
                            Intent(ComboRelay.ACTION_CARBS)
                                .setComponent(ComponentName(ComboRelay.CONTROLLER_PACKAGE, ComboRelay.CONTROLLER_CARBS_RECEIVER))
                                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                                .putExtra("grams", amount)
                                .putExtra("timestamp", System.currentTimeMillis())
                                .putExtra("foodType", foodType),
                            ComboRelay.PERMISSION_RELAY
                        )
                    }
                    showStatus("Записано: $amount г")
                    handler.postDelayed({ finish() }, 1_500)
                    return@setOnClickListener
                }
                // The same for a walk or a sport session: the controller counts it in the watch's
                // own forecast and basal at once, and the phone records it when it is in touch.
                if (requestKind == "ACTIVITY" && ComboWatchMode.isWatchMode(this@WatchControlActivity)) {
                    buttons.forEach { it.isEnabled = false }
                    runCatching {
                        sendBroadcast(
                            Intent(ComboRelay.ACTION_ACTIVITY)
                                .setComponent(ComponentName(ComboRelay.CONTROLLER_PACKAGE, ComboRelay.CONTROLLER_ACTIVITY_RECEIVER))
                                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                                .putExtra("mode", mode)
                                .putExtra("duration", duration)
                                .putExtra("startOffset", start)
                                .putExtra("carbType", carbType)
                                .putExtra("timestamp", System.currentTimeMillis()),
                            ComboRelay.PERMISSION_RELAY
                        )
                    }
                    showStatus("Записано на часах: ${if (mode == "WALK") "прогулка" else "спорт"} $duration мин" + if (start > 0) ", через $start мин" else "")
                    handler.postDelayed({ finish() }, 1_800)
                    return@setOnClickListener
                }
                if (sp.getInt("watch_control_api_version", 0) < if (requestKind == "MEAL") 2 else 1) {
                    showStatus("Нужно обновить AAPS на телефоне и дождаться синхронизации.")
                    rxBus.send(EventWearToMobile(EventData.ActionResendData("Watch controls compatibility")))
                    return@setOnClickListener
                }
                if (kind != "ACTIVITY" && amount == 0) { showStatus("Укажите количество"); return@setOnClickListener }
                buttons.forEach { it.isEnabled = false }
                showStatus("Ожидание подтверждения телефона")
                rxBus.send(EventWearToMobile(EventData.WatchControlRequest(
                    UUID.randomUUID().toString(), System.currentTimeMillis(), requestKind,
                    insulin = if (kind == "INSULIN") amount / 10.0 else 0.0,
                    carbs = if (kind == "CARBS") amount else 0,
                    mode = mode, duration = duration, startOffset = start,
                    carbType = if (kind == "CARBS") foodType else carbType
                )))
                handler.postDelayed({
                    showStatus("Нет ответа? Проверьте связь и AAPS на телефоне.")
                    buttons.forEach { it.isEnabled = true }
                }, 20_000)
            }
        }
        if (kind == "CARBS") {
            root.addView(action("Рассчитать инсулин", "MEAL").apply { id = R.id.watch_control_calculate },
                LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(4) })
            root.addView(action("Только углеводы", "CARBS").apply { id = R.id.watch_control_carbs_only; textSize = 12f },
                LinearLayout.LayoutParams(-1, dp(34)).apply {
                    topMargin = dp(2); marginStart = dp(20); marginEnd = dp(20)
                })
        } else root.addView(
            action(if (kind == "ACTIVITY" && ComboWatchMode.isWatchMode(this)) "Записать на часах" else "Проверить на телефоне", kind),
            LinearLayout.LayoutParams(-1, -2)
        )
        root.addView(status)
        scroll.requestFocus()
    }

    private fun label(value: String, size: Float): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(android.graphics.Color.WHITE); gravity = Gravity.CENTER
    }.also { root.addView(it) }

    private fun choices(title: String, labels: List<String>, initial: Int = 0, selected: (Int) -> Unit) {
        label(title, 14f)
        root.addView(RadioGroup(this).apply {
            labels.forEachIndexed { index, title ->
                addView(RadioButton(this@WatchControlActivity).apply {
                    id = View.generateViewId(); text = title; textSize = 15f
                    setTextColor(android.graphics.Color.WHITE); minHeight = dp(40)
                    isChecked = index == initial
                    setOnClickListener { selected(index) }
                })
            }
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
