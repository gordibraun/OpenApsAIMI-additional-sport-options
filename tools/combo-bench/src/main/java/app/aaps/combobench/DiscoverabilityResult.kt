package app.aaps.combobench

import android.app.Activity

internal object DiscoverabilityResult {
    // Phones normally return a duration; Wear system dialogs may return RESULT_OK.
    fun accepted(
        resultCode: Int?,
        wearDialog: Boolean = false,
        wasDiscoverable: Boolean = false,
        isDiscoverable: Boolean = false
    ): Boolean = resultCode != null && (
        resultCode == Activity.RESULT_OK || resultCode > 0 ||
            // ClockworkSettings on the tested watch never calls setResult(), even after Allow.
            (resultCode == Activity.RESULT_CANCELED && wearDialog && !wasDiscoverable && isDiscoverable)
        )
}
