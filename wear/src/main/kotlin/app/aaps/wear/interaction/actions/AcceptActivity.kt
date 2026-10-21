@file:Suppress("DEPRECATION")

package app.aaps.wear.interaction.actions

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Button
import android.widget.LinearLayout
import android.view.Gravity
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.wear.interaction.utils.RotaryScrollView
import androidx.core.view.InputDeviceCompat
import androidx.core.view.MotionEventCompat
import androidx.core.view.ViewConfigurationCompat
import app.aaps.wear.R
import app.aaps.wear.comm.DataLayerListenerServiceWear
import app.aaps.wear.comm.IntentCancelNotification
import app.aaps.wear.comm.IntentWearToMobile
import app.aaps.wear.nondeprecated.GridPagerAdapterNonDeprecated
import kotlin.math.roundToInt

class AcceptActivity : ViewSelectorActivity() {

    var message = ""
    var actionKey = ""
    private var dismissThread: DismissThread? = null
    private var controlScroll: RotaryScrollView? = null
    private var confirmationMode = false
    private var deliveryStrip: TextView? = null
    private val deliveryListener: () -> Unit = { runOnUiThread { refreshDeliveryStrip() } }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        controlScroll?.handleRotary(event) == true || super.dispatchGenericMotionEvent(event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dismissThread = DismissThread()
        dismissThread?.start()
        val extras = intent.extras
        message = extras?.getString(DataLayerListenerServiceWear.KEY_MESSAGE, "") ?: ""
        actionKey = extras?.getString(DataLayerListenerServiceWear.KEY_ACTION_DATA, "") ?: ""
        if (message.isEmpty()) {
            finish()
            return
        }
        if (runCatching { EventData.deserialize(actionKey) }.getOrNull() is EventData.WatchControlConfirmed) {
            showControlConfirmation()
        } else setAdapter(MyGridViewPagerAdapter())
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        val vibratePattern = longArrayOf(0, 100, 50, 100, 50)
        vibrator.vibrate(vibratePattern, -1)
    }

    private fun showControlConfirmation() {
        fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
        confirmationMode = true
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(30), dp(30), dp(30), dp(40))
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(TextView(this@AcceptActivity).apply {
                text = intent.getStringExtra(DataLayerListenerServiceWear.KEY_TITLE) ?: "Подтверждение"
                textSize = 18f; gravity = Gravity.CENTER; setTextColor(android.graphics.Color.WHITE)
            })
            // The dose confirmed before this one: sent, delivered, failed. Its answer no longer
            // replaces this screen, so this line is where the user sees that it went through.
            deliveryStrip = TextView(this@AcceptActivity).apply {
                textSize = 12f; gravity = Gravity.CENTER; setTextColor(0xFFF2C14E.toInt())
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0); visibility = View.GONE
            }
            addView(deliveryStrip)
            // The phone's text is the decision (carbs, food, the dose) and, after a blank line, the
            // wizard's working. On a round screen the working pushed the button out of sight and
            // the whole thing read as a log (18 Oct 2026): now the decision and the button come
            // first, the working under them in small type.
            val decision = message.substringBefore("\n\n").trim()
            val working = message.substringAfter("\n\n", "").trim()
            addView(TextView(this@AcceptActivity).apply {
                text = decision; textSize = 16f; setTextColor(android.graphics.Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(10), 0, dp(10))
            })
            addView(Button(this@AcceptActivity).apply {
                text = "Подтвердить"; isAllCaps = false
                setOnClickListener {
                    isEnabled = false
                    // Remembered as "sent" before it goes, so the status line shows it at once.
                    DeliveryStatus.sent(this@AcceptActivity, DeliveryStatus.shortDecision(message))
                    startService(IntentWearToMobile(this@AcceptActivity, actionKey))
                    startForegroundService(IntentCancelNotification(this@AcceptActivity))
                    finishAffinity()
                }
            }, LinearLayout.LayoutParams(-1, -2))
            if (working.isNotEmpty()) addView(TextView(this@AcceptActivity).apply {
                text = working; textSize = 12f; setTextColor(android.graphics.Color.LTGRAY)
                setPadding(0, dp(8), 0, dp(8))
            })
            addView(Button(this@AcceptActivity).apply {
                text = "Отмена"; isAllCaps = false
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        controlScroll = RotaryScrollView(this).apply { addView(content) }
        setContentView(controlScroll)
        controlScroll?.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (confirmationMode) {
            WatchUiState.enterInput()
            DeliveryStatus.addListener(deliveryListener)
            refreshDeliveryStrip()
        }
    }

    override fun onPause() {
        if (confirmationMode) {
            DeliveryStatus.removeListener(deliveryListener)
            WatchUiState.leaveInput()
        }
        super.onPause()
        finish()
    }

    private fun refreshDeliveryStrip() {
        val strip = deliveryStrip ?: return
        val line = DeliveryStatus.line(this)
        strip.text = line ?: ""
        strip.visibility = if (line == null) View.GONE else View.VISIBLE
    }

    private inner class MyGridViewPagerAdapter : GridPagerAdapterNonDeprecated() {

        override fun getColumnCount(arg0: Int): Int = 2
        override fun getRowCount(): Int = 1

        override fun instantiateItem(container: ViewGroup, row: Int, col: Int): View = when (col) {
            0    -> {
                val view = LayoutInflater.from(applicationContext).inflate(R.layout.action_confirm_text, container, false)
                val textView = view.findViewById<TextView>(R.id.message)
                val scrollView = view.findViewById<View>(R.id.message_scroll)
                textView.text = message
                container.addView(view)
                scrollView.setOnGenericMotionListener { v: View, ev: MotionEvent ->
                    if (ev.action == MotionEvent.ACTION_SCROLL &&
                        ev.isFromSource(InputDeviceCompat.SOURCE_ROTARY_ENCODER)
                    ) {
                        val delta = -ev.getAxisValue(MotionEventCompat.AXIS_SCROLL) *
                            ViewConfigurationCompat.getScaledVerticalScrollFactor(
                                ViewConfiguration.get(container.context),
                                container.context
                            )
                        v.scrollBy(0, delta.roundToInt())
                        return@setOnGenericMotionListener true
                    }
                    false
                }
                scrollView.requestFocus()
                view
            }

            else -> {
                val view = LayoutInflater.from(applicationContext).inflate(R.layout.action_confirm_ok, container, false)
                val confirmButton = view.findViewById<ImageView>(R.id.confirmbutton)
                confirmButton.setOnClickListener {
                    if (actionKey.isNotEmpty()) startService(IntentWearToMobile(this@AcceptActivity, actionKey))
                    startForegroundService(IntentCancelNotification(this@AcceptActivity))
                    finishAffinity()
                }
                container.addView(view)
                view
            }
        }

        override fun destroyItem(container: ViewGroup, row: Int, col: Int, view: Any) {
            // Handle this to get the data before the view is destroyed?
            // Object should still be kept by this, just setup for re-init?
            container.removeView(view as View)
        }

        override fun isViewFromObject(view: View, `object`: Any): Boolean = view === `object`
    }

    @Synchronized public override fun onDestroy() {
        dismissThread?.invalidate()
        super.onDestroy()
    }

    private inner class DismissThread : Thread() {

        private var valid = true
        @Synchronized fun invalidate() {
            valid = false
        }

        override fun run() {
            SystemClock.sleep((60 * 1000L))
            synchronized(this) { if (valid) finish() }
        }
    }

    @Synchronized override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        dismissThread?.invalidate()
        intent.extras?.let {
            startActivity(Intent(this, AcceptActivity::class.java).apply { putExtras(it) })
            finish()
        }
    }
}
