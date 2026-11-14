package app.aaps.wear.interaction.actions

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WatchUiStateTest {

    @Test
    fun `the phone's answer to a request the watch took over is late for three minutes`() {
        val t = 1_000_000_000L
        WatchUiState.phoneAsked()
        assertFalse(WatchUiState.phoneAnswerComesLate(t))
        WatchUiState.handOverToWatch(t)
        assertTrue(WatchUiState.phoneAnswerComesLate(t + 5_000L))
        assertTrue(WatchUiState.phoneAnswerComesLate(t + 170_000L))
        assertFalse(WatchUiState.phoneAnswerComesLate(t + 200_000L))
    }

    @Test
    fun `a new request to the phone makes its answer wanted again`() {
        val t = 2_000_000_000L
        WatchUiState.handOverToWatch(t)
        WatchUiState.phoneAsked()
        assertFalse(WatchUiState.phoneAnswerComesLate(t + 5_000L))
    }
}
