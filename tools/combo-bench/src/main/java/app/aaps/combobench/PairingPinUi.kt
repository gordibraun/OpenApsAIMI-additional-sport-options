package app.aaps.combobench

import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ScrollView

internal object PairingPinUi {
    fun close(editor: EditText, group: View, scroll: ScrollView) {
        // Wear keyboards can cover the entire screen; hiding the EditText does not dismiss the IME.
        editor.context.getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(editor.windowToken, 0)
        editor.clearFocus()
        editor.text.clear()
        editor.error = null
        group.visibility = View.GONE
        scroll.requestFocus()
        scroll.scrollTo(0, 0)
    }
}
