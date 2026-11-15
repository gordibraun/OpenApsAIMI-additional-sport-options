package app.aaps.combobench

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent

/**
 * In front of the controller's complications on the watch face - the owner's main screen. Three quick taps on
 * any of them have the pump read («Обновить с помпы»); a tap that no others follow opens, a moment later, what
 * that complication always opened: the pump's screen or the journal.
 *
 * The owner's words (15.11.2026): the button for reading the pump belongs on the main screen and answers to
 * three quick taps, like the other buttons there (the glucose, which opens the sensor's setup the same way).
 *
 * The first tap on a complication arrives as a launch of this screen, which shows nothing - the face stays
 * visible through it - and waits a moment. The taps that follow land on this window itself, wherever on the
 * screen they fall, or, if they were quicker than the window, arrive as further launches; both are counted.
 * Whatever is opened is opened while this screen is in front, so the system never has a reason to refuse it:
 * opening the plain target from the background a moment after closing was tried first and rests on a grace
 * the system gives or withholds as it sees fit.
 */
class PumpTapGateActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var waiting: TapGate.Tap.Earlier? = null
    private val plainTap = Runnable {
        val tap = waiting ?: return@Runnable
        if (TapGate.settleAsPlain(tap.id)) open(plainTarget())
        close()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        if (!BuildConfig.MANUAL_TARGET) { close(); return }
        tap()
    }

    /** A tap quicker than this window: the complication was hit again and launched this screen once more. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tap()
    }

    /** A tap on this window, wherever it falls: the face under it is not reached while this screen waits. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) tap()
        return true
    }

    private fun tap() {
        handler.removeCallbacks(plainTap)
        when (val tap = TapGate.tap(SystemClock.elapsedRealtime())) {
            TapGate.Tap.Third      -> { waiting = null; open(PumpReadActivity::class.java); close() }
            is TapGate.Tap.Earlier -> { waiting = tap; handler.postDelayed(plainTap, TapGate.PLAIN_AFTER_MS) }
        }
    }

    private fun plainTarget(): Class<out Activity> =
        if (intent.getStringExtra(EXTRA_PLAIN) == PLAIN_LOG) ControlLogActivity::class.java else ManualPumpSetupActivity::class.java

    private fun open(target: Class<out Activity>) {
        runCatching { startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun close() {
        handler.removeCallbacks(plainTap)
        finish()
        overridePendingTransition(0, 0)
    }

    /** Covered or switched off before the wait was over: nothing is opened behind the owner's back. */
    override fun onStop() {
        handler.removeCallbacks(plainTap)
        waiting?.let { TapGate.settleAsPlain(it.id) }
        waiting = null
        if (!isFinishing) finish()
        super.onStop()
    }

    companion object {

        /** What a plain tap opens: [PLAIN_CONTROLLER] (the pump's screen) or [PLAIN_LOG] (the journal). */
        const val EXTRA_PLAIN = "plain"
        const val PLAIN_CONTROLLER = "controller"
        const val PLAIN_LOG = "log"
    }
}

/**
 * Counts the taps: the third within [WINDOW_MS] is the triple tap; an earlier one that nothing follows for
 * [PLAIN_AFTER_MS] is a plain tap.
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
