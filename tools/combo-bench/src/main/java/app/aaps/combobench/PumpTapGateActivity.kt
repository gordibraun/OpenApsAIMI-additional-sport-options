package app.aaps.combobench

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * In front of the controller's complications on the watch face - the owner's main screen. Three quick taps on
 * any of them have the pump read («Обновить с помпы»); a tap that no others follow opens, a moment later, what
 * that complication always opened: the pump's screen or the journal.
 *
 * The owner's words (15.11.2026): the button for reading the pump belongs on the main screen and answers to
 * three quick taps, like the other buttons there (the glucose, which opens the sensor's setup the same way).
 *
 * A tap on a complication arrives as a launch, and launches cannot be counted where they come from; so each
 * lands here, is noted, and this screen is gone again at once - it shows nothing - so that the next tap reaches
 * the face and not this window.
 */
class PumpTapGateActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        if (BuildConfig.MANUAL_TARGET) {
            val now = SystemClock.elapsedRealtime()
            val plain = if (intent.getStringExtra(EXTRA_PLAIN) == PLAIN_LOG) ControlLogActivity::class.java else ManualPumpSetupActivity::class.java
            val app = applicationContext
            when (val tap = TapGate.tap(now)) {
                TapGate.Tap.Third      ->
                    runCatching { startActivity(Intent(this, PumpReadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

                is TapGate.Tap.Earlier ->
                    // If no tap follows this one, it was a plain tap. Started from the application, a moment after
                    // this screen has gone: the system lets an app do that for a few seconds after its screen closed.
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (TapGate.settleAsPlain(tap.id))
                            runCatching { app.startActivity(Intent(app, plain).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }, TapGate.PLAIN_AFTER_MS)
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {

        /** What a plain tap opens: [PLAIN_CONTROLLER] (the pump's screen) or [PLAIN_LOG] (the journal). */
        const val EXTRA_PLAIN = "plain"
        const val PLAIN_CONTROLLER = "controller"
        const val PLAIN_LOG = "log"
    }
}

/**
 * Counts taps that arrive one launch at a time: the third within [WINDOW_MS] is the triple tap; an earlier one
 * that nothing follows for [PLAIN_AFTER_MS] is a plain tap.
 */
internal object TapGate {

    sealed interface Tap {

        /** The third quick tap. */
        data object Third : Tap

        /** The first or second; [id] is asked about again with [settleAsPlain] once the wait is over. */
        data class Earlier(val id: Long) : Tap
    }

    const val TAPS = 3
    const val WINDOW_MS = 1_000L
    const val PLAIN_AFTER_MS = 700L

    private val taps = ArrayDeque<Long>()
    private var lastId = 0L

    @Synchronized fun tap(nowElapsedMs: Long): Tap {
        while (taps.isNotEmpty() && nowElapsedMs - taps.first() > WINDOW_MS) taps.removeFirst()
        taps.addLast(nowElapsedMs)
        lastId++
        if (taps.size >= TAPS) {
            taps.clear()
            return Tap.Third
        }
        return Tap.Earlier(lastId)
    }

    /** True when the tap [id] is still the last one and no triple tap took it: it stands as a plain tap, once. */
    @Synchronized fun settleAsPlain(id: Long): Boolean {
        if (id != lastId || taps.isEmpty()) return false
        taps.clear()
        return true
    }
}
