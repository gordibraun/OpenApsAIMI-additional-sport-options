package app.aaps.combobench

import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingPinUiTest {
    @Test fun confirmationClosesKeyboardAndClearsEditorImmediately() = withEditor { scenario ->
        scenario.onActivity { it.closeEditor() }
        assertClosed(scenario)
    }

    @Test fun keyboardDoneClosesFullScreenIme() = withEditor { scenario ->
        scenario.onActivity { it.editor.onEditorAction(EditorInfo.IME_ACTION_DONE) }
        assertClosed(scenario)
    }

    @Test fun repeatedCloseAndNewPinRequestRemainUsable() = withEditor { scenario ->
        scenario.onActivity { it.closeEditor(); it.closeEditor() }
        assertClosed(scenario)
        scenario.onActivity { it.showEditor() }
        awaitIme(scenario, visible = true)
        scenario.onActivity {
            assertEquals(View.VISIBLE, it.group.visibility)
            assertTrue(it.editor.hasFocus())
            assertEquals("", it.editor.text.toString())
            it.closeEditor()
        }
        assertClosed(scenario)
    }

    private fun withEditor(test: (ActivityScenario<PairingPinUiTestActivity>) -> Unit) {
        ActivityScenario.launch(PairingPinUiTestActivity::class.java).use { scenario ->
            val deadline = SystemClock.uptimeMillis() + 5_000
            var focused = false
            do {
                scenario.onActivity { focused = it.hasWindowFocus() }
                if (focused) break
                SystemClock.sleep(50)
            } while (SystemClock.uptimeMillis() < deadline)
            assertTrue("Watch must be unlocked and the charging overlay dismissed", focused)
            scenario.onActivity { it.showEditor() }
            awaitIme(scenario, visible = true)
            scenario.onActivity { it.editor.setText("0000000000") }
            test(scenario)
        }
    }

    private fun assertClosed(scenario: ActivityScenario<PairingPinUiTestActivity>) {
        scenario.onActivity {
            assertEquals(View.GONE, it.group.visibility)
            assertFalse(it.editor.hasFocus())
            assertEquals("", it.editor.text.toString())
            assertNull(it.editor.error)
            assertEquals(0, it.scroll.scrollY)
        }
        awaitIme(scenario, visible = false)
    }

    private fun awaitIme(scenario: ActivityScenario<PairingPinUiTestActivity>, visible: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            var actual: Boolean? = null
            scenario.onActivity { actual = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) }
            if (actual == visible) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("IME did not become visible=$visible within 5 seconds")
    }
}
