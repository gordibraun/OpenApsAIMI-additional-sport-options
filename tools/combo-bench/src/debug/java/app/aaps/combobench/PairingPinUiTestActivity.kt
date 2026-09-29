package app.aaps.combobench

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView

/** UI-only fixture: never initializes BenchRuntime or accesses pump state. */
class PairingPinUiTestActivity : Activity() {
    lateinit var editor: EditText
    lateinit var group: LinearLayout
    lateinit var scroll: ScrollView

    @SuppressLint("WearPasswordInput")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setTurnScreenOn(true)
        editor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            isSingleLine = true
            isSaveEnabled = false
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { closeEditor(); true } else false
            }
        }
        group = LinearLayout(this).apply { addView(editor) }
        scroll = ScrollView(this).apply { isFocusableInTouchMode = true; addView(group) }
        setContentView(scroll)
    }

    fun showEditor() {
        group.visibility = android.view.View.VISIBLE
        editor.requestFocus()
        // Match an explicit field tap; Wear keyboards can reject automatic SHOW_IMPLICIT requests.
        getSystemService(InputMethodManager::class.java).showSoftInput(editor, 0)
    }

    fun closeEditor() = PairingPinUi.close(editor, group, scroll)
}
