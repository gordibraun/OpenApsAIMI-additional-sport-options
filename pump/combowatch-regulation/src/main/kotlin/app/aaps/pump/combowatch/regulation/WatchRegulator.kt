package app.aaps.pump.combowatch.regulation

import app.aaps.core.data.model.TB
import app.aaps.plugins.aps.openAPSAIMI.AIMIAdaptiveBasal
import app.aaps.plugins.aps.openAPSAIMI.basal.BasalHistoryUtils
import app.aaps.plugins.aps.openAPSAIMI.basal.BasalPlanner
import app.aaps.plugins.aps.openAPSAIMI.model.AimiSettings
import app.aaps.plugins.aps.openAPSAIMI.model.BgSnapshot
import app.aaps.plugins.aps.openAPSAIMI.model.LoopContext
import app.aaps.plugins.aps.openAPSAIMI.model.LoopProfile
import app.aaps.plugins.aps.openAPSAIMI.model.ModeState
import app.aaps.plugins.aps.openAPSAIMI.model.PumpCaps
import app.aaps.plugins.aps.openAPSAIMI.safety.GuardedBasalSelector
import app.aaps.pump.combowatch.protocol.RegulationSnapshot
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What the watch does about glucose when the phone is away.
 *
 * It only ever lowers basal, or brings it back after lowering it. It never gives a bolus and
 * never runs the pump above its own basal profile, so the most it can get wrong is to hold basal
 * back when it need not have - never to give insulin the phone did not decide on.
 *
 * It is not a second algorithm. The forecast, the choice of a basal the forecast can bear and the
 * low-glucose guards are the phone's own code; the insulin already on board and the carbohydrates
 * come from the phone's last loop run. This class only puts them together with what the watch
 * measures itself, takes the most cautious answer, and turns it into one pump command at most.
 *
 * In two things it is deliberately not the phone. Under 80 it gives no basal at all, where the
 * phone gives half back once glucose stops falling. And it does not follow every small change of
 * the answer: each command is a connection to the pump, so small steps wait for the renewal.
 */
class WatchRegulator {

    class Inputs(
        val nowEpochMs: Long,
        val readings: List<GlucoseReading>,
        /** The phone's last loop run, or null if the phone never sent one. */
        val snapshot: RegulationSnapshot?,
        /** What the pump delivers at 100 % in each hour of its day, U/h; 24 values. */
        val pumpBasalUph: List<Double>,
        val delivery: DeliveryLog,
        /** The hour of the pump's day at a moment. Passed in so that nothing here reads a clock or a time zone. */
        val hourOfDay: (Long) -> Int
    )

    sealed interface Action {

        /** Send nothing; the pump goes on with what it has. */
        data object Leave : Action

        /** Set this temporary basal. The percentage is never above 90. */
        data class SetTbr(val percent: Int, val durationMinutes: Int) : Action
    }

    enum class Rule {
        /** No sensor reading at all, or none recent enough to act on. */
        NO_GLUCOSE,

        /** The pump's basal profile is not known, so no percentage can be judged. */
        NO_BASAL_PROFILE,

        /** Glucose is under 80: basal is stopped whatever the trend and the forecast. */
        LOW_LEVEL,

        /** Glucose is falling fast enough to reach the target, or the floor, within the half hour. */
        FAST_FALL,

        /** The four-hour forecast goes under the floor unless basal is held back. */
        FORECAST_LOW,

        /** Between 80 and 100 and falling: the graded reductions of the phone's basal rules. */
        LOW_BAND,

        /** Basal was at zero and may come back, part of it first. */
        RESUME,

        /** Basal has been at zero for an hour with glucose no longer low: a minimum is restored. */
        ZERO_LIMIT,

        /** Nothing calls for less basal than the profile. */
        ALL_CLEAR
    }

    class Decision(
        val action: Action,
        val rule: Rule,
        /** The share of profile basal the rules ask for, in per cent, 0..100. */
        val wantedPercent: Int,
        /** One sentence for the journal, in the owner's language. */
        val explanation: String,
        /** Grams to eat when even no basal at all does not keep the forecast up; otherwise null. */
        val carbsHintG: Int?,
        val trend: GlucoseTrend?,
        /** Lowest and last value of the forecast with profile basal, when a forecast could be made. */
        val forecastMinMgdl: Int?,
        val forecastEndMgdl: Int?
    )

    private class Cap(val fraction: Double, val rule: Rule, val text: String)

    fun decide(inputs: Inputs): Decision {
        val now = inputs.nowEpochMs
        fun leave(rule: Rule, text: String, trend: GlucoseTrend? = null) =
            Decision(Action.Leave, rule, 100, text, null, trend, null, null)

        val trend = GlucoseTrend.from(inputs.readings, now)
            ?: return leave(Rule.NO_GLUCOSE, "нет показаний сенсора")
        val ageMinutes = (now - trend.atEpochMs) / 60_000.0
        if (ageMinutes > MAX_READING_AGE_MINUTES)
            return leave(Rule.NO_GLUCOSE, "последнее показание сенсора старше ${MAX_READING_AGE_MINUTES.toInt()} мин", trend)

        fun basalAt(epochMs: Long): Double = inputs.pumpBasalUph.getOrNull(inputs.hourOfDay(epochMs)) ?: 0.0
        val basalNow = basalAt(now)
        if (inputs.pumpBasalUph.size != 24 || basalNow <= 0.0)
            return leave(Rule.NO_BASAL_PROFILE, "базальный профиль помпы неизвестен", trend)
        // The pump takes temporary basals in steps of ten per cent.
        val step = basalNow / 10.0

        val snapshot = inputs.snapshot
        val bg = trend.mgdl
        val goal = snapshot?.targetMgdl?.takeIf { it.isFinite() && it in 70.0..200.0 } ?: DEFAULT_GOAL_MGDL
        val floorMgdl = max(FLOOR_MGDL, snapshot?.hypoThresholdMgdl?.takeIf { it.isFinite() && it <= goal } ?: 0.0)
        val fastFall = trend.known && trend.delta < 0 &&
            (min(trend.delta, trend.shortAvgDelta) <= -FAST_FALL_PER_5_MIN || (trend.fallPerHour ?: 0.0) >= FAST_FALL_PER_HOUR)

        val forecast = snapshot
            ?.takeIf { WatchForecast.usable(it, now) }
            ?.let { runCatching { WatchForecast(it, trend, inputs.delivery, ::basalAt, now) }.getOrNull() }
        val atProfile = forecast?.let { runCatching { it.series(basalNow) }.getOrNull() }
        val forecastMin = atProfile?.minOrNull()
        val forecastEnd = atProfile?.lastOrNull()

        val caps = mutableListOf<Cap>()
        var carbsHint: Int? = null
        fun n(value: Double) = value.roundToInt().toString()
        val glucoseText = "сахар ${n(bg)}" + if (trend.known) ", изменение ${signed(trend.delta)} за 5 мин" else ""

        // ---- by level ----
        // Under 80 the pump gives no basal, whatever the trend. The phone is a little more lenient
        // here: at 75 and under it gives half of basal back as soon as glucose stops falling, and
        // stops it again between 75 and 80. Followed to the letter that wakes the pump three times
        // in ten minutes on the way out of a low, so the watch waits for 80.
        // From 80 up it is the phone's own order: first what its first-stage guard says about
        // coming back from zero, and where that has nothing to say, its graded rules up to 100.
        if (bg < LOW_BAND_STOP_MGDL)
            caps += Cap(0.0, Rule.LOW_LEVEL, "$glucoseText: ниже ${n(LOW_BAND_STOP_MGDL)} — базал остановлен")
        else {
            val guard = firstStageGuard(inputs, trend, basalNow, step, goal, forecastEnd, forecast?.cobNowG ?: 0.0)
            if (guard != null) {
                val fraction = (guard.rateUph / basalNow).coerceIn(0.0, 1.0)
                if (fraction < 1.0 - 1e-6)
                    caps += Cap(fraction, Rule.RESUME, "$glucoseText: после остановки возвращается часть базала")
            } else {
                val band = when {
                    !trend.known || trend.delta >= 0 -> null
                    bg <= 90.0                       -> when {
                        trend.delta >= -2.0                                -> 0.25
                        bg > 85.0 && (forecastEnd ?: 0) > 80 && !fastFall  -> 0.20
                        else                                               -> 0.0
                    }

                    bg <= 100.0                      -> 0.5
                    else                             -> null
                }
                if (band != null) caps += Cap(band, Rule.LOW_BAND, "$glucoseText: ниже 100 и падает — базал ${percentOf(band)} %")
            }
        }

        // ---- falling fast towards the target: the phone's own half-hour projection ----
        if (fastFall) {
            val reaches = GuardedBasalSelector.select(
                maximumRate = basalNow, basalStep = step, bg = bg, delta = trend.delta, shortDelta = trend.shortAvgDelta,
                target = goal, durationMinutes = PROJECTION_MINUTES
            ) { alwaysAbove(goal) }
            if (reaches.rate == 0.0) {
                val projected = bg + min(0.0, min(trend.delta, trend.shortAvgDelta)) * PROJECTION_MINUTES / 5.0
                caps += Cap(0.0, Rule.FAST_FALL, "$glucoseText: за $PROJECTION_MINUTES мин опустится до ${n(projected)}, цель ${n(goal)} — базал остановлен")
            }
        }

        // ---- the floor: the half-hour projection and the four-hour forecast must stay above it ----
        // Above the floor, the floor itself. Already under it, the rule is that glucose must not
        // go lower still - otherwise nothing could ever be given back while it climbs out.
        val mustStayAbove = if (bg > floorMgdl) floorMgdl else bg - BELOW_FLOOR_MARGIN_MGDL
        val choice = GuardedBasalSelector.select(
            maximumRate = basalNow, basalStep = step, bg = bg, delta = trend.delta, shortDelta = trend.shortAvgDelta,
            target = mustStayAbove, durationMinutes = PROJECTION_MINUTES
        ) { rate -> if (forecast == null) alwaysAbove(mustStayAbove) else runCatching { forecast.series(rate) }.getOrDefault(emptyList()) }
        val floorFraction = (choice.rate / basalNow).coerceIn(0.0, 1.0)
        if (floorFraction < 1.0 - 1e-6) {
            caps += if (choice.reason == "trend reaches target")
                Cap(0.0, Rule.FAST_FALL, "$glucoseText: за $PROJECTION_MINUTES мин опустится до ${n(mustStayAbove)} — базал остановлен")
            else {
                val zeroBasal = forecast?.let { runCatching { it.series(0.0) }.getOrNull() }
                if (choice.reason == "risk remains without basal" && zeroBasal != null)
                    carbsHint = carbsFor(zeroBasal, floorMgdl, checkNotNull(snapshot))
                Cap(
                    floorFraction, Rule.FORECAST_LOW,
                    "$glucoseText: прогноз опускается до ${forecastMin ?: "?"}, ниже ${n(mustStayAbove)} — базал ${percentOf(floorFraction)} %"
                )
            }
        }

        var wanted = caps.minByOrNull { it.fraction }
        // A pump held at zero for an hour while glucose is no longer low and no longer falling is
        // more likely a forecast gone stale than a need: the phone, too, gives up on zero by then.
        val zeroMinutes = inputs.delivery.zeroMinutesUpTo(now)
        if (wanted != null && wanted.fraction < ZERO_LIMIT_FRACTION && zeroMinutes >= MAX_ZERO_MINUTES &&
            bg > floorMgdl + ZERO_LIMIT_MARGIN_MGDL && trend.known && trend.delta >= 0
        ) wanted = Cap(ZERO_LIMIT_FRACTION, Rule.ZERO_LIMIT, "$glucoseText: базал остановлен уже $zeroMinutes мин, сахар не низкий и не падает — возвращается половина базала")

        val wantedPercent = wanted?.let { percentOf(it.fraction) } ?: 100
        val rule = wanted?.rule ?: Rule.ALL_CLEAR
        val text = wanted?.text ?: "$glucoseText: снижать базал не нужно"

        val running = inputs.delivery.tbrAt(now)
        val runningPercent = running?.percent ?: 100
        val remainingMinutes = running?.let { (it.endEpochMs - now) / 60_000.0 } ?: 0.0
        // What runs now ends before the next reading or two: whatever is wanted has to be set now.
        val runsOut = running != null && remainingMinutes < RENEW_BEFORE_MINUTES
        val set = Action.SetTbr(wantedPercent, if (rule == Rule.RESUME || rule == Rule.ZERO_LIMIT) RESUME_MINUTES else REDUCTION_MINUTES)

        // Every command is a connection to the pump, so one is sent only when it changes something
        // that matters. Stopping basal always does. Trading sixty per cent for fifty does not.
        val action: Action = when {
            wantedPercent >= 100                                              ->
                // Nothing is to be held back. A reduction still running is left to run out. Only a
                // temporary basal above profile that the phone left behind, and that the forecast
                // does not bear to its end, is brought down to just under profile.
                if (runningPercent > 100 && forecast != null &&
                    (runCatching { forecast.series(basalNow * runningPercent / 100.0, ceil(remainingMinutes).toInt().coerceAtLeast(5)) }
                        .getOrNull()?.minOrNull() ?: Int.MAX_VALUE) < floorMgdl
                ) Action.SetTbr(NEAR_PROFILE_PERCENT, RESUME_MINUTES) else Action.Leave

            // A tenth of basal less for half an hour is a few hundredths of a unit. With the pump
            // at profile, or about to be, that is not worth a connection either.
            wantedPercent >= NEAR_PROFILE_PERCENT && (runningPercent >= 100 || runsOut) -> Action.Leave

            // Less than what runs now. At once if it is a stop, if nothing is held back yet, or if
            // it is a real step down; a small step waits until what runs now is renewed.
            wantedPercent < runningPercent                                    ->
                if (wantedPercent == 0 || runningPercent >= 100 || runningPercent - wantedPercent >= MIN_LOWER_PERCENT || runsOut) set
                else Action.Leave

            // The same as what runs now: renewed shortly before it runs out.
            wantedPercent == runningPercent                                   -> if (runsOut) set else Action.Leave

            // More than what runs now. If that is about to run out, what is wanted is set in its
            // place - left alone, the pump would go to full basal, which is more. Otherwise only a
            // temporary basal the watch set itself is raised, never one the phone left behind, only
            // by a real step, and only after the pump has been where it is for a quarter of an hour.
            else                                                              ->
                if (runsOut || (running?.byWatch == true && wantedPercent - runningPercent >= MIN_RAISE_PERCENT &&
                        inputs.delivery.heldMinutesUpTo(now) >= MIN_MINUTES_BEFORE_RAISE)
                ) set else Action.Leave
        }

        return Decision(action, rule, wantedPercent, text, carbsHint, trend, forecastMin, forecastEnd)
    }

    /** The phone's first-stage basal guard, run on the watch's own glucose. */
    private fun firstStageGuard(
        inputs: Inputs, trend: GlucoseTrend, basalNow: Double, step: Double, goal: Double, forecastEnd: Int?, cobG: Double
    ) = synchronized(BasalHistoryUtils) {
        BasalHistoryUtils.historyProvider = BasalHistoryUtils.FetcherProvider(
            fetcher = { from ->
                inputs.delivery.tbrs
                    .filter { it.endEpochMs >= from && it.startEpochMs <= inputs.nowEpochMs && it.endEpochMs > it.startEpochMs }
                    .sortedByDescending { it.startEpochMs }
                    .map { TB(timestamp = it.startEpochMs, type = TB.Type.NORMAL, isAbsolute = false, rate = it.percent.toDouble(), duration = it.endEpochMs - it.startEpochMs) }
            },
            nowProvider = { inputs.nowEpochMs }
        )
        try {
            BasalPlanner(AIMIAdaptiveBasal(QuietLogger, PlainDecimals), QuietLogger).plan(
                LoopContext(
                    bg = BgSnapshot(
                        mgdl = trend.mgdl, delta5 = trend.delta, shortAvgDelta = trend.shortAvgDelta,
                        longAvgDelta = trend.longAvgDelta, epochMillis = trend.atEpochMs
                    ),
                    iobU = inputs.snapshot?.iobU ?: 0.0,
                    cobG = cobG,
                    profile = LoopProfile(targetMgdl = goal, isfMgdlPerU = inputs.snapshot?.sensitivityMgdlPerU ?: 0.0, basalProfileUph = basalNow),
                    // Nothing above the pump's own profile: the planner's small boosts are cut off here.
                    pump = PumpCaps(basalStep = step, bolusStep = 0.1, minDurationMin = RESUME_MINUTES, maxBasal = basalNow, maxSmb = 0.0),
                    modes = ModeState(),
                    settings = AimiSettings(smbIntervalMin = 5, wCycleEnabled = false),
                    tdd24hU = 0.0,
                    eventualBg = forecastEnd?.toDouble() ?: trend.mgdl,
                    nowEpochMillis = inputs.nowEpochMs
                )
            )
        } finally {
            BasalHistoryUtils.historyProvider = BasalHistoryUtils.EmptyProvider
        }
    }

    /** Grams that would lift the lowest point of the no-basal forecast to the floor; null if that is not soon or not much. */
    private fun carbsFor(zeroBasal: List<Int>, floorMgdl: Double, snapshot: RegulationSnapshot): Int? {
        val soon = zeroBasal.take(CARBS_HINT_WITHIN_STEPS + 1)
        val lowest = soon.minOrNull() ?: return null
        val missing = floorMgdl - lowest
        if (missing < CARBS_HINT_MIN_MGDL) return null
        val mgdlPerGram = snapshot.sensitivityMgdlPerU / snapshot.carbRatioGPerU
        if (!mgdlPerGram.isFinite() || mgdlPerGram <= 0.0) return null
        return (ceil(missing / mgdlPerGram / 5.0) * 5.0).toInt().coerceIn(5, CARBS_HINT_MAX_G)
    }

    private fun alwaysAbove(level: Double): List<Int> = List(WatchForecast.STEPS + 1) { ceil(level).toInt() + 1 }

    /** A share of profile basal as a percentage the pump can take: tens, rounded down. */
    private fun percentOf(fraction: Double): Int = (floor(fraction * 10.0 + 1e-9).toInt() * 10).coerceIn(0, 100)

    private fun signed(value: Double): String = (if (value > 0) "+" else "") + String.format(java.util.Locale.US, "%.0f", value)

    companion object {

        /** A reading older than this is the past, not the present; the cycle is five minutes. */
        const val MAX_READING_AGE_MINUTES = 7.0

        /**
         * The level the forecast must not go under, and at which basal stops outright. It is the
         * phone's own number: its basal rules stop basal under 80 and cut it on a forecast under 80.
         * A higher low-glucose threshold set on the phone takes its place.
         */
        const val FLOOR_MGDL = 80.0

        /** Under this the phone's basal rules stop basal whatever the trend. */
        const val LOW_BAND_STOP_MGDL = 80.0

        /** Once under the floor, how much further down the forecast may still go before basal is held back. */
        const val BELOW_FLOOR_MARGIN_MGDL = 5.0

        /** Used only if the phone never said what the target is. */
        const val DEFAULT_GOAL_MGDL = 100.0

        /** "Falling fast" as the phone counts it: 65 mg/dL an hour, or as much in the last minutes. */
        const val FAST_FALL_PER_HOUR = 65.0
        const val FAST_FALL_PER_5_MIN = FAST_FALL_PER_HOUR / 12.0

        /** How far ahead the fall is carried, and how long a reduction is set for. */
        const val PROJECTION_MINUTES = 30
        const val REDUCTION_MINUTES = 30

        /** The pump's shortest temporary basal; used for bringing basal back in steps. */
        const val RESUME_MINUTES = 15

        /** A temporary basal with less than this left is renewed, or replaced, at this reading. */
        const val RENEW_BEFORE_MINUTES = 10.0

        /** The least step down, short of a stop, that is worth a connection to the pump before the renewal. */
        const val MIN_LOWER_PERCENT = 20

        /**
         * The least step up that is, and how long the pump must have been where it is before it:
         * three readings. The forecast moves with every reading, and what it gives back at one it
         * may take away at the next; giving back can wait, taking away cannot.
         */
        const val MIN_RAISE_PERCENT = 30
        const val MIN_MINUTES_BEFORE_RAISE = 15

        /** The pump cannot be told "100 %"; this is how a temporary basal above profile is ended. */
        const val NEAR_PROFILE_PERCENT = 90

        /** The phone's own limit on an uninterrupted stop of basal. */
        const val MAX_ZERO_MINUTES = 60
        const val ZERO_LIMIT_FRACTION = 0.5
        const val ZERO_LIMIT_MARGIN_MGDL = 10.0

        const val CARBS_HINT_WITHIN_STEPS = 18
        const val CARBS_HINT_MIN_MGDL = 5.0
        const val CARBS_HINT_MAX_G = 40
    }
}
