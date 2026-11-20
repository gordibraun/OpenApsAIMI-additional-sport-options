package app.aaps.wear.interaction.actions

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.aaps.wear.combo.ComboRelay
import app.aaps.wear.combo.ComboWatchMode
import kotlin.math.roundToInt

/**
 * What became of the dose confirmed a moment ago, large and by itself: on its way, given, not given, or no
 * answer. Opened right after «Подтвердить».
 *
 * 20.11.2026: the owner confirmed 2.0 U at 11:32:20 and the phone's «подано» came at 11:34:01. For that minute
 * and forty seconds the watch went back to the input form - emptied, every button live - with one yellow line
 * above it that was cut off before the dose. He read it as "the calculation failed, press again", which with a
 * dose already on its way to the pump is how a meal gets dosed twice. Nothing here can be pressed to give
 * insulin: the screen only tells, and closes by itself once the dose has landed.
 *
 * It counts as an input screen ([WatchUiState]), so the phone's answer does not open a screen of its own over
 * it: the answer changes what stands here.
 */
class DeliveryActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var head: TextView
    private lateinit var note: TextView
    private lateinit var readPump: Button
    private lateinit var close: Button
    private var closingAfterDone = false
    private var lastShown = ""
    private val listener: () -> Unit = { runOnUiThread { refresh() } }
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1_000L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
        head = TextView(this).apply {
            gravity = Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.BOLD)
            // One line whatever the dose: «⏳ Подаётся 12,5 ЕД» is wider than the round screen gives at full size.
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(13, 20, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        note = TextView(this).apply { textSize = 13f; gravity = Gravity.CENTER; setTextColor(0xFFD0D0D0.toInt()) }
        readPump = Button(this).apply {
            text = "Обновить с помпы"; isAllCaps = false; textSize = 13f; visibility = View.GONE
            setOnClickListener {
                runCatching {
                    startActivity(Intent().setClassName(ComboRelay.CONTROLLER_PACKAGE, ComboRelay.CONTROLLER_PUMP_READ_ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
        close = Button(this).apply {
            text = "Закрыть"; isAllCaps = false; textSize = 13f; visibility = View.GONE
            setOnClickListener { finish() }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(34), dp(28), dp(40))
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(TextView(this@DeliveryActivity).apply { text = "Доза"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(android.graphics.Color.WHITE) })
            addView(head, LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(4) })
            addView(note, LinearLayout.LayoutParams(-1, -2))
            addView(readPump, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(close, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(android.graphics.Color.BLACK); isFillViewport = true; addView(column) })
    }

    /** The one instance is asked for again: another dose has been confirmed. What it showed of the last one is over. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handler.removeCallbacksAndMessages(null)
        closingAfterDone = false
        lastShown = ""
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
        DeliveryStatus.addListener(listener)
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        DeliveryStatus.removeListener(listener)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        val progress = DeliveryStatus.progress(this, now)
        if (progress == null) { finish(); return }
        head.text = DeliveryStatus.headline(progress)
        head.setTextColor(
            when (progress.phase) {
                DeliveryStatus.Phase.IN_FLIGHT -> AMBER
                DeliveryStatus.Phase.NO_ANSWER -> AMBER
                DeliveryStatus.Phase.DONE      -> GREEN
                DeliveryStatus.Phase.FAILED    -> RED
            }
        )
        note.text = DeliveryStatus.detail(progress, now)
        // Only when the answer is overdue is there anything to do here: read the pump, to see whether the dose landed.
        readPump.visibility = if (progress.phase == DeliveryStatus.Phase.NO_ANSWER && progress.isDose && ComboWatchMode.isWatchMode(this)) View.VISIBLE else View.GONE
        close.visibility = if (progress.phase == DeliveryStatus.Phase.IN_FLIGHT) View.GONE else View.VISIBLE
        // What stands on the screen, for the log: once per state, not once per second of the count.
        val shown = "${progress.phase} | ${head.text} | readPump=${readPump.visibility == View.VISIBLE} close=${close.visibility == View.VISIBLE}"
        if (shown != lastShown) {
            lastShown = shown
            android.util.Log.i(DeliveryStatus.LOG_TAG, "dose screen: $shown | ${note.text}")
        }
        // Given: nothing more to read or do. (The form the request came from closed itself when the dose was confirmed.)
        if (progress.phase == DeliveryStatus.Phase.DONE && !closingAfterDone) {
            closingAfterDone = true
            handler.postDelayed({
                if (!isFinishing) {
                    android.util.Log.i(DeliveryStatus.LOG_TAG, "dose screen: closes by itself, the dose is given")
                    finish()
                }
            }, CLOSE_AFTER_DONE_MS)
        }
    }

    private companion object {

        const val AMBER = 0xFFF2C14E.toInt()
        const val GREEN = 0xFF7BD88F.toInt()
        const val RED = 0xFFFF6B6B.toInt()
        const val CLOSE_AFTER_DONE_MS = 6_000L
    }
}
