package app.aaps.pump.combowatch.regulation

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionEngine
import app.aaps.plugins.aps.openAPSAIMI.pkpd.CarbAbsorptionModel
import app.aaps.plugins.aps.openAPSAIMI.pkpd.ForecastCarbImpact
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PlannedInsulinAction
import app.aaps.pump.combowatch.protocol.RegulationSnapshot
import kotlin.math.roundToInt

/**
 * The phone's forecast, continued on the watch.
 *
 * The formula is the phone's own (the shared prediction engine). What goes into it:
 *
 * - glucose and its rate of change, from the watch's sensor readings;
 * - how the insulin given before the snapshot will act, from the snapshot;
 * - what the pump delivered since the snapshot differently from what the snapshot assumed -
 *   boluses, and every minute of basal above or below the assumed rate - from the watch's own
 *   record of the pump, turned into an effect by the insulin curve the phone sent;
 * - what is left of the carbohydrates, from the snapshot, absorbed further by the phone's model
 *   for the time that has passed;
 * - how much better glucose is doing right now than the insulin at work explains, which the
 *   phone's forecast carries on for a while as food still arriving. It is worked out here by the
 *   phone's own function from the watch's readings.
 *
 * What it cannot know is anything that happened without the pump or the sensor: food eaten and
 * not entered, insulin from a pen. The phone guesses at food nobody entered from more than the
 * sensor - the time of day, its meal modes, the last hour - and lets a rise count for more when
 * it is confident. The watch has none of that and takes the cautious end of that scale: a rise
 * counts for what the sensor shows of it and no more.
 */
internal class WatchForecast(
    private val snapshot: RegulationSnapshot,
    private val trend: GlucoseTrend,
    private val delivery: DeliveryLog,
    /** What the pump delivers at 100 % at a given moment, U/h. */
    private val pumpBasalUphAt: (Long) -> Double,
    private val nowEpochMs: Long
) {

    private val insulin = SampledInsulin(snapshot.insulinRemaining)
    private val action = PlannedInsulinAction.from(insulin, diaHours = insulin.dia, horizonMinutes = insulin.horizonMinutes)

    /** How many five-minute steps of effect the insulin curve can be asked for at once. */
    private val reach = (snapshot.insulinRemaining.size - 1) / 2

    private val minutesSinceSnapshot = (nowEpochMs - snapshot.madeAtEpochMs) / 60_000.0

    /** What the snapshot's insulin curve took for granted the pump would deliver at a moment, U/h. */
    private fun assumedRateUphAt(epochMs: Long): Double =
        snapshot.assumedTbr?.takeIf { epochMs < it.endsAtEpochMs }?.rateUph ?: pumpBasalUphAt(epochMs)

    private fun deliveredRateUphAt(epochMs: Long): Double = pumpBasalUphAt(epochMs) * delivery.percentAt(epochMs) / 100.0

    /**
     * Units of insulin action in each coming five-minute step that the snapshot's curve does not
     * contain: negative where the pump gave less than the snapshot assumed, positive where more.
     */
    private val unaccounted: DoubleArray = DoubleArray(STEPS).also { total ->
        fun add(effects: DoubleArray, startedStepsAgo: Int) {
            for (step in 0 until STEPS) total[step] += effects.getOrElse(startedStepsAgo + step) { 0.0 }
        }

        // Boluses the snapshot was made too early to know about. A bolus from the minutes just
        // before it may be counted twice, because the pump's clock and the phone's differ by up
        // to a couple of minutes; counting it twice errs toward less basal, missing it would not.
        for (bolus in delivery.boluses) {
            if (bolus.atEpochMs <= snapshot.madeAtEpochMs - CLOCK_TOLERANCE_MS || bolus.atEpochMs > nowEpochMs) continue
            val stepsAgo = ((nowEpochMs - bolus.atEpochMs) / 300_000.0).roundToInt().coerceAtLeast(0)
            if (stepsAgo + STEPS > reach) continue
            add(action.effectsPer5Minutes(bolus.units, 0.0, 0, stepsAgo + STEPS), stepsAgo)
        }

        // Basal since the snapshot, five minutes at a time: what was delivered against what the
        // snapshot assumed would be.
        var sliceStart = snapshot.madeAtEpochMs
        while (sliceStart < nowEpochMs) {
            val sliceEnd = minOf(sliceStart + 300_000L, nowEpochMs)
            val middle = (sliceStart + sliceEnd) / 2
            val differenceUph = deliveredRateUphAt(middle) - assumedRateUphAt(middle)
            val minutes = ((sliceEnd - sliceStart) / 60_000.0).roundToInt()
            val stepsAgo = ((nowEpochMs - sliceStart) / 300_000.0).roundToInt()
            if (kotlin.math.abs(differenceUph) > 1e-6 && minutes > 0 && stepsAgo + STEPS <= reach)
                add(action.effectsPer5Minutes(0.0, differenceUph, minutes, stepsAgo + STEPS), stepsAgo)
            sliceStart = sliceEnd
        }

        // A candidate rate replaces whatever runs now, so the rest of the temporary basal the
        // snapshot counted on will not happen. The engine below adds the candidate relative to
        // profile basal; this takes the assumed remainder out.
        snapshot.assumedTbr?.takeIf { it.endsAtEpochMs > nowEpochMs }?.let { assumed ->
            val minutes = ((assumed.endsAtEpochMs - nowEpochMs) / 60_000.0).roundToInt()
            val differenceUph = pumpBasalUphAt(nowEpochMs) - assumed.rateUph
            if (minutes > 0 && kotlin.math.abs(differenceUph) > 1e-6)
                add(action.effectsPer5Minutes(0.0, differenceUph, minutes, STEPS), 0)
        }
    }

    /** Insulin activity from the snapshot at a moment, units per minute; nothing is left past its end. */
    private fun snapshotActivityAt(epochMs: Long): Double {
        val position = (epochMs - snapshot.madeAtEpochMs) / 300_000.0
        val points = snapshot.insulinActivity
        if (position < 0 || points.isEmpty() || position > points.lastIndex) return 0.0
        val lower = position.toInt()
        if (lower == points.lastIndex) return points[lower]
        return points[lower] + (points[lower + 1] - points[lower]) * (position - lower)
    }

    /**
     * Carbohydrates still to be absorbed now: the snapshot's amount, absorbed on by the phone's
     * model, plus what the owner entered on the watch after the snapshot was made - the phone
     * knows nothing of those until it is back, and a snapshot made after them already counts them.
     */
    val cobNowG: Double = snapshot.cobG.coerceAtLeast(0.0) *
        CarbAbsorptionModel.remainingFraction(elapsedMinutes = minutesSinceSnapshot, selectedFoodType = null, delta = trend.delta) +
        delivery.carbs
            .filter { it.atEpochMs > snapshot.madeAtEpochMs - CLOCK_TOLERANCE_MS && it.atEpochMs <= nowEpochMs }
            .sumOf { it.grams * CarbAbsorptionModel.remainingFraction((nowEpochMs - it.atEpochMs) / 60_000.0, selectedFoodType = null, delta = trend.delta) }

    /** What the insulin at work is doing to glucose right now, units per minute, net of profile basal. */
    private val activityNow: Double = snapshotActivityAt(nowEpochMs) + unaccounted[0] / 5.0

    /** Glucose rising faster, or falling slower, than that insulin explains; nothing while the trend is not known. */
    private val carbImpact: ForecastCarbImpact? = if (!trend.known) null else ForecastCarbImpact.calculate(
        minDelta = minOf(trend.delta, trend.shortAvgDelta),
        insulinActivity = activityNow,
        decisionIsf = snapshot.sensitivityMgdlPerU,
        csf = snapshot.sensitivityMgdlPerU / snapshot.carbRatioGPerU,
        cobG = cobNowG,
        sensitivityRatio = 1.0,
        remainingCarbsCap = REMAINING_CARBS_CAP_G
    )

    /** The walk or sport session that matters now, if the owner entered one on the watch. */
    private val activity: ActivityRecord? = ActivityEffect.current(delivery.activities, nowEpochMs)

    /** Insulin works better during an activity; the phone multiplies its sensitivity the same way. */
    private val sensitivity: Double = snapshot.sensitivityMgdlPerU * (activity?.let { ActivityEffect.isfMultiplier(it, nowEpochMs) } ?: 1.0)

    private val profile = profileForForecast(snapshot.carbRatioGPerU, sensitivity, snapshot.targetMgdl)

    /**
     * Glucose every five minutes for the next four hours if the pump runs at [rateUph] for
     * [minutes] and at profile basal afterwards. The first value is glucose now.
     */
    fun series(rateUph: Double, minutes: Int = 30): List<Int> {
        // The engine reads, for the step ending k * 5 minutes from now, the entry at that time.
        val entries = Array(STEPS + 1) { index ->
            val at = nowEpochMs + index * 300_000L
            val extra = if (index == 0) 0.0 else unaccounted[index - 1] / 5.0
            IobTotal(time = at, activity = snapshotActivityAt(at) + extra)
        }
        val basalNow = pumpBasalUphAt(nowEpochMs)
        val predicted = AdvancedPredictionEngine.predict(
            currentBG = trend.mgdl,
            iobArray = entries,
            finalSensitivity = sensitivity,
            cobG = cobNowG,
            profile = profile,
            delta = trend.delta,
            plannedSmbU = 0.0,
            plannedRateUph = rateUph,
            profileBasalUph = basalNow,
            plannedDurationMin = minutes,
            observedCarbImpactMgdlPer5m = carbImpact?.observedMgdlPer5m ?: 0.0,
            remainingCiPeakMgdlPer5m = carbImpact?.remainingPeakMgdlPer5m ?: 0.0,
            // No confidence in food nobody entered beyond what the sensor shows; see above.
            uamConfidence = 0.0,
            targetBG = snapshot.targetMgdl,
            plannedInsulinAction = action
        ).map { it.roundToInt() }
        // What the activity uses comes off afterwards, as the phone takes it off its predictions.
        return activity?.let { ActivityEffect.adjust(predicted, it, nowEpochMs, basalNow, snapshot.sensitivityMgdlPerU) } ?: predicted
    }

    /**
     * Insulin on board now, in units: what is left of the insulin the snapshot knew of, plus what
     * the pump has given since, net of what the snapshot assumed - the forecast's own corrections,
     * summed instead of spread over the coming hours. For the face; nothing is decided on it.
     */
    fun iobNowU(): Double {
        var left = 0.0
        val end = snapshot.madeAtEpochMs + snapshot.insulinActivity.lastIndex * 300_000L
        var at = nowEpochMs
        while (at <= end) {
            left += snapshotActivityAt(at) * 5.0
            at += 300_000L
        }
        return left + unaccounted.sum()
    }

    companion object {

        /** Four hours in five-minute steps, the horizon of the phone's forecast. */
        const val STEPS = 48

        /** The algorithm's own limit on carbohydrates it assumes are still to come. */
        const val REMAINING_CARBS_CAP_G = 90

        /** How far the pump's clock and the phone's are allowed to differ. */
        const val CLOCK_TOLERANCE_MS = 3 * 60_000L

        /** Past this age the snapshot's insulin curve has run out and no longer supports a forecast. */
        const val MAX_SNAPSHOT_AGE_MS = 4 * 60 * 60_000L

        fun usable(snapshot: RegulationSnapshot, nowEpochMs: Long): Boolean =
            nowEpochMs >= snapshot.madeAtEpochMs - CLOCK_TOLERANCE_MS &&
                nowEpochMs - snapshot.madeAtEpochMs <= MAX_SNAPSHOT_AGE_MS &&
                snapshot.sensitivityMgdlPerU.isFinite() && snapshot.sensitivityMgdlPerU > 0.0 &&
                snapshot.carbRatioGPerU.isFinite() && snapshot.carbRatioGPerU > 0.0 &&
                snapshot.insulinActivity.isNotEmpty() && snapshot.insulinActivity.all { it.isFinite() } &&
                snapshot.insulinRemaining.size >= SampledInsulin.MIN_SAMPLES &&
                // Enough of the curve to place what the pump did since the snapshot.
                (snapshot.insulinRemaining.size - 1) / 2 >= 2 * STEPS
    }
}
