package app.aaps.combobench.controller

import app.aaps.pump.combowatch.executor.AutonomyPolicy

/**
 * What the watch face shows about the pump, the link to the phone and the forecast - in one
 * object, read by the complication services. Everything in it is already known to the controller;
 * nothing is computed for the face.
 */
class FaceFacts(
    val heldPump: String?,
    /** When the phone was last heard from, by this watch's clock; zero if never. */
    val phoneHeardEpochMs: Long,
    val leaseLive: Boolean,
    val pumpReachable: Boolean,
    /** When the pump was last read; null if never. */
    val pumpReadAtEpochMs: Long?,
    val tbr: Tbr?,
    val reservoirUnits: Int?,
    val mode: AutonomyPolicy.Mode,
    /** True while the watch is on its own with the pump. */
    val alone: Boolean,
    val forecast: Forecast?
) {

    /** The temporary basal running now, as far as the controller knows. */
    class Tbr(val percent: Int, val endsAtEpochMs: Long)

    /** Lowest and last value of the four-hour forecast, and whose it is. */
    class Forecast(val minMgdl: Int, val endMgdl: Int, val madeAtEpochMs: Long, val byWatch: Boolean)
}
