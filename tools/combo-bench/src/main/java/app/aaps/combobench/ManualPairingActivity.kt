package app.aaps.combobench

import android.app.Activity
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ManualPairingActivity : Activity() {
    private val runtime get() = BenchRuntime.get(this)
    private val manualRuntime get() = ManualPumpRuntime.get(this)
    private val pairing get() = if (BuildConfig.MANUAL_TARGET) manualRuntime.pairing else runtime.manualPairing
    private val main = Handler(Looper.getMainLooper())
    private lateinit var stateText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var start: Button
    private lateinit var cancel: Button
    private lateinit var reconnectButton: Button
    private lateinit var reconnectText: TextView
    private lateinit var controlButton: Button
    private lateinit var aapsControlButton: Button
    private lateinit var backgroundButton: Button
    private lateinit var backgroundStopButton: Button
    private lateinit var backgroundCancel: Button
    private lateinit var backgroundText: TextView
    private lateinit var statusButton: Button
    private lateinit var stopButton: Button
    private lateinit var stop45Button: Button
    private lateinit var resumeButton: Button
    private lateinit var therapyText: TextView
    private lateinit var controlText: TextView
    private lateinit var secureSocket: CheckBox
    private lateinit var directSocket: CheckBox
    private lateinit var holdSocket: CheckBox
    private lateinit var offBody: CheckBox
    private lateinit var phoneDisabled: CheckBox
    private lateinit var pin: EditText
    private lateinit var send: Button
    private lateinit var pinGroup: LinearLayout
    private lateinit var scroll: ScrollView
    private var visibilityId = ""
    private var visiblePinRequest = ""
    private var requestingStart = false
    private val tick = object : Runnable {
        override fun run() { render(); main.postDelayed(this, 500) }
    }

    // This is the pump's one-time pairing PIN, not an account password or a login form.
    @SuppressLint("WearPasswordInput")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        visibilityId = savedInstanceState?.getString("visibilityId").orEmpty()
        val padding = (resources.displayMetrics.density * if (resources.configuration.isScreenRound) 26 else 16).toInt()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, padding, padding, padding) }
        fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; column.addView(this) }
        label("Сопряжение Combo", 18f)
        label("${if (BuildConfig.MANUAL_TARGET) manualRuntime.target()?.serial ?: "не выбрана" else "41056642"} · тестовая помпа", 14f)
        label(if (BuildConfig.MANUAL_TARGET) "Терапии нет; только остановка подачи на тестовой помпе" else "Подача инсулина недоступна", 12f)
            .setTextColor(Color.rgb(255, 195, 50))
        stateText = label("Готов к сопряжению", 14f).apply { setPadding(0, 12, 0, 12) }
        progress = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        column.addView(progress, LinearLayout.LayoutParams(-1, 30))
        offBody = CheckBox(this).apply { text = "Помпа вне тела и не используется для лечения"; textSize = 12f }
        phoneDisabled = CheckBox(this).apply {
            if (BuildConfig.MANUAL_TARGET) text = "Другой контроллер не подключается к этой тестовой помпе"
            else setText(R.string.manual_pairing_phone_confirmation)
            textSize = 12f
        }
        column.addView(offBody)
        column.addView(phoneDisabled)
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text; isAllCaps = false; textSize = 14f
            backgroundTintList = ColorStateList.valueOf(Color.rgb(45, 45, 45))
            setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(Color.WHITE, Color.GRAY)))
            setOnClickListener { action() }; column.addView(this)
        }
        start = button("Начать сопряжение") {
            requestingStart = true
            if (BuildConfig.MANUAL_TARGET) manualRuntime.start(offBody.isChecked, phoneDisabled.isChecked)
            else runtime.startManualPairing(offBody.isChecked, phoneDisabled.isChecked)
        }
        secureSocket = CheckBox(this).apply { text = "Защищённый RFCOMM"; textSize = 12f }
        column.addView(secureSocket)
        directSocket = CheckBox(this).apply { text = "Канал 1 без поиска SDP"; textSize = 12f }
        holdSocket = CheckBox(this).apply { text = "Удержание 5 секунд"; textSize = 12f }
        column.addView(directSocket)
        column.addView(holdSocket)
        reconnectButton = button("Проверить связь") {
            if (manualRuntime.reconnect.canCheckDisconnect()) manualRuntime.checkDisconnect()
            else manualRuntime.testReconnect(secureSocket.isChecked, directSocket.isChecked, holdSocket.isChecked)
        }.apply { id = R.id.manual_probe_start }
        reconnectText = label("", 12f)
        controlButton = button("Приветствие Combo") {
            manualRuntime.testControlSession(secureSocket.isChecked, directSocket.isChecked)
        }
        aapsControlButton = button("Приветствие через AAPS") {
            manualRuntime.testControlSession(secure = false, direct = false, useAapsTransport = true)
        }
        controlText = label("", 12f)
        statusButton = button("Состояние помпы (драйвер AAPS)") { manualRuntime.runTherapy(TherapySessionPolicy.Kind.STATUS) }
        stopButton = button("Остановить подачу на 30 мин") {
            manualRuntime.runTherapy(TherapySessionPolicy.Kind.STOP, ManualBackgroundProbe.STOP_MINUTES)
        }
        stop45Button = button("Остановить подачу на 45 мин") { manualRuntime.runTherapy(TherapySessionPolicy.Kind.STOP, 45) }
        resumeButton = button("Вернуть 100 %") { manualRuntime.runTherapy(TherapySessionPolicy.Kind.RESUME) }
        therapyText = label("", 12f)
        backgroundButton = button("Проверка в фоне через 5 мин") { manualRuntime.scheduleBackgroundProbe() }
        backgroundStopButton = button("В фоне через 5 мин: остановка на 30 мин") {
            manualRuntime.scheduleBackgroundProbe(ManualBackgroundProbe.MODE_STOP_DELIVERY)
        }
        backgroundCancel = button("Отменить отложенную проверку") { manualRuntime.background.cancelWaiting() }
        backgroundText = label("", 12f)
        pinGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        pinGroup.addView(TextView(this).apply { text = "Код с экрана помпы"; textSize = 14f })
        pin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(10))
            hint = "10 цифр"; textSize = 20f; isSingleLine = true
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            contentDescription = "Десятизначный код сопряжения"
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { sendPin(); true } else false
            }
        }
        pinGroup.addView(pin)
        send = Button(this).apply {
            text = "Подтвердить код"; isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(45, 45, 45))
            setOnClickListener { sendPin() }
        }
        pinGroup.addView(send)
        column.addView(pinGroup)
        cancel = button("Отменить") { pairing.cancel() }
        button("Журнал сопряжения") { showLog() }
        scroll = ScrollView(this).apply {
            addView(column)
            isFocusableInTouchMode = true
            setOnGenericMotionListener { _, event ->
                if (event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                    scrollBy(0, (-event.getAxisValue(MotionEvent.AXIS_SCROLL) * ViewConfiguration.get(this@ManualPairingActivity).scaledVerticalScrollFactor).toInt())
                    true
                } else false
            }
        }
        setContentView(scroll)
        scroll.requestFocus()
    }

    override fun onResume() { super.onResume(); render(); main.post(tick) }
    override fun onPause() { main.removeCallbacks(tick); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::pinGroup.isInitialized) render()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("visibilityId", visibilityId)
        super.onSaveInstanceState(outState)
    }
    @Deprecated("System discoverability dialog")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 6) pairing.visibilityResult(visibilityId, resultCode)
    }

    private fun sendPin() {
        val value = pin.text.toString()
        if (!PairingPinInput.valid(value)) { pin.error = "Нужны ровно 10 цифр"; return }
        val current = pairing.snapshot()
        if (pairing.submitPin(current.optString("id"), visiblePinRequest, value)) {
            PairingPinUi.close(pin, pinGroup, scroll)
        } else pin.error = "Этот запрос кода уже закрыт"
        render()
    }

    private fun render() {
        val state = pairing.snapshot()
        val active = state.optBoolean("active")
        val therapyActive = BuildConfig.MANUAL_TARGET && manualRuntime.therapy.isActive()
        val controlBlocked = BuildConfig.MANUAL_TARGET && (manualRuntime.control.isBlocked() || manualRuntime.background.isActive() || therapyActive)
        val controlActive = BuildConfig.MANUAL_TARGET && manualRuntime.control.isActive()
        val stage = state.optString("stage")
        val error = if (BuildConfig.MANUAL_TARGET) manualRuntime.error else runtime.snapshot().optString("error")
        val retryPreparation = BuildConfig.MANUAL_TARGET && manualRuntime.canRestartPreparation()
        if (active || error.isNotEmpty()) requestingStart = false
        stateText.text = if (error.isNotEmpty() && !active) error
            else if (retryPreparation) "Ожидание прервано до обнаружения помпы. Можно начать снова"
            else state.optString("detail", "На помпе: Bluetooth → Добавить устройство")
        stateText.setTextColor(when (stage) {
            "PAIRED" -> Color.rgb(80, 220, 180)
            "FAILED", "TIMEOUT", "INTERRUPTED" -> Color.rgb(255, 140, 130)
            else -> Color.WHITE
        })
        progress.visibility = if ((active && stage != "PIN_REQUIRED") || controlActive || therapyActive) View.VISIBLE else View.GONE
        offBody.visibility = if (active || stage == "PAIRED") View.GONE else View.VISIBLE
        phoneDisabled.visibility = offBody.visibility
        start.visibility = if (active || stage == "PAIRED") View.GONE else View.VISIBLE
        start.isEnabled = offBody.isChecked && phoneDisabled.isChecked && !requestingStart && (stage != "INTERRUPTED" || retryPreparation) &&
            state.optBoolean("cleanupKnown", true) && !controlBlocked
        cancel.visibility = if (active) View.VISIBLE else View.GONE
        reconnectButton.visibility = if (BuildConfig.MANUAL_TARGET && stage == "PAIRED") View.VISIBLE else View.GONE
        reconnectText.visibility = reconnectButton.visibility
        secureSocket.visibility = reconnectButton.visibility
        secureSocket.isEnabled = !active && !controlBlocked && (!BuildConfig.MANUAL_TARGET || !manualRuntime.reconnect.isActive())
        directSocket.visibility = secureSocket.visibility
        holdSocket.visibility = secureSocket.visibility
        directSocket.isEnabled = secureSocket.isEnabled
        holdSocket.isEnabled = secureSocket.isEnabled
        controlButton.visibility = reconnectButton.visibility
        aapsControlButton.visibility = reconnectButton.visibility
        controlText.visibility = reconnectButton.visibility
        backgroundButton.visibility = reconnectButton.visibility
        backgroundStopButton.visibility = reconnectButton.visibility
        backgroundText.visibility = reconnectButton.visibility
        backgroundCancel.visibility = View.GONE
        statusButton.visibility = reconnectButton.visibility
        stopButton.visibility = reconnectButton.visibility
        stop45Button.visibility = reconnectButton.visibility
        resumeButton.visibility = reconnectButton.visibility
        therapyText.visibility = reconnectButton.visibility
        if (BuildConfig.MANUAL_TARGET) {
            val probe = manualRuntime.reconnect.snapshot()
            val cleanupCheck = manualRuntime.reconnect.canCheckDisconnect()
            reconnectButton.text = if (cleanupCheck) "Проверить разрыв" else "Проверить связь"
            reconnectButton.isEnabled = !manualRuntime.reconnect.isActive() && !probe.optBoolean("active") &&
                (!probe.optBoolean("locked") || cleanupCheck) && !active && !controlBlocked
            controlButton.isEnabled = !active && !controlBlocked && !manualRuntime.reconnect.isActive() &&
                !probe.optBoolean("active") && !probe.optBoolean("locked")
            aapsControlButton.isEnabled = controlButton.isEnabled
            backgroundButton.isEnabled = controlButton.isEnabled
            val therapyOtherWork = manualRuntime.otherWorkActive() || probe.optBoolean("active") || probe.optBoolean("locked")
            statusButton.isEnabled = manualRuntime.therapy.mayStart(TherapySessionPolicy.Kind.STATUS, therapyOtherWork)
            stopButton.isEnabled = manualRuntime.therapy.mayStart(TherapySessionPolicy.Kind.STOP, therapyOtherWork)
            stop45Button.isEnabled = stopButton.isEnabled
            resumeButton.isEnabled = manualRuntime.therapy.mayStart(TherapySessionPolicy.Kind.RESUME, therapyOtherWork)
            backgroundStopButton.isEnabled = controlButton.isEnabled && stopButton.isEnabled
            val therapy = manualRuntime.therapy.snapshot()
            val therapyResult = therapy.optJSONObject("result")
            val statusAfter = therapyResult?.optJSONObject("statusAfterCommand") ?: therapyResult?.optJSONObject("statusAfterConnect")
            val tbrLine = if (statusAfter == null) "" else if (statusAfter.optBoolean("tbrOngoing"))
                "\nНа помпе TBR ${statusAfter.optInt("tbrPercentage")} %, осталось ${statusAfter.optInt("remainingTbrDurationInMinutes")} мин"
            else "\nНа помпе TBR нет; базал ${statusAfter.optInt("currentBasalRateFactor") / 1000.0} ЕД/ч"
            therapyText.text = when {
                therapyActive -> "Сеанс драйвера AAPS выполняется: ${therapy.optString("kind")}"
                manualRuntime.therapy.isLocked() -> "Результат сеанса драйвера не выяснен. Сначала прочитайте состояние помпы"
                therapy.optString("stage") == "COMPLETED" -> "Сеанс ${therapy.optString("kind")} завершён успешно$tbrLine"
                therapy.optString("stage") == "FAILED" -> "Сеанс ${therapy.optString("kind")} не удался: " +
                    (therapyResult?.optString("errorClass").orEmpty().ifEmpty { therapy.optString("errorClass") }) + tbrLine
                else -> "Драйвер AAPS ещё не запускался"
            }
            val background = manualRuntime.background.snapshot()
            val backgroundStop = background.optString("mode") == ManualBackgroundProbe.MODE_STOP_DELIVERY
            backgroundCancel.visibility = if (background.optString("stage") == "WAITING") View.VISIBLE else View.GONE
            backgroundText.text = when (background.optString("stage")) {
                "WAITING" -> if (backgroundStop) "Отложенная остановка подачи запланирована" else "Отложенная проверка запланирована"
                "RUNNING" -> if (backgroundStop) "Сеанс драйвера в фоне" else "Служебное соединение в фоне"
                "COMPLETED" -> if (backgroundStop) "Фоновая остановка подачи выполнена" else "Фоновое приветствие выполнено"
                "FAILED", "START_FAILED", "REJECTED", "INTERRUPTED" -> "Фоновая проверка не завершена; журнал сохранён"
                "CANCELLED" -> "Отложенная проверка отменена"
                else -> ""
            }
            val control = manualRuntime.control.snapshot()
            controlText.text = when {
                controlActive -> "Служебный сеанс выполняется. Подача недоступна"
                controlBlocked -> "Результат сеанса неясен. Повтор заблокирован"
                control.optString("stage") == "COMPLETED" -> "Приветствие и завершение выполнены. Подачи не было"
                control.optString("stage") == "FAILED" -> "Служебный сеанс не установлен. Журнал сохранён"
                else -> ""
            }
            reconnectText.text = when (probe.optString("stage")) {
                "PREPARING", "CONNECTING" -> "Проверяется открытие канала. Команды не отправляются"
                "SOCKET_OPENED_ZERO_BYTES" -> "Канал открылся. Команд отправлено: 0"
                "TIMEOUT" -> "Канал не открылся за 8 секунд. Журнал сохранён"
                "FAILED", "START_FAILED" -> "Канал не открыт. Журнал сохранён"
                "INTERRUPTED", "SAVE_FAILED" -> "Проверка прервана; повтор заблокирован"
                else -> "Повторное соединение ещё не проверено"
            } + if (probe.optBoolean("locked") && !probe.optBoolean("active")) "\nНужно проверить состояние соединения" else ""
        }
        val needsPin = active && stage == "PIN_REQUIRED"
        if (needsPin) {
            pinGroup.visibility = View.VISIBLE
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (visiblePinRequest != state.optString("pinRequestId")) {
                visiblePinRequest = state.getString("pinRequestId")
                pin.text.clear()
                pin.error = null
                val request = visiblePinRequest
                scroll.post {
                    if (pinGroup.visibility == View.VISIBLE && visiblePinRequest == request)
                        scroll.smoothScrollTo(0, pinGroup.top)
                }
            }
        } else {
            if (pinGroup.visibility == View.VISIBLE || pin.hasFocus() ||
                window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true)
                PairingPinUi.close(pin, pinGroup, scroll)
            visiblePinRequest = ""
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (active || ((controlActive || therapyActive) && (!BuildConfig.MANUAL_TARGET || !manualRuntime.background.isActive()))) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (active && stage == "WAITING_VISIBILITY" && visibilityId != state.optString("id")) {
            visibilityId = state.getString("id")
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 180), 6)
            } catch (_: Exception) { pairing.visibilityResult(visibilityId, null) }
        }
    }

    private fun showLog() {
        val events = pairing.snapshot().optJSONArray("events")
        val date = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val text = if (events == null || events.length() == 0) "Сопряжение ещё не запускалось" else buildString {
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                appendLine("${date.format(Date(event.getLong("at")))} ${event.getString("detail")}")
            }
        }
        val body = TextView(this).apply { this.text = text; textSize = 12f; setPadding(16, 8, 16, 8) }
        AlertDialog.Builder(this).setTitle("Журнал").setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Закрыть", null).show()
    }
}
