package app.aaps.implementation.queue

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.Callback
import app.aaps.implementation.queue.commands.CommandSMBBolus
import app.aaps.implementation.queue.commands.CommandTempBasalAbsolute
import app.aaps.implementation.queue.commands.CommandTempBasalPercent
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.verify
import org.mockito.kotlin.never
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class CommandInputValidationTest : TestBaseWithProfile() {
    private fun staleCallback() = object : Callback() {
        override fun validationErrorBeforeDelivery() = "changed insulin inputs"
        override fun run() { assertThat(result.enacted).isFalse(); assertThat(result.success).isFalse() }
    }

    @Test fun staleSmbCannotCallPumpDriver() {
        val callback = staleCallback()
        val command = CommandSMBBolus(injector, DetailedBolusInfo().apply { insulin = 1.3 }, callback)
        command.aapsLogger = aapsLogger
        command.activePlugin = activePlugin
        command.pumpEnactResultProvider = pumpEnactResultProvider
        command.execute()
        assertThat(callback.result.comment).isEqualTo("changed insulin inputs")
        verify(activePlugin, never()).activePump
    }

    @Test fun staleAbsoluteBasalCannotCallPumpDriver() {
        val callback = staleCallback()
        val command = CommandTempBasalAbsolute(injector, 2.64, 30, false, validProfile, PumpSync.TemporaryBasalType.NORMAL, callback)
        command.aapsLogger = aapsLogger
        command.activePlugin = activePlugin
        command.pumpEnactResultProvider = pumpEnactResultProvider
        command.execute()
        assertThat(callback.result.comment).isEqualTo("changed insulin inputs")
        verify(activePlugin, never()).activePump
    }

    @Test fun stalePercentBasalCannotCallPumpDriver() {
        val callback = staleCallback()
        val command = CommandTempBasalPercent(injector, 240, 30, false, validProfile, PumpSync.TemporaryBasalType.NORMAL, callback)
        command.aapsLogger = aapsLogger
        command.activePlugin = activePlugin
        command.pumpEnactResultProvider = pumpEnactResultProvider
        command.execute()
        assertThat(callback.result.comment).isEqualTo("changed insulin inputs")
        verify(activePlugin, never()).activePump
    }

    @Test fun unchangedSmbIsStillExecutedAndReported() {
        val pump = mock<app.aaps.core.interfaces.pump.Pump>()
        whenever(activePlugin.activePump).thenReturn(pump)
        val info = DetailedBolusInfo().apply { insulin = 0.1; deliverAtTheLatest = System.currentTimeMillis() }
        val receipt = pumpEnactResultProvider.get().success(true).enacted(true)
        whenever(pump.deliverTreatment(info)).thenReturn(receipt)
        val callback = object : Callback() { override fun run() {} }
        val command = CommandSMBBolus(injector, info, callback)
        command.aapsLogger = aapsLogger
        command.activePlugin = activePlugin
        command.pumpEnactResultProvider = pumpEnactResultProvider
        command.persistenceLayer = mock()
        command.dateUtil = dateUtil
        command.preferences = preferences
        command.execute()
        verify(pump).deliverTreatment(info)
        assertThat(callback.result).isSameInstanceAs(receipt)
    }

    @Test fun protectiveZeroBasalWithoutValidationIsNotBlocked() {
        val pump = mock<app.aaps.core.interfaces.pump.Pump>()
        whenever(activePlugin.activePump).thenReturn(pump)
        val receipt = pumpEnactResultProvider.get().success(true).enacted(true)
        whenever(pump.setTempBasalAbsolute(0.0, 30, validProfile, false, PumpSync.TemporaryBasalType.NORMAL)).thenReturn(receipt)
        val callback = object : Callback() { override fun run() {} }
        val command = CommandTempBasalAbsolute(injector, 0.0, 30, false, validProfile, PumpSync.TemporaryBasalType.NORMAL, callback)
        command.aapsLogger = aapsLogger
        command.activePlugin = activePlugin
        command.pumpEnactResultProvider = pumpEnactResultProvider
        command.execute()
        verify(pump).setTempBasalAbsolute(0.0, 30, validProfile, false, PumpSync.TemporaryBasalType.NORMAL)
        assertThat(callback.result).isSameInstanceAs(receipt)
    }
}
