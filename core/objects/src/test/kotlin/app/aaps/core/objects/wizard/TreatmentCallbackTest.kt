package app.aaps.core.objects.wizard

import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.queue.Callback
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class TreatmentCallbackTest {
    @Test fun carbPersistenceDoesNotOverwriteInsulinReceiptForSeparatedCallback() {
        var deliveries = 0
        var carbsStored = 0
        val callback = object : Callback() {
            override fun run() { deliveries++ }
            override fun onCarbsStored(result: PumpEnactResult) { carbsStored++ }
        }
        val receipt = mock<PumpEnactResult>()
        callback.result(receipt).run()
        callback.onCarbsStored(mock())
        assertThat(deliveries).isEqualTo(1)
        assertThat(carbsStored).isEqualTo(1)
        assertThat(callback.result).isSameInstanceAs(receipt)
    }

    @Test fun existingCallbacksRetainCarbNotificationBehavior() {
        var calls = 0
        val callback = object : Callback() { override fun run() { calls++ } }
        val receipt = mock<PumpEnactResult>()
        callback.onCarbsStored(receipt)
        assertThat(calls).isEqualTo(1)
        assertThat(callback.result).isSameInstanceAs(receipt)
    }
}
