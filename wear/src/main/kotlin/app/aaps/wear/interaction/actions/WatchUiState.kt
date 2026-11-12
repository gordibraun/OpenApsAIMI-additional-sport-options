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
}
