package app.aaps.combobench.controller

import app.aaps.pump.combowatch.executor.AutonomyPolicy
import app.aaps.pump.combowatch.regulation.ActivityRecord
import app.aaps.pump.combowatch.regulation.GlucoseReading

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
    /** Who leads the basal now, and since when; see [LeadershipLog]. */
    val leader: LeadershipLog.Leader,
    val leaderSinceEpochMs: Long,
    val forecast: Forecast?,
    /** The watch's own sensor readings of the last hour and a half, oldest first; what the graph plots. */
    val readings: List<GlucoseReading> = emptyList(),
    /** Change per five minutes from the latest readings; null when not known. */
    val deltaPer5Min: Double? = null,
    /** Insulin on board, units, as the leader knows it: the phone's snapshot, or the watch's own model. */
    val iobU: Double? = null,
    /** Carbohydrates on board, grams, when known. */
    val cobG: Double? = null,
    /** The phone's glucose target, mg/dL, when known. */
    val targetMgdl: Double? = null,
    /** The walk or sport session entered on the watch that matters now, if any. */
    val activity: ActivityRecord? = null,
    /** Grams of carbohydrate that would bring the forecast back up to the target; null when none are needed. */
    val carbsNeededG: Int? = null
) {

    /** The temporary basal running now, as far as the controller knows. */
    class Tbr(val percent: Int, val endsAtEpochMs: Long)

    /** Lowest and last value of the four-hour forecast, whose it is, and the series itself, five minutes apart from now. */
    class Forecast(val minMgdl: Int, val endMgdl: Int, val madeAtEpochMs: Long, val byWatch: Boolean, val series: List<Int> = emptyList())
}
