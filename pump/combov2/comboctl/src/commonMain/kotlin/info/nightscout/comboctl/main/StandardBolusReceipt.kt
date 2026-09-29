package info.nightscout.comboctl.main

import info.nightscout.comboctl.base.ApplicationLayer.CMDHistoryEvent
import info.nightscout.comboctl.base.ApplicationLayer.CMDHistoryEventDetail
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant
import kotlin.time.ExperimentalTime

/** Only the post-command history delta can identify this delivery, including a partial bolus. */
@OptIn(ExperimentalTime::class)
internal fun findStandardBolusReceipt(
    historyDelta: List<CMDHistoryEvent>,
    utcOffset: UtcOffset,
    requestedAmount: Int
): Pump.LastBolus? {
    val entry = historyDelta.filter {
        it.detail.isBolusDetail && it.detail !is CMDHistoryEventDetail.StandardBolusRequested
    }.singleOrNull() ?: return null
    val detail = entry.detail as? CMDHistoryEventDetail.StandardBolusInfused ?: return null
    if (detail.manual || detail.bolusAmount !in 0..requestedAmount) return null
    // Preserve the exact timestamp emitted to PumpSync, not the display's clock-clamped timestamp.
    return Pump.LastBolus(entry.eventCounter, detail.bolusAmount, entry.timestamp.toInstant(utcOffset))
}
