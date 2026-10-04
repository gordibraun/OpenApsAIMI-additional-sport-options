package app.aaps.pump.combowatch.regulation

import app.aaps.core.objects.activity.ActivityPlanCalculator
import kotlin.math.roundToInt

/**
 * A walk or a sport session the owner entered on the watch, as the phone's "Activity v2" knows
 * it: a mode, a start, a duration, and a tail after it during which the effect fades.
 */
data class ActivityRecord(
    val startEpochMs: Long,
    val durationMinutes: Int,
    /** WALK or SPORT. */
    val mode: String,
    val tailMinutes: Int = ActivityPlanCalculator.tailMinutes(mode, durationMinutes)
) {

    /** 0.20 for a walk, 0.30 for sport: the share of the body's insulin need the activity covers. */
    val effectFraction: Double get() = ActivityPlanCalculator.effectPercent(mode) / 100.0
    val activeEndEpochMs: Long get() = startEpochMs + durationMinutes * 60_000L
    val tailEndEpochMs: Long get() = activeEndEpochMs + tailMinutes * 60_000L
}

/**
 * What an activity does to glucose and to the pump, as the phone's algorithm does it (its
 * ActivityManager for the manual activity and its forecast adjustment), so that the watch on
 * its own treats a walk the way the phone would. The numbers are the phone's: 20 and 30 per cent,
 * the hour before the start counted in full, a tail that fades linearly, and a glucose use per
 * five minutes made of an insulin equivalent and the movement itself.
 */
object ActivityEffect {

    /** The activity that matters now: the latest not yet over with its tail, and starting within six hours. */
    fun current(activities: List<ActivityRecord>, nowEpochMs: Long): ActivityRecord? = activities
        .filter { it.startEpochMs <= nowEpochMs + 6 * 60 * 60_000L && it.tailEndEpochMs >= nowEpochMs - 5 * 60_000L }
        .maxByOrNull { it.startEpochMs }

    /** One during the session, fading to nothing over the tail; nothing before and after. */
    fun phaseAt(activity: ActivityRecord, epochMs: Long): Double = when {
        epochMs < activity.startEpochMs       -> 0.0
        epochMs <= activity.activeEndEpochMs  -> 1.0
        epochMs <= activity.tailEndEpochMs && activity.tailMinutes > 0 ->
            ((activity.tailEndEpochMs - epochMs).toDouble() / (activity.tailMinutes * 60_000L)).coerceIn(0.0, 1.0)
        else                                  -> 0.0
    }

    /**
     * How much of the pump's rate the phone keeps for the session and the hour before it: its
     * "new insulin factor", never under 0.55. 0.8 for a walk, 0.7 for sport.
     */
    fun newInsulinFactor(activity: ActivityRecord, nowEpochMs: Long): Double {
        val startOffset = ((activity.startEpochMs - nowEpochMs) / 60_000.0).roundToInt()
        val phase = phaseAt(activity, nowEpochMs)
        val overlap = when {
            phase > 0.0             -> phase
            startOffset in 1..75    -> 1.0
            startOffset in 76..120  -> ((120 - startOffset).toDouble() / 45.0).coerceIn(0.0, 1.0)
            else                    -> 0.0
        }
        return (1.0 - activity.effectFraction * overlap).coerceIn(0.55, 1.0)
    }

    /** Insulin works this much better while the activity is on: 1.2 for a walk, 1.3 for sport, fading with the tail. */
    fun isfMultiplier(activity: ActivityRecord, nowEpochMs: Long): Double = 1.0 + activity.effectFraction * phaseAt(activity, nowEpochMs)

    /** Glucose the activity uses up every five minutes at full phase, mg/dL, the phone's formula and caps. */
    fun glucoseUsePer5m(activity: ActivityRecord, basalUph: Double, isfMgdlPerU: Double): Double {
        val insulinEquivalent = if (basalUph > 0.0 && isfMgdlPerU > 0.0) basalUph * isfMgdlPerU * activity.effectFraction / 12.0 else 0.0
        val sport = activity.mode == "SPORT"
        val movement = if (sport) 1.2 else 0.8
        val cap = if (sport) 5.0 else 3.5
        return (insulinEquivalent + movement).coerceIn(0.5, cap)
    }

    /**
     * A forecast, glucose now first and then every five minutes, with what the activity uses
     * taken off step by step - the phone's own adjustment of its predictions.
     */
    fun adjust(series: List<Int>, activity: ActivityRecord, nowEpochMs: Long, basalUph: Double, isfMgdlPerU: Double): List<Int> {
        val use = glucoseUsePer5m(activity, basalUph, isfMgdlPerU)
        var accumulated = 0.0
        return series.mapIndexed { index, value ->
            if (index == 0) value
            else {
                accumulated += use * phaseAt(activity, nowEpochMs + index * 300_000L)
                (value - accumulated).roundToInt()
            }
        }
    }

    fun name(mode: String): String = if (mode == "SPORT") "спорт" else "прогулка"
}
