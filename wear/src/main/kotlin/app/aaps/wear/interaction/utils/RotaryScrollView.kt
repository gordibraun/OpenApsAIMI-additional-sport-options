package app.aaps.wear.interaction.utils

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ScrollView
import androidx.core.view.InputDeviceCompat
import androidx.core.view.ViewConfigurationCompat
import kotlin.math.roundToInt

class RotaryScrollView(context: Context) : ScrollView(context) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // Called by the Activity before a focused radio button can consume the crown event.
    fun handleRotary(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_SCROLL || !event.isFromSource(InputDeviceCompat.SOURCE_ROTARY_ENCODER)) return false
        val factor = ViewConfigurationCompat.getScaledVerticalScrollFactor(ViewConfiguration.get(context), context)
        val delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL) * factor
        if (delta.isFinite()) scrollBy(0, delta.roundToInt())
        return true
    }
}
