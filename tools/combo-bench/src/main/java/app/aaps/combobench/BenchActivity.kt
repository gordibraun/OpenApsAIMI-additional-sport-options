package app.aaps.combobench

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

class BenchActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var probe: Button
    private lateinit var transfer: Button
    private lateinit var stopPairing: Button
    private var visibilityRequestedFor = ""
    private val handler = Handler(Looper.getMainLooper())
    private val update = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.MANUAL_TARGET) {
            startActivity(Intent(this, ManualPumpSetupActivity::class.java))
            finish()
            return
        }
        visibilityRequestedFor = savedInstanceState?.getString("visibilityRequestedFor").orEmpty()
        val round = resources.configuration.isScreenRound
        val padding = (resources.displayMetrics.density * if (round) 28 else 20).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        column.addView(TextView(this).apply {
            text = getString(R.string.bench_title, BuildConfig.VERSION_NAME.substringBefore('-'))
            textSize = 18f
        })
        column.addView(TextView(this).apply {
            setText(R.string.no_insulin_delivery)
            textSize = 13f
            setTextColor(Color.rgb(255, 195, 50))
        })
        column.addView(Button(this).apply {
            setText(R.string.manual_pairing_title)
            textSize = 15f
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@BenchActivity, ManualPairingActivity::class.java)) }
        })
        status = TextView(this).apply { textSize = if (round) 13f else 16f; setPadding(0, 12, 0, 12) }
        column.addView(status)
        fun button(label: String, command: String) = Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            backgroundTintList = ColorStateList.valueOf(Color.rgb(45, 45, 45))
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(Color.WHITE, Color.rgb(145, 145, 145))
            ))
            setOnClickListener { BenchRuntime.get(this@BenchActivity).run(command) }
            column.addView(this)
        }
        button("Обновить связь", "refresh")
        probe = button("Проверить Combo", "probe")
        transfer = button("Передать проверку", "transfer")
        stopPairing = button("Остановить сопряжение", "pairing-stop")
        val scroll = ScrollView(this).apply {
            addView(column)
            isFocusableInTouchMode = true
            setOnGenericMotionListener { _, event ->
                if (event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                    val amount = -event.getAxisValue(MotionEvent.AXIS_SCROLL) * ViewConfiguration.get(this@BenchActivity).scaledVerticalScrollFactor
                    scrollBy(0, amount.toInt())
                    true
                } else false
            }
        }
        setContentView(scroll)
        scroll.requestFocus()
        val permissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_SCAN).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), 1)
        BenchRuntime.get(this).run("discover")
    }

    override fun onResume() { super.onResume(); if (!BuildConfig.MANUAL_TARGET) handler.post(update) }
    override fun onPause() { handler.removeCallbacks(update); super.onPause() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("visibilityRequestedFor", visibilityRequestedFor)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Platform Activity callback for the diagnostic discoverability dialog")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 5) BenchRuntime.get(this).pairingVisibilityResult(visibilityRequestedFor, resultCode)
    }

    private fun render() {
        val data = BenchRuntime.get(this).snapshot()
        val pairing = data.optJSONObject("pairing")
        val pairingActive = pairing?.optBoolean("active") == true
        stopPairing.visibility = if (pairingActive) View.VISIBLE else View.GONE
        if (pairingActive) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (pairingActive && pairing?.optString("stage") == "WAITING_VISIBILITY" &&
            visibilityRequestedFor != pairing.optString("id")) {
            visibilityRequestedFor = pairing.getString("id")
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 180), 5)
            } catch (_: Exception) {
                BenchRuntime.get(this).pairingVisibilityResult(visibilityRequestedFor, null)
            }
        }
        val state = data.optJSONObject("ownership")
        val local = data.optString("localNode")
        val isOwner = state?.optString("owner") == local && local.isNotEmpty()
        val ready = isOwner && state?.has("operation") == false && data.optString("error").isEmpty()
        probe.isEnabled = ready
        transfer.isEnabled = ready
        val probeResult = data.optJSONObject("probe")
        val probeState = probeResult?.optString("stage")
        status.text = buildString {
            appendLine("Помпа: ${data.optString("pump", "не настроена")}")
            pairing?.let { appendLine(it.optString("detail", "Подготовка Bluetooth-сопряжения")) }
            appendLine("Устройств рядом: ${data.optJSONArray("peers")?.length() ?: 0}")
            appendLine(if (state == null) "Стенд не настроен" else if (isOwner) "Проверка: это устройство" else "Проверка: другое устройство")
            state?.let {
                appendLine("Поколение: ${it.optLong("generation")}")
                if (it.has("outbox")) appendLine("Ожидается подтверждение передачи")
                if (it.has("operation")) appendLine("Операция не завершена")
            }
            probeResult?.let {
                appendLine("Последняя попытка: ${DateFormat.getTimeInstance().format(Date(it.getLong("at")))}")
            }
            appendLine(when (probeState) {
                "RFCOMM_OK_ZERO_BYTES" -> "Проверка: канал открывался\nКоманд отправлено: 0"
                "RFCOMM_CONNECTING" -> "Combo: подключение"
                "RFCOMM_TIMEOUT" -> "Combo: нет ответа"
                "RFCOMM_FAILED" -> "Проверка: подключиться не удалось"
                "RFCOMM_PAIRING_BLOCKED" -> "Сопряжение изменилось или запрошено заново. Проверка остановлена; автоматический повтор запрещён."
                else -> "Combo: ещё не проверена"
            })
            if (data.optString("error").isNotEmpty()) appendLine(data.getString("error"))
        }
    }
}
