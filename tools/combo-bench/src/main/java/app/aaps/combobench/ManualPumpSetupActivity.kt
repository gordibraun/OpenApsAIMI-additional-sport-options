package app.aaps.combobench

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
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

class ManualPumpSetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.MANUAL_TARGET) { finish(); return }
        val runtime = ManualPumpRuntime.get(this)
        val saved = runtime.target()
        val padding = (resources.displayMetrics.density * 26).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, (resources.displayMetrics.density * 18).toInt(), padding, padding)
        }
        fun label(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; column.addView(this) }
        label("Combo: стенд", 18f)
        label("Номер тестовой помпы", 14f)
        val serial = EditText(this).apply {
            id = R.id.manual_pump_serial
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(8))
            setText(saved?.serial ?: "10392647")
            textSize = 18f
            isSingleLine = true
            contentDescription = "Номер тестовой помпы"
            column.addView(this)
        }
        val addressLabel = label("Bluetooth-адрес", 12f).apply {
            setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.arrow_down_float, 0)
            minHeight = (resources.displayMetrics.density * 32).toInt()
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val address = EditText(this).apply {
            id = R.id.manual_pump_address
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(17))
            setText(saved?.address.orEmpty())
            hint = "Автоматически"
            textSize = 12f
            isSingleLine = true
            contentDescription = "Bluetooth-адрес помпы"
            visibility = if (saved?.address != null || savedInstanceState?.getBoolean("addressExpanded") == true)
                View.VISIBLE else View.GONE
            column.addView(this)
        }
        addressLabel.setOnClickListener {
            address.visibility = if (address.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        val error = label("", 12f).apply { visibility = View.GONE; setTextColor(Color.rgb(255, 140, 130)) }
        val pair = Button(this).apply {
            text = "Сопряжение"; isAllCaps = false; textSize = 14f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(45, 45, 45))
            setOnClickListener {
                try {
                    val permissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE,
                        Manifest.permission.BLUETOOTH_SCAN).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                    if (permissions.isNotEmpty()) { requestPermissions(permissions.toTypedArray(), 1); return@setOnClickListener }
                    runtime.configure(serial.text.toString(), address.text.toString())
                    startActivity(Intent(this@ManualPumpSetupActivity, ManualPairingActivity::class.java))
                } catch (e: Exception) { error.text = e.message ?: "Проверьте номер и адрес"; error.visibility = View.VISIBLE }
            }
        }
        column.addView(pair, column.indexOfChild(serial) + 1,
            LinearLayout.LayoutParams(-1, (resources.displayMetrics.density * 48).toInt()))
        label("Подача инсулина недоступна", 12f)
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
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("addressExpanded", findViewById<EditText>(R.id.manual_pump_address)?.visibility == View.VISIBLE)
        super.onSaveInstanceState(outState)
    }
}
