package app.aaps.combobench

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.aaps.pump.combowatch.executor.CommandJournal
import app.aaps.pump.combowatch.protocol.CommandKind
import app.aaps.pump.combowatch.protocol.Outcome
import java.util.concurrent.Executors

/**
 * Which pump this watch holds: choose one and pair it, or unpair the one it has.
 *
 * Unpairing asks twice, because the pump it lets go of may be the one in use.
 */
class ManualPumpSetupActivity : Activity() {
    private val runtime get() = ManualPumpRuntime.get(this)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var serialLabel: TextView
    private lateinit var serial: EditText
    private lateinit var address: EditText
    private lateinit var addressLabel: TextView
    private lateinit var pair: Button
    private lateinit var unpair: Button
    private lateinit var note: TextView
    private lateinit var error: TextView
    private lateinit var alone: TextView
    private lateinit var aloneMode: Button
    private var confirmingActive = false
    private var activeConfirmationAskedAt = 0L
    private val cancelActiveConfirmation = Runnable { confirmingActive = false; render() }
    private var confirmingUnpair = false
    private var confirmationAskedAt = 0L
    private var unpairing = false
    private val cancelConfirmation = Runnable { confirmingUnpair = false; note.text = ""; render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.MANUAL_TARGET) { finish(); return }
        val padding = (resources.displayMetrics.density * 26).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Room below the last line, so that on a round screen it can be scrolled up to where
            // the screen is wide enough to show it whole.
            setPadding(padding, (resources.displayMetrics.density * 18).toInt(), padding, (resources.displayMetrics.density * 72).toInt())
        }
        fun label(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; column.addView(this) }
        fun button(value: String, action: () -> Unit) = Button(this).apply {
            text = value; isAllCaps = false; textSize = 14f
            setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()), intArrayOf(Color.WHITE, Color.GRAY)))
            backgroundTintList = ColorStateList.valueOf(Color.rgb(45, 45, 45))
            setOnClickListener { action() }
            column.addView(this, LinearLayout.LayoutParams(-1, (resources.displayMetrics.density * 48).toInt()))
        }
        label("Combo: помпа", 18f)
        status = label("", 14f).apply { id = R.id.manual_pump_status; setPadding(0, 6, 0, 6) }
        serialLabel = label("Номер помпы", 14f)
        serial = EditText(this).apply {
            id = R.id.manual_pump_serial
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(8))
            hint = "8 цифр с наклейки"
            textSize = 18f
            isSingleLine = true
            contentDescription = "Номер помпы"
            column.addView(this)
        }
        pair = button("Сопряжение") { openPairing() }.apply { id = R.id.manual_pump_pair }
        addressLabel = label("Bluetooth-адрес", 12f).apply {
            setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.arrow_down_float, 0)
            minHeight = (resources.displayMetrics.density * 32).toInt()
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        address = EditText(this).apply {
            id = R.id.manual_pump_address
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(17))
            hint = "Автоматически"
            textSize = 12f
            isSingleLine = true
            contentDescription = "Bluetooth-адрес помпы"
            visibility = if (savedInstanceState?.getBoolean("addressExpanded") == true) View.VISIBLE else View.GONE
            column.addView(this)
        }
        addressLabel.setOnClickListener {
            address.visibility = if (address.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        error = label("", 12f).apply { visibility = View.GONE; setTextColor(Color.rgb(255, 140, 130)) }
        unpair = button("Отвязать помпу") { onUnpairTapped() }.apply { id = R.id.manual_pump_unpair }
        note = label("", 12f).apply { id = R.id.manual_pump_note; setTextColor(Color.rgb(255, 195, 50)) }
        label("Команды помпе отдаёт телефон (AAPS)", 12f)
        label("Без телефона", 18f).setPadding(0, (resources.displayMetrics.density * 14).toInt(), 0, 0)
        alone = label("", 12f).apply { id = R.id.manual_alone_status }
        aloneMode = button("") { onAloneModeTapped() }.apply { id = R.id.manual_alone_mode }
        button("Журнал помпы") { showJournal() }.apply { id = R.id.manual_alone_journal }
        val scroll = ScrollView(this).apply {
            addView(column); isFocusableInTouchMode = true
            setOnGenericMotionListener { _, event ->
                if (event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                    scrollBy(0, (-event.getAxisValue(MotionEvent.AXIS_SCROLL) * ViewConfiguration.get(this@ManualPumpSetupActivity).scaledVerticalScrollFactor).toInt())
                    true
                } else false
            }
        }
        setContentView(scroll)
        scroll.requestFocus()
        fillFromStored()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onPause() {
        main.removeCallbacks(cancelConfirmation)
        main.removeCallbacks(cancelActiveConfirmation)
        confirmingUnpair = false
        confirmingActive = false
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    /** The chosen pump, when one is stored; the fields are only for choosing a pump while none is. */
    private fun fillFromStored() {
        val saved = runCatching { runtime.target() }.getOrNull()
        serial.setText(saved?.serial.orEmpty())
        address.setText(saved?.address.orEmpty())
        if (saved?.address != null) address.visibility = View.VISIBLE
    }

    private fun render() {
        val saved = runCatching { runtime.target() }.getOrNull()
        val paired = runtime.pairedPump()
        val stored = runtime.hasSomethingToUnpair()
        status.text = when {
            unpairing      -> "Отвязываем…"
            paired != null -> "Привязана помпа ${paired.serial}" + if (paired.isTestPump) " (тестовая)" else ""
            saved != null  -> "Помпа ${saved.serial}: сопряжение не завершено"
            stored         -> "Прошлое сопряжение не завершено"
            else           -> "Помпа не привязана"
        }
        status.setTextColor(if (paired != null) Color.rgb(80, 220, 180) else Color.WHITE)
        // While a pump is stored its number is shown, not edited: another pump means unpairing first.
        val choosing = !stored && !unpairing
        serial.isEnabled = choosing
        address.isEnabled = choosing
        serialLabel.text = if (choosing) "Номер помпы" else "Номер помпы (чтобы сменить, отвяжите)"
        pair.text = if (paired != null) "Открыть" else "Сопряжение"
        pair.isEnabled = !unpairing
        unpair.visibility = if (stored || unpairing) View.VISIBLE else View.GONE
        unpair.isEnabled = !unpairing
        val keys = runtime.hasStoredKeys()
        unpair.text = when {
            confirmingUnpair && keys -> "Да, отвязать ${saved?.serial.orEmpty()}".trim()
            confirmingUnpair         -> "Да, сбросить"
            keys                     -> "Отвязать помпу"
            else                     -> "Сбросить выбор помпы"
        }
        unpair.backgroundTintList = ColorStateList.valueOf(if (confirmingUnpair) Color.rgb(150, 40, 30) else Color.rgb(45, 45, 45))
        renderAlone()
    }

    // ---- what the watch does when the phone is away ------------------------------------------------

    private val host get() = app.aaps.combobench.controller.ControllerHost.get(this)

    private fun renderAlone() {
        val mode = runCatching { host.autonomy.mode() }.getOrDefault(app.aaps.pump.combowatch.executor.AutonomyPolicy.Mode.OFF)
        aloneMode.text = when {
            confirmingActive                                                       -> "Да, часы сами снижают базал"
            mode == app.aaps.pump.combowatch.executor.AutonomyPolicy.Mode.OFF     -> "Режим: выключено"
            mode == app.aaps.pump.combowatch.executor.AutonomyPolicy.Mode.OBSERVE -> "Режим: наблюдение"
            else                                                                   -> "Режим: работа"
        }
        aloneMode.backgroundTintList = ColorStateList.valueOf(
            when {
                confirmingActive                                                      -> Color.rgb(150, 40, 30)
                mode == app.aaps.pump.combowatch.executor.AutonomyPolicy.Mode.ACTIVE -> Color.rgb(20, 90, 70)
                else                                                                  -> Color.rgb(45, 45, 45)
            }
        )
        val what = when (mode) {
            app.aaps.pump.combowatch.executor.AutonomyPolicy.Mode.OFF     -> "Часы ничего не делают сами."
            app.aaps.pump.combowatch.executor.AutonomyPolicy.Mode.OBSERVE -> "Часы только записывают, что сделали бы; помпу не трогают."
            app.aaps.pump.combowatch.executor.AutonomyPolicy.Mode.ACTIVE  -> "Часы сами снижают и останавливают базал. Инсулин не добавляют."
        }
        val now = when (val reason = runCatching { host.whyNotAlone() }.getOrDefault("нет данных")) {
            null                                             -> "Сейчас часы одни."
            "the phone is in charge"                         -> "Сейчас командует телефон."
            "the phone took control back"                    -> "Телефон забрал управление себе."
            "no pump is paired with this watch"              -> "Помпа не привязана."
            "the phone has never been in charge of this watch",
            "the phone left no snapshot"                     -> "Телефон ещё не оставил данных."
            "the phone's permission has run out"             -> "Телефона нет больше суток: часы остановились."
            "the phone's lease was for another pump",
            "the phone's snapshot is of another pump"        -> "Телефон настроен на другую помпу."
            "an earlier command is not settled"              -> "Исход прошлой команды не выяснен: часы сначала читают помпу."
            "the pump is in use"                             -> "Помпа сейчас занята."
            else                                             -> "Сейчас не одни: $reason."
        }
        alone.text = "$what\n$now"
    }

    private fun onAloneModeTapped() {
        val modes = app.aaps.pump.combowatch.executor.AutonomyPolicy.Mode.entries
        val next = modes[(host.autonomy.mode().ordinal + 1) % modes.size]
        // Letting the watch act on the pump by itself is asked for twice.
        if (next == app.aaps.pump.combowatch.executor.AutonomyPolicy.Mode.ACTIVE && !confirmingActive) {
            confirmingActive = true
            activeConfirmationAskedAt = android.os.SystemClock.elapsedRealtime()
            main.postDelayed(cancelActiveConfirmation, CONFIRMATION_WINDOW_MS)
            render()
            return
        }
        // The second tap of a double tap is not a confirmation: the question could not have been read yet.
        if (confirmingActive && android.os.SystemClock.elapsedRealtime() - activeConfirmationAskedAt < DOUBLE_TAP_GUARD_MS) return
        main.removeCallbacks(cancelActiveConfirmation)
        confirmingActive = false
        runCatching { host.autonomy.setMode(next) }.onFailure { showError("Не удалось сохранить режим") }
        runCatching { host.refreshFace() }
        render()
    }

    /**
     * Everything that happened between this watch and the pump, newest first: every command the
     * phone or the watch itself gave and what came of it, and the decisions the watch made alone.
     */
    private fun showJournal() {
        val time = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
        fun stamp(at: Long) = time.format(java.util.Date(at))
        val decisions = runCatching { host.autonomy.journal() }.getOrDefault(emptyList()).map { entry ->
            val done = when {
                entry.optString("action") == "LEAVE" -> ""
                entry.optBoolean("done")             -> " ✓"
                entry.optString("mode") == "OBSERVE" -> " (не выполнялось)"
                else                                 -> " ✗ ${entry.optString("reason")}"
            }
            entry.optLong("at") to "${stamp(entry.optLong("at"))} сами: ${entry.optString("text")}$done"
        }
        val commands = runCatching { host.recentCommands(JOURNAL_LINES) }.getOrDefault(emptyList()).map { entry ->
            entry.startedAtEpochMs to "${stamp(entry.startedAtEpochMs)} ${describe(entry)}"
        }
        val lines = (decisions + commands).sortedByDescending { it.first }.take(JOURNAL_LINES).map { it.second }
        val text = if (lines.isEmpty()) "Команд помпе ещё не было" else lines.joinToString("\n\n")
        val body = TextView(this).apply { this.text = text; textSize = 12f; setPadding(24, 8, 24, 8) }
        android.app.AlertDialog.Builder(this).setTitle("Журнал помпы").setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Закрыть", null).show()
    }

    /** One command to the pump: whose it was, what it asked, what came of it. */
    private fun describe(entry: CommandJournal.Entry): String {
        val who = if (entry.id.startsWith("auto-")) "часы" else "телефон"
        val what = when (entry.kind) {
            CommandKind.STATUS        -> "чтение помпы"
            CommandKind.SET_TBR       -> "базал ${entry.tbrPercentage ?: "?"} %"
            CommandKind.CANCEL_TBR    -> "отмена временного базала"
            CommandKind.DELIVER_BOLUS -> "болюс ${(entry.bolusTenthsIU ?: 0) / 10.0} ЕД"
            null                      -> "команда"
        }
        val outcome = when (entry.outcome) {
            Outcome.DONE    -> "выполнено"
            Outcome.REFUSED -> "отказ: ${entry.reason}"
            Outcome.FAILED  -> "не удалось: ${entry.reason}"
            Outcome.UNKNOWN -> "исход не выяснен"
            null            -> if (entry.abandoned) "брошено" else "выполняется"
        }
        return "$who: $what — $outcome"
    }

    private fun openPairing() {
        try {
            error.visibility = View.GONE
            val permissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (permissions.isNotEmpty()) { requestPermissions(permissions.toTypedArray(), 1); return }
            // A stored pump is opened as it is; only a new choice is written.
            if (!runtime.hasSomethingToUnpair()) runtime.configure(serial.text.toString(), address.text.toString())
            else checkNotNull(runCatching { runtime.target() }.getOrNull()) { "Прошлое сопряжение не завершено: отвяжите помпу и начните заново" }
            startActivity(Intent(this, ManualPairingActivity::class.java))
        } catch (e: Exception) { showError(e.message ?: "Проверьте номер и адрес") }
    }

    private fun onUnpairTapped() {
        error.visibility = View.GONE
        if (!confirmingUnpair) {
            confirmingUnpair = true
            confirmationAskedAt = android.os.SystemClock.elapsedRealtime()
            val warnings = runtime.unpairWarnings()
            note.text = (warnings + "Нажмите ещё раз, чтобы подтвердить").joinToString("\n\n")
            main.removeCallbacks(cancelConfirmation)
            main.postDelayed(cancelConfirmation, CONFIRMATION_WINDOW_MS)
            render()
            return
        }
        // The confirming button appears where the first tap landed; a double tap must not count.
        if (android.os.SystemClock.elapsedRealtime() - confirmationAskedAt < DOUBLE_TAP_GUARD_MS) return
        main.removeCallbacks(cancelConfirmation)
        confirmingUnpair = false
        unpairing = true
        note.text = ""
        render()
        worker.execute {
            val outcome = runCatching { runtime.unpair() }
            main.post {
                unpairing = false
                outcome.onSuccess { note.text = it; fillFromStored() }
                    .onFailure { showError(it.message ?: "Не удалось отвязать помпу") }
                render()
            }
        }
    }

    private fun showError(message: String) {
        error.text = message
        error.visibility = View.VISIBLE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("addressExpanded", findViewById<EditText>(R.id.manual_pump_address)?.visibility == View.VISIBLE)
        super.onSaveInstanceState(outState)
    }

    private companion object {
        /** How long the second tap is waited for; long enough to read the warnings. */
        const val CONFIRMATION_WINDOW_MS = 20_000L
        const val JOURNAL_LINES = 20
        const val DOUBLE_TAP_GUARD_MS = 1_500L
    }
}
