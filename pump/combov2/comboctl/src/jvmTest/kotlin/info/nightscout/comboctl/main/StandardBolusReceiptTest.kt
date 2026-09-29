package info.nightscout.comboctl.main

import info.nightscout.comboctl.base.ApplicationLayer.CMDHistoryEvent
import info.nightscout.comboctl.base.ApplicationLayer.CMDHistoryEventDetail
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class StandardBolusReceiptTest {
    private val time = LocalDateTime(2026, 9, 11, 14, 58, 15)
    private val offset = UtcOffset(hours = 3)
    private fun infused(amount: Int = 7, manual: Boolean = false) =
        CMDHistoryEvent(time, 123L, CMDHistoryEventDetail.StandardBolusInfused(amount, manual))

    @Test
    fun fullDeliveryPreservesPumpSyncTimestampAndId() {
        val requested = CMDHistoryEvent(time, 122L, CMDHistoryEventDetail.StandardBolusRequested(7, false))
        val receipt = findStandardBolusReceipt(listOf(requested, infused()), offset, 7)
        assertEquals(Pump.LastBolus(123L, 7, time.toInstant(offset)), receipt)
    }

    @Test
    fun partialAndZeroInfusionsUseActualAmount() {
        assertEquals(3, findStandardBolusReceipt(listOf(infused(3)), offset, 7)?.bolusAmount)
        assertEquals(0, findStandardBolusReceipt(listOf(infused(0)), offset, 7)?.bolusAmount)
    }

    @Test
    fun missingHistoryDoesNotInventReceiptFromPreviousBolus() {
        assertNull(findStandardBolusReceipt(emptyList(), offset, 7))
        val requested = CMDHistoryEvent(time, 122L, CMDHistoryEventDetail.StandardBolusRequested(7, false))
        assertNull(findStandardBolusReceipt(listOf(requested), offset, 7))
    }

    @Test
    fun duplicateOrUnexpectedBolusIsNotAssignedToCommand() {
        assertNull(findStandardBolusReceipt(listOf(infused(), infused()), offset, 7))
        val quick = CMDHistoryEvent(time, 124L, CMDHistoryEventDetail.QuickBolusInfused(5))
        assertNull(findStandardBolusReceipt(listOf(infused(), quick), offset, 7))
        assertNull(findStandardBolusReceipt(listOf(quick), offset, 7))
    }

    @Test
    fun manualAndOutOfRangeAmountsCannotConfirmCommand() {
        assertNull(findStandardBolusReceipt(listOf(infused(manual = true)), offset, 7))
        assertNull(findStandardBolusReceipt(listOf(infused(8)), offset, 7))
        assertNull(findStandardBolusReceipt(listOf(infused(-1)), offset, 7))
    }

    @Test
    fun pumpClockAheadIsNotClampedToPhoneClock() {
        val future = infused().copy(timestamp = LocalDateTime(2099, 1, 1, 12, 0))
        assertEquals(future.timestamp.toInstant(offset), findStandardBolusReceipt(listOf(future), offset, 7)?.timestamp)
    }
}
