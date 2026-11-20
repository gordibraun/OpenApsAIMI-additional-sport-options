package app.aaps.wear.interaction.actions

import android.content.Context
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
import androidx.core.view.InputDeviceCompat
import androidx.core.view.MotionEventCompat
import app.aaps.wear.interaction.utils.RotaryStepper
import androidx.core.view.ViewConfigurationCompat
import android.view.ViewConfiguration
import com.google.android.gms.wearable.Wearable
import dagger.android.DaggerActivity
import io.reactivex.rxjava3.disposables.Disposable
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
    private var amountChange: ((Int) -> Unit)? = null
    private val rotary by lazy { RotaryStepper(ViewConfigurationCompat.getScaledVerticalScrollFactor(ViewConfiguration.get(this), this)) }
    private var deliveryStrip: TextView? = null
    private val deliveryListener: () -> Unit = { runOnUiThread { refreshDeliveryStrip() } }
    private val deliveryTick = object : Runnable {
        // By the second while a dose is on its way (the block counts it), now and then otherwise.
        override fun run() { refreshDeliveryStrip(); handler.postDelayed(this, if (dosePending) 1_000L else 20_000L) }
    }

    /** The form itself - amount, food type, buttons - under the title and the lines about the dose before. */
    private lateinit var body: LinearLayout

    // While a dose confirmed here is on its way, the form is closed and this stands in its place:
    // what is happening, large, and one small button for the owner who does mean to enter more.
    private var pendingBlock: LinearLayout? = null
    private var pendingHead: TextView? = null
    private var pendingNote: TextView? = null
    private var pendingReadPump: Button? = null
    private var enterMore: Button? = null
    private var closesForDose = false
    private var dosePending = false
    private var formOpenedByOwner = false
    private var formClosed = false
    private var compactForm = false

    /** When the phone was last asked from this form; see [WatchUiState.phoneAnsweredSince]. */
    private var phoneAskedAtMs = 0L

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // On the amount screens the crown changes the amount: one unit per detent of travel however
        // many events the watch splits it into, larger steps only after a long turn (RotaryStepper);
        // elsewhere it scrolls the page.
        val change = amountChange
        if (change != null && !formClosed && event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDeviceCompat.SOURCE_ROTARY_ENCODER)) {
            val px = -event.getAxisValue(MotionEventCompat.AXIS_SCROLL) *
                ViewConfigurationCompat.getScaledVerticalScrollFactor(ViewConfiguration.get(this), this)
            val step = rotary.onScroll(px, System.currentTimeMillis())
            if (step != 0) change(step)
            return true
        }
        return (::scroll.isInitialized && scroll.handleRotary(event)) || super.dispatchGenericMotionEvent(event)
    }

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
        compactForm = compact
        closesForDose = kind != "ACTIVITY"
        root.addView(TextView(this).apply {
            text = when (kind) { "INSULIN" -> "Инсулин"; "ACTIVITY" -> "Нагрузка"; else -> "Углеводы" }
            textSize = if (compact) 14f else 18f; setTextColor(android.graphics.Color.WHITE); gravity = Gravity.CENTER
        }, if (compact) LinearLayout.LayoutParams(-1, dp(18)) else LinearLayout.LayoutParams(-1, -2))
        // The dose confirmed a moment ago: sent, delivered, failed. Its answer no longer takes the
        // screen while something is being entered here, so this line is where it shows.
        deliveryStrip = TextView(this).apply {
            textSize = if (compact) 11f else 12f; gravity = Gravity.CENTER; setTextColor(0xFFF2C14E.toInt())
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        root.addView(deliveryStrip, LinearLayout.LayoutParams(-1, -2))
        // 20.11.2026: with a dose on its way the form stood here emptied, every button live, under one line cut
        // off before the dose - and read as "it did not go through, enter it again". Now the dose's state stands
        // here large and the form is closed until the pump has finished; «Ввести ещё…» opens it for a new entry.
        fun small(title: String, onClick: () -> Unit) = Button(this).apply {
            text = title; isAllCaps = false; textSize = 12f; letterSpacing = 0f
            minHeight = 0; minimumHeight = 0; setPadding(dp(6), 0, dp(6), 0); includeFontPadding = false
            setOnClickListener { onClick() }
        }
        pendingHead = TextView(this).apply {
            gravity = Gravity.CENTER; setTextColor(0xFFF2C14E.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            // One line whatever the dose, within what the round screen gives.
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(12, 18, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        pendingNote = TextView(this).apply { textSize = 12f; gravity = Gravity.CENTER; setTextColor(Color.LTGRAY) }
        pendingReadPump = small("Обновить с помпы") { openPumpRead() }.apply { visibility = View.GONE }
        enterMore = small(if (kind == "INSULIN") "Ввести ещё дозу" else "Ввести ещё еду") { formOpenedByOwner = true; refreshDeliveryStrip() }
        pendingBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            addView(pendingHead, LinearLayout.LayoutParams(-1, dp(32)).apply { topMargin = dp(4) })
            addView(pendingNote, LinearLayout.LayoutParams(-1, -2))
            addView(pendingReadPump, LinearLayout.LayoutParams(-1, dp(34)).apply { topMargin = dp(6); marginStart = dp(20); marginEnd = dp(20) })
            addView(enterMore, LinearLayout.LayoutParams(-1, dp(34)).apply { topMargin = dp(8); marginStart = dp(20); marginEnd = dp(20) })
        }
        root.addView(pendingBlock, LinearLayout.LayoutParams(-1, -2))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(body, LinearLayout.LayoutParams(-1, -2))
        // Heard for as long as the form lives, not only while it is in front: the dose is confirmed on another screen.
        DeliveryStatus.addListener(deliveryListener)
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
            val change: (Int) -> Unit = { delta -> amount = (amount + delta).coerceIn(0, maximum.coerceAtLeast(0)); refresh() }
            amountChange = change
            // Dragging up or down over the number changes it too, one unit per ten dp, so a meal
            // does not take a tap per gram. The buttons at either side keep their own taps.
            row.setOnTouchListener(object : View.OnTouchListener {
                private var lastY = 0f
                private var carry = 0f
                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { lastY = e.y; carry = 0f; v.parent?.requestDisallowInterceptTouchEvent(true) }
                        MotionEvent.ACTION_MOVE -> {
                            carry += lastY - e.y; lastY = e.y
                            val unit = dp(10).toFloat()
                            while (carry >= unit) { change(1); carry -= unit }
                            while (carry <= -unit) { change(-1); carry += unit }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    return true
                }
            })
            fun step(icon: Int, change: Int, description: String) = ImageButton(this).apply {
                id = if (change < 0) R.id.watch_control_minus else R.id.watch_control_plus
                setImageResource(icon); contentDescription = description
                if (compact) setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener { amount = (amount + change).coerceIn(0, maximum.coerceAtLeast(0)); refresh() }
            }
            row.addView(step(R.drawable.ic_action_minus, -1, "Уменьшить"), LinearLayout.LayoutParams(dp(44), dp(if (compact) 44 else 48)))
            row.addView(amountView, LinearLayout.LayoutParams(0, dp(if (compact) 44 else 64), 1f))
            row.addView(step(R.drawable.ic_action_add, 1, "Увеличить"), LinearLayout.LayoutParams(dp(44), dp(if (compact) 44 else 48)))
            body.addView(row); refresh()
        }
        if (kind == "CARBS") {
            body.addView(RadioGroup(this).apply {
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
        statusView = status
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
            // The phone's own check and record of this entry, as always; also where an entry for
            // the watch's controller goes when the controller does not answer.
            fun requestPhone() {
                if (sp.getInt("watch_control_api_version", 0) < if (requestKind == "MEAL") 2 else 1) {
                    showStatus("Нужно обновить AAPS на телефоне и дождаться синхронизации.")
                    rxBus.send(EventWearToMobile(EventData.ActionResendData("Watch controls compatibility")))
                    buttons.forEach { it.isEnabled = true }
                    return
                }
                buttons.forEach { it.isEnabled = false }
                showStatus("Ожидание подтверждения телефона")
                val askedAt = System.currentTimeMillis()
                phoneAskedAtMs = askedAt
                WatchUiState.phoneAsked(askedAt)
                rxBus.send(EventWearToMobile(EventData.WatchControlRequest(
                    UUID.randomUUID().toString(), System.currentTimeMillis(), requestKind,
                    insulin = if (kind == "INSULIN") amount / 10.0 else 0.0,
                    carbs = if (kind == "CARBS") amount else 0,
                    mode = mode, duration = duration, startOffset = start,
                    carbType = if (kind == "CARBS") foodType else carbType
                )))
                handler.postDelayed({
                    buttons.forEach { it.isEnabled = true }
                    if (WatchUiState.phoneAnsweredSince(askedAt)) clearStatus()
                    else showStatus("Нет ответа? Проверьте связь и AAPS на телефоне.")
                }, 20_000)
            }
            setOnClickListener {
                // A calculation or a dose asked for while the previous dose is still being delivered waits for it.
                if ((requestKind == "MEAL" || requestKind == "INSULIN") && amount > 0 && holdForDoseInFlight(buttons) { performClick() }) return@setOnClickListener
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
                // Sent as an ordered broadcast: «Записано» is shown only once the controller has
                // answered that it kept the entry; without an answer the entry goes to the phone
                // instead, so that it is never lost in silence (29.10.2026).
                if (requestKind == "ACTIVITY" && ComboWatchMode.isWatchMode(this@WatchControlActivity)) {
                    buttons.forEach { it.isEnabled = false }
                    showStatus("Записываю на часах…")
                    val settled = java.util.concurrent.atomic.AtomicBoolean(false)
                    val fallback = Runnable {
                        if (!settled.compareAndSet(false, true)) return@Runnable
                        showStatus("Контроллер часов не ответил, записываю на телефоне")
                        requestPhone()
                    }
                    handler.postDelayed(fallback, 4_000)
                    runCatching {
                        sendOrderedBroadcast(
                            Intent(ComboRelay.ACTION_ACTIVITY)
                                .setComponent(ComponentName(ComboRelay.CONTROLLER_PACKAGE, ComboRelay.CONTROLLER_ACTIVITY_RECEIVER))
                                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                                .putExtra("mode", mode)
                                .putExtra("duration", duration)
                                .putExtra("startOffset", start)
                                .putExtra("carbType", carbType)
                                .putExtra("timestamp", System.currentTimeMillis()),
                            ComboRelay.PERMISSION_RELAY,
                            object : android.content.BroadcastReceiver() {
                                override fun onReceive(context: Context, intent: Intent) {
                                    if (resultCode != android.app.Activity.RESULT_OK) { fallback.run(); return }
                                    if (!settled.compareAndSet(false, true)) return
                                    handler.removeCallbacks(fallback)
                                    val startAt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(System.currentTimeMillis() + start * 60_000L))
                                    showStatus("Записано на часах: ${if (mode == "WALK") "прогулка" else "спорт"} $duration мин с $startAt" + (if (start > 0) " (через $start мин)" else "") + ". Телефон запишет при связи")
                                    handler.postDelayed({ finish() }, 2_200)
                                }
                            },
                            handler, android.app.Activity.RESULT_CANCELED, null, null
                        )
                    }.onFailure { fallback.run() }
                    return@setOnClickListener
                }
                // A meal with the pump driven through this watch: is the phone here at all? Three
                // seconds decide. Without it the dose is worked out on the watch and given from it;
                // the phone files the bolus and the carbohydrates when it is back. With the phone
                // here the phone's own wizard is asked as before, and if it does not answer in
                // time, the watch's own screen is offered.
                // A dose typed in with the pump driven through this watch: the same question to
                // the phone, and without it the dose is given from the watch after a double press.
                if (requestKind == "INSULIN" && amount > 0 && ComboWatchMode.isWatchMode(this@WatchControlActivity)) {
                    buttons.forEach { it.isEnabled = false }
                    showStatus("Проверяем, рядом ли телефон…")
                    val tenths = amount
                    phoneAnswers(PHONE_PROBE_MS) { nearby ->
                        if (!nearby) {
                            openWatchBolus(tenths)
                            return@phoneAnswers
                        }
                        showStatus("Телефон рядом: ожидание его подтверждения")
                        val askedAt = System.currentTimeMillis()
                        phoneAskedAtMs = askedAt
                        WatchUiState.phoneAsked(askedAt)
                        rxBus.send(EventWearToMobile(EventData.WatchControlRequest(
                            UUID.randomUUID().toString(), System.currentTimeMillis(), "INSULIN",
                            insulin = tenths / 10.0
                        )))
                        handler.postDelayed({
                            buttons.forEach { it.isEnabled = true }
                            // The phone did answer - its confirmation is, or was, on screen: the watch's own way is not offered.
                            if (WatchUiState.phoneAnsweredSince(askedAt)) { clearStatus(); return@postDelayed }
                            showStatus("Телефон не ответил. Можно подать с часов.")
                            watchBolusButton.visibility = View.VISIBLE
                        }, PHONE_ANSWER_MS)
                    }
                    return@setOnClickListener
                }
                if (requestKind == "MEAL" && amount > 0 && ComboWatchMode.isWatchMode(this@WatchControlActivity)) {
                    buttons.forEach { it.isEnabled = false }
                    showStatus("Проверяем, рядом ли телефон…")
                    val grams = amount
                    val type = foodType
                    phoneAnswers(PHONE_PROBE_MS) { nearby ->
                        if (!nearby) {
                            openWatchMeal(grams, type)
                            return@phoneAnswers
                        }
                        showStatus("Телефон рядом: ожидание его расчёта")
                        val askedAt = System.currentTimeMillis()
                        phoneAskedAtMs = askedAt
                        WatchUiState.phoneAsked(askedAt)
                        rxBus.send(EventWearToMobile(EventData.WatchControlRequest(
                            UUID.randomUUID().toString(), System.currentTimeMillis(), "MEAL",
                            carbs = grams, carbType = type
                        )))
                        handler.postDelayed({
                            buttons.forEach { it.isEnabled = true }
                            // The phone did answer - its calculation is, or was, on screen: the watch's own is not offered.
                            if (WatchUiState.phoneAnsweredSince(askedAt)) { clearStatus(); return@postDelayed }
                            showStatus("Телефон не ответил. Можно рассчитать на часах.")
                            watchMealButton.visibility = View.VISIBLE
                        }, PHONE_ANSWER_MS)
                    }
                    return@setOnClickListener
                }
                if (kind != "ACTIVITY" && amount == 0) { showStatus("Укажите количество"); return@setOnClickListener }
                requestPhone()
            }
        }
        if (kind == "CARBS") {
            body.addView(action("Рассчитать инсулин", "MEAL").apply { id = R.id.watch_control_calculate },
                LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(4) })
            watchMealButton = Button(this).apply {
                text = "Рассчитать на часах"; isAllCaps = false; textSize = 12f; letterSpacing = 0f
                minHeight = 0; minimumHeight = 0; setPadding(dp(6), 0, dp(6), 0); includeFontPadding = false
                visibility = View.GONE
                setOnClickListener { openWatchMeal(amount, foodType) }
            }
            body.addView(watchMealButton, LinearLayout.LayoutParams(-1, dp(34)).apply { topMargin = dp(2); marginStart = dp(20); marginEnd = dp(20) })
            body.addView(action("Только углеводы", "CARBS").apply { id = R.id.watch_control_carbs_only; textSize = 12f },
                LinearLayout.LayoutParams(-1, dp(34)).apply {
                    topMargin = dp(2); marginStart = dp(20); marginEnd = dp(20)
                })
        } else {
            body.addView(
                action(if (kind == "ACTIVITY" && ComboWatchMode.isWatchMode(this)) "Записать на часах" else "Проверить на телефоне", kind),
                LinearLayout.LayoutParams(-1, -2)
            )
            if (kind == "INSULIN") {
                watchBolusButton = Button(this).apply {
                    text = "Подать с часов"; isAllCaps = false; textSize = 12f; letterSpacing = 0f
                    visibility = View.GONE
                    setOnClickListener { openWatchBolus(amount) }
                }
                body.addView(watchBolusButton, LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(2) })
            }
        }
        // Before a dose is worked out, here or on the phone: have the pump read now, so that a bolus given
        // with its own buttons is counted. Only with the pump driven through this watch; the amount entered
        // above stays as it is while the controller's screen is open.
        if (kind != "ACTIVITY" && ComboWatchMode.isWatchMode(this)) {
            body.addView(Button(this).apply {
                id = R.id.watch_control_read_pump
                text = "Обновить с помпы"; isAllCaps = false; textSize = 12f; letterSpacing = 0f
                minHeight = 0; minimumHeight = 0; setPadding(dp(6), 0, dp(6), 0); includeFontPadding = false
                setOnClickListener { openPumpRead() }
            }, LinearLayout.LayoutParams(-1, dp(34)).apply { topMargin = dp(2); marginStart = dp(20); marginEnd = dp(20) })
        }
        body.addView(status)
        scroll.requestFocus()
    }

    private fun openPumpRead() {
        runCatching {
            startActivity(
                Intent().setClassName(ComboRelay.CONTROLLER_PACKAGE, ComboRelay.CONTROLLER_PUMP_READ_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { showStatus("Экран чтения помпы не открылся: ${it.javaClass.simpleName}") }
    }

    private lateinit var watchMealButton: Button
    private lateinit var watchBolusButton: Button
    private var pongWatch: Disposable? = null

    /**
     * Whether the phone's AAPS is within reach right now: it is sent a ping and must answer it
     * within [timeoutMs]. A connected node alone is not enough (the data layer reports one for a
     * while after the phone has gone, and over Wi-Fi from another room), and no connected node at
     * all is answered at once.
     */
    private fun phoneAnswers(timeoutMs: Long, callback: (Boolean) -> Unit) {
        var answered = false
        val stamp = System.currentTimeMillis()
        fun answer(nearby: Boolean) {
            if (answered) return
            answered = true
            pongWatch?.dispose(); pongWatch = null
            handler.post { callback(nearby) }
        }
        pongWatch = rxBus.toObservable(EventData.ActionPong::class.java)
            .subscribe({ if (it.timeStamp == stamp) answer(true) }, { answer(false) })
        runCatching {
            Wearable.getNodeClient(applicationContext).connectedNodes
                .addOnSuccessListener { nodes -> if (nodes.none { it.isNearby }) answer(false) }
        }
        rxBus.send(EventWearToMobile(EventData.ActionPing(stamp)))
        handler.postDelayed({ answer(false) }, timeoutMs)
    }

    private fun openWatchBolus(tenths: Int) {
        if (tenths <= 0) { showStatus("Укажите дозу"); return }
        runCatching {
            WatchUiState.handOverToWatch()
            startActivity(
                Intent().setClassName(ComboRelay.CONTROLLER_PACKAGE, ComboRelay.CONTROLLER_BOLUS_ACTIVITY)
                    .putExtra("insulinTenths", tenths)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
        }.onFailure { showStatus("Экран подачи на часах не открылся: ${it.javaClass.simpleName}") }
    }

    private fun openWatchMeal(grams: Int, type: String) {
        if (grams <= 0) { showStatus("Укажите количество"); return }
        runCatching {
            WatchUiState.handOverToWatch()
            startActivity(
                Intent().setClassName(ComboRelay.CONTROLLER_PACKAGE, ComboRelay.CONTROLLER_BOLUS_ACTIVITY)
                    .putExtra("carbs", grams).putExtra("foodType", type)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
        }.onFailure { showStatus("Экран расчёта на часах не открылся: ${it.javaClass.simpleName}") }
    }

    private fun showStatus(message: String) { statusView?.let { it.text = message; it.visibility = View.VISIBLE } }

    private fun clearStatus() { statusView?.let { it.text = ""; if (compactForm) it.visibility = View.GONE } }
    private var statusView: TextView? = null

    private fun label(value: String, size: Float): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(android.graphics.Color.WHITE); gravity = Gravity.CENTER
    }.also { body.addView(it) }

    private fun choices(title: String, labels: List<String>, initial: Int = 0, selected: (Int) -> Unit) {
        label(title, 14f)
        body.addView(RadioGroup(this).apply {
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

    private companion object {

        const val PHONE_PROBE_MS = 1_500L
        const val PHONE_ANSWER_MS = 12_000L
        const val DOSE_FILED_MS = 2_500L
    }
    override fun onStart() {
        super.onStart()
        WatchUiState.enterInput()
    }

    override fun onStop() {
        WatchUiState.leaveInput()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        handler.post(deliveryTick)
        // Back from the phone's calculation, which came after this form had given the phone up: the offer of the
        // watch's own way that the form put up meanwhile is taken down again.
        if (phoneAskedAtMs > 0L && WatchUiState.phoneAnsweredSince(phoneAskedAtMs)) {
            if (::watchMealButton.isInitialized) watchMealButton.visibility = View.GONE
            if (::watchBolusButton.isInitialized) watchBolusButton.visibility = View.GONE
            if (statusView?.text?.startsWith("Телефон не ответил") == true) clearStatus()
        }
    }

    override fun onPause() {
        handler.removeCallbacks(deliveryTick)
        super.onPause()
    }

    /**
     * A dose confirmed a moment ago is still on its way to the pump: the phone turns a second
     * calculation away until it has landed, and what it would calculate now leaves that dose out.
     * So the request waits here and goes by itself once the phone has answered for that dose (or
     * after the time a dose can take at most). True when the caller must stop and wait.
     */
    private fun holdForDoseInFlight(buttons: List<Button>, proceed: () -> Unit): Boolean {
        val first = DeliveryStatus.inFlight(this) ?: return false
        buttons.forEach { it.isEnabled = false }
        showStatus("Предыдущая доза ещё подаётся. Расчёт начнётся сам, как только помпа закончит.")
        val deadline = first.sentAtEpochMs + DeliveryStatus.IN_FLIGHT_MAX_MS
        val wait = object : Runnable {
            override fun run() {
                if (DeliveryStatus.inFlight(this@WatchControlActivity) != null && System.currentTimeMillis() < deadline) {
                    handler.postDelayed(this, 1_000L)
                    return
                }
                // The phone files the bolus and its carbohydrates a moment after it answers.
                showStatus("Предыдущая доза подана. Считаем…")
                handler.postDelayed({ buttons.forEach { it.isEnabled = true }; proceed() }, DOSE_FILED_MS)
            }
        }
        handler.postDelayed(wait, 1_000L)
        return true
    }

    /** The line about the dose before; and, while a dose is on its way, the block that stands in the form's place. */
    private fun refreshDeliveryStrip() {
        val strip = deliveryStrip ?: return
        val now = System.currentTimeMillis()
        val progress = DeliveryStatus.progress(this, now)
        // What this form asked the phone for has been confirmed and is on its way, or given: the form's work is
        // done. Left open it would come back from behind the dose's screen with the amount still in it and its
        // buttons live. (A request the phone turned down leaves the form as it was, to be tried again.)
        if (phoneAskedAtMs > 0L && progress != null && progress.sentAtEpochMs >= phoneAskedAtMs && progress.phase != DeliveryStatus.Phase.FAILED && !isFinishing) {
            android.util.Log.i(DeliveryStatus.LOG_TAG, "input form: closes, its request was confirmed (${progress.phase})")
            finish()
            return
        }
        val pending = closesForDose && progress != null && progress.pending && progress.isDose
        dosePending = pending
        if (!pending) formOpenedByOwner = false
        if (progress != null && pending) {
            pendingHead?.text = DeliveryStatus.headline(progress)
            pendingNote?.text = DeliveryStatus.detail(progress, now)
            pendingReadPump?.visibility = if (progress.phase == DeliveryStatus.Phase.NO_ANSWER && ComboWatchMode.isWatchMode(this)) View.VISIBLE else View.GONE
            enterMore?.visibility = if (formOpenedByOwner) View.GONE else View.VISIBLE
        }
        pendingBlock?.visibility = if (pending) View.VISIBLE else View.GONE
        formClosed = pending && !formOpenedByOwner
        if (::body.isInitialized) body.visibility = if (formClosed) View.GONE else View.VISIBLE
        // The one-line version, when the block is not saying the same thing large.
        val line = if (pending || progress == null) null else DeliveryStatus.lineOf(progress, now)
        strip.text = line ?: ""
        strip.visibility = if (line == null) View.GONE else View.VISIBLE
        // What stands on the screen, for the log: once per state.
        val shown = "form closed=$formClosed | block=${if (pending) pendingHead?.text else "-"} | more=${enterMore?.visibility == View.VISIBLE && pending} " +
            "readPump=${pendingReadPump?.visibility == View.VISIBLE && pending} | line=${line ?: "-"}"
        if (shown != lastShownDelivery) {
            lastShownDelivery = shown
            android.util.Log.i(DeliveryStatus.LOG_TAG, "input form: $shown" + (if (pending) " | ${pendingNote?.text}" else ""))
        }
    }

    private var lastShownDelivery = ""

    override fun onDestroy() {
        DeliveryStatus.removeListener(deliveryListener)
        handler.removeCallbacksAndMessages(null); pongWatch?.dispose()
        super.onDestroy()
    }
}
