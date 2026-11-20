package app.aaps.wear.interaction.actions

import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether a dose is being entered or confirmed on screen right now. The phone's answer to an
 * earlier dose must not take the screen while that is so; see DataHandlerWear.
 *
 * Counted from a screen's start to its stop, not from resume to pause: between the input form's
 * pause and the confirmation's resume neither was "on screen", and an answer that arrived just then
 * replaced the confirmation (12.11.2026). A screen that is only covered for a moment still counts.
 */
object WatchUiState {
    private val inputScreens = AtomicInteger(0)

    val inputOnScreen: Boolean get() = inputScreens.get() > 0

    fun enterInput() { inputScreens.incrementAndGet() }

    fun leaveInput() { inputScreens.updateAndGet { if (it > 0) it - 1 else 0 } }

    @Volatile private var handedToWatchAtMs = 0L

    /**
     * The request went to the watch's own calculation because the phone was slow or away. The phone's
     * answer to it may still arrive; shown then, it lay on top of the watch's screen and the same food was
     * confirmed on both (12.11.2026 17:26: 10 g dosed 1.0 U by the phone and 0.7 U by the watch).
     */
    fun handOverToWatch(nowMs: Long = System.currentTimeMillis()) { handedToWatchAtMs = nowMs }

    /** A new request went to the phone: its answer is wanted again. */
    fun phoneAsked(nowMs: Long = System.currentTimeMillis()) { handedToWatchAtMs = 0L; phoneAskedAtMs = nowMs }

    @Volatile private var phoneAskedAtMs = 0L
    @Volatile private var phoneAnsweredAtMs = 0L

    /** Something came back from the phone about a request: its calculation to confirm, a refusal, a result. */
    fun phoneAnswered(nowMs: Long = System.currentTimeMillis()) { phoneAnsweredAtMs = nowMs }

    /**
     * True when the phone has answered since a request went out at [askedAtMs]. The form that sent the request
     * waits a few seconds and then offers the watch's own calculation "because the phone did not answer"; it
     * went on to say so, with all its buttons live, behind the phone's calculation and after the dose had been
     * confirmed (20.11.2026) - an invitation to give the dose a second time.
     */
    fun phoneAnsweredSince(askedAtMs: Long): Boolean = askedAtMs > 0L && phoneAnsweredAtMs >= askedAtMs

    /** True while an answer of the phone can only be the late one to a request the watch has taken over. */
    fun phoneAnswerComesLate(nowMs: Long = System.currentTimeMillis()): Boolean =
        handedToWatchAtMs > 0L && nowMs - handedToWatchAtMs in 0..HANDED_OVER_MS

    private const val HANDED_OVER_MS = 3 * 60_000L
}
