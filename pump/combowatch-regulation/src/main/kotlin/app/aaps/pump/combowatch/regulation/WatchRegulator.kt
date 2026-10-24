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
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What the watch does about glucose when the phone is away.
 *
 * Mostly it lowers basal, or brings it back after lowering it. Since 23 Oct 2026 it also adds
 * insulin when nothing holds basal back and the forecast runs above the target: the phone's own
 * oref arithmetic on the watch's forecast, under hard caps of the watch's own ([MAX_SMB_U],
 * [MAX_SMB_PER_HOUR_U], [MAX_RAISE_PERCENT], [MAX_IOB_ALONE_U]) - because a meal with the phone
 * away used to run to 200 with the watch able to do nothing about it (22 Oct 2026).
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

    /** What the watch may add on its own under the owner's resistance factor; see [resistantCaps]. */
    class ResistantCaps(val maxIobU: Double, val maxSmbU: Double, val maxSmbPerHourU: Double, val maxRaisePercent: Int)

    class Inputs(
        val nowEpochMs: Long,
        val readings: List<GlucoseReading>,
        /** The phone's last loop run, or null if the phone never sent one. */
        val snapshot: RegulationSnapshot?,
        /** What the pump delivers at 100 % in each hour of its day, U/h; 24 values. */
        val pumpBasalUph: List<Double>,
        val delivery: DeliveryLog,
        /** The hour of the pump's day at a moment. Passed in so that nothing here reads a clock or a time zone. */
        val hourOfDay: (Long) -> Int,
        /** Units of microbolus the watch gave itself in the last hour, for its hourly cap. */
        val smbGivenLastHourU: Double = 0.0,
        /**
         * The owner's "insulin works worse today" factor, 1.0 normally, up to [MAX_RESISTANCE_FACTOR]:
         * the snapshot's sensitivity and carb ratio are expected to have been divided by it already
         * (more insulin per mg/dL and per gram); here it widens the caps on what the watch adds.
         */
        val resistanceFactor: Double = 1.0
    )

    sealed interface Action {

        /** Send nothing; the pump goes on with what it has. */
        data object Leave : Action

        /** Set this temporary basal: 0..90 to hold basal back, 110..[MAX_RAISE_PERCENT] to add to it. */
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

        /** A walk or a sport session the owner entered: basal is kept down for it and the hour before. */
        ACTIVITY,

        /** Nothing holds basal back and the forecast runs above the target: insulin is added, as the phone would add it. */
        CORRECTION,

        /** Nothing calls for less basal than the profile. */
        ALL_CLEAR
    }

    class Decision(
        val action: Action,
        val rule: Rule,
        /** The share of profile basal the rules ask for, in per cent, 0..[MAX_RAISE_PERCENT]. */
        val wantedPercent: Int,
        /** One sentence for the journal, in the owner's language. */
        val explanation: String,
        /** Grams to eat when even no basal at all does not keep the forecast up; otherwise null. */
        val carbsHintG: Int?,
        val trend: GlucoseTrend?,
        /** Lowest and last value of the forecast with profile basal, when a forecast could be made. */
        val forecastMinMgdl: Int?,
        val forecastEndMgdl: Int?,
        /** That forecast itself, mg/dL every five minutes from now; for looking at, not for deciding. */
        val forecastMgdl: List<Int>? = null,
        /**
         * The forecast with the temporary basal that runs now left to run to its end - the one to
         * hold against the phone's own forecast, which counts the basal it has just set. Null when
         * no temporary basal runs: then it is [forecastMgdl].
         */
        val forecastAsRunningMgdl: List<Int>? = null,
        /**
         * Why the pump is left as it is although the rules want less basal, in the owner's language.
         * Null when something is set, and when nothing is wanted in the first place.
         */
        val holdText: String? = null,
        /** Insulin on board now, units, by the watch's forecast model; null without a usable snapshot. For the face. */
        val iobNowU: Double? = null,
        /**
         * Grams of carbohydrate that would lift the lowest point of the forecast, with what runs
         * now, back up to the target; null when the forecast stays at or above it. For the face.
         */
        val carbsToTargetG: Int? = null,
        /** Tenths of a unit of microbolus to give now; zero when none. */
        val smbTenthsIU: Int = 0,
        /** The phone's "insulin required" on the watch's forecast, units; null when the rules asked for less basal instead. */
        val insulinReqU: Double? = null
    )

    /**
     * A meal bolus worked out on the watch, for carbohydrates the owner is about to eat with the
     * phone away: the phone's own wizard arithmetic - carbohydrates over the ratio, the distance to
     * the target over the sensitivity, carbohydrates still on board over the ratio, less the
     * insulin on board - on the watch's own numbers. Nothing is guessed: without the phone's
     * snapshot or without a fresh reading there is no advice, and the owner is told why.
     */
    class BolusAdvice(
        val carbsG: Int,
        val carbsU: Double,
        val bgMgdl: Double?,
        val correctionU: Double,
        val cobG: Double,
        val cobU: Double,
        val iobU: Double,
        /** The dose before rounding and the limit; may be negative. */
        val rawU: Double,
        /** Tenths of a unit to give; zero when nothing is needed. */
        val tenthsIU: Int,
        /** The owner's limit, when it cut the dose. */
        val limitedToTenths: Int?,
        val lines: List<String>,
        /** Why there is no advice; null when [tenthsIU] stands. */
        val refusal: String? = null
    )

    fun adviseBolus(inputs: Inputs, carbsG: Int, maxTenthsIU: Int): BolusAdvice {
        val now = inputs.nowEpochMs
        fun none(why: String) = BolusAdvice(carbsG, 0.0, null, 0.0, 0.0, 0.0, 0.0, 0.0, 0, null, emptyList(), why)
        val snapshot = inputs.snapshot ?: return none("телефон не оставил данных для расчёта")
        // The snapshot serves a day, as it does the watch's basal: after its insulin curve has run
        // out, insulin on board is what the watch itself has seen the pump give since.
        if (now - snapshot.madeAtEpochMs > BOLUS_MAX_SNAPSHOT_AGE_MS) return none("данным телефона больше суток: считать не из чего")
        if (now < snapshot.madeAtEpochMs - WatchForecast.CLOCK_TOLERANCE_MS) return none("данные телефона из будущего: часы не в курсе времени")
        if (snapshot.carbRatioGPerU <= 0.0 || snapshot.sensitivityMgdlPerU <= 0.0) return none("в данных телефона нет коэффициентов")
        // Without a recent reading the meal is still bolused for - as the phone's wizard does it
        // without glucose - only with no correction towards the target, and said so.
        val fresh = GlucoseTrend.from(inputs.readings, now)?.takeIf { (now - it.atEpochMs) / 60_000.0 <= BOLUS_MAX_READING_AGE_MINUTES }
        val trend = fresh ?: GlucoseTrend(snapshot.targetMgdl, now, 0.0, 0.0, 0.0, known = false, fallPerHour = null)
        fun basalAt(epochMs: Long): Double = inputs.pumpBasalUph.getOrNull(inputs.hourOfDay(epochMs)) ?: 0.0
        val forecast = runCatching { WatchForecast(snapshot, trend, inputs.delivery, ::basalAt, now) }.getOrNull()
            ?: return none("прогноз не построился")
        val iob = forecast.iobNowU()
        val cob = forecast.cobNowG
        val carbsU = carbsG / snapshot.carbRatioGPerU
        val correctionU = if (fresh != null) (fresh.mgdl - snapshot.targetMgdl) / snapshot.sensitivityMgdlPerU else 0.0
        val cobU = cob / snapshot.carbRatioGPerU
        val raw = carbsU + correctionU + cobU - iob
        val wanted = floor(raw * 10.0 + 1e-9).toInt().coerceAtLeast(0)
        val limited = wanted > maxTenthsIU
        val tenths = minOf(wanted, maxTenthsIU)
        fun u(value: Double) = String.format(java.util.Locale.getDefault(), "%.1f", value)
        val lines = mutableListOf("Углеводы $carbsG г ÷ ${u(snapshot.carbRatioGPerU)} г/ЕД = ${u(carbsU)} ЕД")
        // The owner said insulin works worse today: the ratio above is already the strengthened one.
        if (inputs.resistanceFactor > 1.0 + 1e-9) lines += "Режим «инсулин работает хуже» +${((inputs.resistanceFactor - 1.0) * 100).roundToInt()} %: коэффициенты усилены"
        lines += if (fresh != null)
            "Сахар ${fresh.mgdl.roundToInt()}, цель ${snapshot.targetMgdl.roundToInt()}, ISF ${snapshot.sensitivityMgdlPerU.roundToInt()}: ${if (correctionU >= 0) "+" else "−"}${u(abs(correctionU))} ЕД"
        else "Сахар неизвестен (показаний за ${BOLUS_MAX_READING_AGE_MINUTES.toInt()} мин нет): поправка к цели не считалась"
        if (cob >= 1.0) lines += "Углеводы в работе ${cob.roundToInt()} г: +${u(cobU)} ЕД"
        lines += "Активный инсулин ${u(iob)} ЕД: ${if (iob >= 0) "−" else "+"}${u(abs(iob))} ЕД"
        lines += if (tenths == 0) "Итого: инсулин не нужен (${u(raw)} ЕД)" else "Итого ${u(tenths / 10.0)} ЕД" + if (limited) " (предел телефона ${u(maxTenthsIU / 10.0)} ЕД, расчёт ${u(raw)})" else ""
        return BolusAdvice(carbsG, carbsU, fresh?.mgdl, correctionU, cob, cobU, iob, raw, tenths, if (limited) maxTenthsIU else null, lines)
    }

    /**
     * A dose the owner typed in on the watch with the phone away. No arithmetic of the watch's own
     * goes into it: only the owner's limit, and, for the owner to read before pressing, what the
     * watch knows of glucose and of the insulin already at work.
     */
    fun adviseManualBolus(inputs: Inputs, tenthsIU: Int, maxTenthsIU: Int): BolusAdvice {
        val now = inputs.nowEpochMs
        fun u(value: Double) = String.format(java.util.Locale.getDefault(), "%.1f", value)
        if (tenthsIU <= 0) return BolusAdvice(0, 0.0, null, 0.0, 0.0, 0.0, 0.0, 0.0, 0, null, emptyList(), "доза не указана")
        if (maxTenthsIU <= 0) return BolusAdvice(0, 0.0, null, 0.0, 0.0, 0.0, 0.0, 0.0, 0, null, emptyList(), "предел болюса не задан")
        val limited = tenthsIU > maxTenthsIU
        val tenths = minOf(tenthsIU, maxTenthsIU)
        val lines = mutableListOf("Доза с часов: ${u(tenthsIU / 10.0)} ЕД")
        val fresh = GlucoseTrend.from(inputs.readings, now)?.takeIf { (now - it.atEpochMs) / 60_000.0 <= BOLUS_MAX_READING_AGE_MINUTES }
        lines += if (fresh != null) "Сахар ${fresh.mgdl.roundToInt()}" + (if (fresh.known) ", за 5 мин ${if (fresh.delta >= 0) "+" else "−"}${abs(fresh.delta).roundToInt()}" else "")
        else "Сахар неизвестен (показаний за ${BOLUS_MAX_READING_AGE_MINUTES.toInt()} мин нет)"
        var iob = 0.0
        var cob = 0.0
        val snapshot = inputs.snapshot?.takeIf { now - it.madeAtEpochMs <= BOLUS_MAX_SNAPSHOT_AGE_MS && now >= it.madeAtEpochMs - WatchForecast.CLOCK_TOLERANCE_MS }
        if (snapshot != null) {
            fun basalAt(epochMs: Long): Double = inputs.pumpBasalUph.getOrNull(inputs.hourOfDay(epochMs)) ?: 0.0
            val trend = fresh ?: GlucoseTrend(snapshot.targetMgdl, now, 0.0, 0.0, 0.0, known = false, fallPerHour = null)
            val forecast = runCatching { WatchForecast(snapshot, trend, inputs.delivery, ::basalAt, now) }.getOrNull()
            if (forecast != null) {
                iob = forecast.iobNowU()
                cob = forecast.cobNowG
                lines += "Активный инсулин ${u(iob)} ЕД" + if (cob >= 1.0) ", углеводы в работе ${cob.roundToInt()} г" else ""
            }
        } else lines += "Активный инсулин неизвестен: телефон не оставил данных"
        lines += if (limited) "Итого ${u(tenths / 10.0)} ЕД (предел ${u(maxTenthsIU / 10.0)} ЕД, запрошено ${u(tenthsIU / 10.0)})" else "Итого ${u(tenths / 10.0)} ЕД"
        return BolusAdvice(0, 0.0, fresh?.mgdl, 0.0, cob, 0.0, iob, tenthsIU / 10.0, tenths, if (limited) maxTenthsIU else null, lines)
    }

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

        // ---- a walk or a sport session the owner entered on the watch ----
        // The phone keeps the pump at 80 % for a walk and 70 % for sport, from an hour before the
        // start to the end, fading over the tail; the forecast above already counts the glucose
        // the activity uses. The same cap here, so that the watch alone treats it the same way.
        ActivityEffect.current(inputs.delivery.activities, now)?.let { activity ->
            val factor = ActivityEffect.newInsulinFactor(activity, now)
            if (factor < 1.0 - 1e-3) {
                val startsIn = ((activity.startEpochMs - now) / 60_000.0).roundToInt()
                val phase = if (startsIn > 0) "через $startsIn мин" else "идёт"
                caps += Cap(factor, Rule.ACTIVITY, "$glucoseText: нагрузка (${ActivityEffect.name(activity.mode)} ${activity.durationMinutes} мин, $phase) — базал не выше ${percentOf(factor)} %")
            }
        }

        var wanted = caps.minByOrNull { it.fraction }
        // A pump held at zero for an hour while glucose is no longer low and no longer falling is
        // more likely a forecast gone stale than a need: the phone, too, gives up on zero by then.
        val zeroMinutes = inputs.delivery.zeroMinutesUpTo(now)
        if (wanted != null && wanted.fraction < ZERO_LIMIT_FRACTION && zeroMinutes >= MAX_ZERO_MINUTES &&
            bg > floorMgdl + ZERO_LIMIT_MARGIN_MGDL && trend.known && trend.delta >= 0
        ) wanted = Cap(ZERO_LIMIT_FRACTION, Rule.ZERO_LIMIT, "$glucoseText: базал остановлен уже $zeroMinutes мин, сахар не низкий и не падает — возвращается половина базала")

        var wantedPercent = wanted?.let { percentOf(it.fraction) } ?: 100
        var rule = wanted?.rule ?: Rule.ALL_CLEAR
        var text = wanted?.text ?: "$glucoseText: снижать базал не нужно"

        // ---- more insulin than profile: the phone's own arithmetic on the watch's forecast ----
        // Only when nothing above asks for less basal. The phone's "insulin required" (oref) is the
        // distance from the target of the lowest point of the forecast after the insulin peak - or
        // of its end, whichever is lower - over the sensitivity, and never more than the insulin
        // cap leaves room for. Half of it goes as a microbolus, the rest as basal above profile
        // for half an hour, both under the watch's own caps, and only if the forecast with that
        // plan still keeps above the floor; otherwise first the microbolus goes, then the raise.
        var smbTenths = 0
        var insulinReq: Double? = null
        fun u1(value: Double) = String.format(java.util.Locale.US, "%.1f", value)
        if (wanted == null && snapshot != null && forecast != null && atProfile != null && trend.known) {
            val iob = runCatching { forecast.iobNowU() }.getOrDefault(0.0)
            val cob = forecast.cobNowG
            val mealContext = cob >= MEAL_COB_MIN_G ||
                inputs.delivery.carbs.any { (now - it.atEpochMs) in 0L..MEAL_CONTEXT_MS }
            val peakSteps = if (mealContext) PEAK_STEPS_WITH_CARBS else PEAK_STEPS
            val eventual = atProfile.last()
            val minAfterPeak = atProfile.drop(peakSteps).minOrNull() ?: eventual
            val minPred = min(minAfterPeak, eventual)
            // The owner's resistance factor widens the caps; the coefficients it scales are already in the snapshot.
            val f = inputs.resistanceFactor.coerceIn(1.0, MAX_RESISTANCE_FACTOR)
            val caps = resistantCaps(f)
            val req = min((minPred - goal) / snapshot.sensitivityMgdlPerU, caps.maxIobU - iob)
            // Not while glucose is falling: the watch's forecast does not carry a fall forward the
            // way the phone's deviations do, so a glide towards the target would read as "above
            // target" and get insulin on top of it. Not for less than a third of a unit either -
            // each command is a connection to the pump - and not around a walk or a sport session.
            val falling = min(trend.delta, trend.shortAvgDelta) < 0.0
            val activityNow = ActivityEffect.current(inputs.delivery.activities, now) != null
            // Below the target only a meal under way justifies adding insulin: glucose climbing
            // out of a low with nothing eaten is a rebound, and insulin on it makes the next low.
            val highEnough = bg >= goal || (mealContext && bg > floorMgdl)
            if (req >= MIN_CORRECTION_U && highEnough && !falling && !fastFall && !activityNow) {
                insulinReq = req
                val hour = inputs.hourOfDay(now)
                val night = hour >= NIGHT_FROM_HOUR || hour < NIGHT_TO_HOUR
                val sinceBolusMs = inputs.delivery.boluses.maxOfOrNull { it.atEpochMs }?.let { now - it } ?: Long.MAX_VALUE
                val smbAllowed = !night && bg > floorMgdl && sinceBolusMs >= SMB_INTERVAL_MS &&
                    (mealContext || (bg >= HIGH_BG_SMB_MGDL && trend.delta > 0))
                fun raiseFor(smbU: Double): Int =
                    (floor((basalNow + 2.0 * (req - smbU)) / basalNow * 10.0 + 1e-9).toInt() * 10).coerceIn(100, caps.maxRaisePercent)
                fun keepsAboveFloor(percent: Int, smbU: Double): Boolean =
                    (runCatching { forecast.series(basalNow * percent / 100.0, REDUCTION_MINUTES, smbU) }.getOrNull()?.minOrNull() ?: Int.MIN_VALUE) >= floorMgdl
                if (smbAllowed) {
                    val cap = min(caps.maxSmbU, caps.maxSmbPerHourU - inputs.smbGivenLastHourU)
                    smbTenths = floor(min(req / 2.0, cap) * 10.0 + 1e-9).toInt().coerceAtLeast(0)
                }
                var raise = raiseFor(smbTenths / 10.0)
                if (smbTenths > 0 && !keepsAboveFloor(raise, smbTenths / 10.0)) {
                    smbTenths = 0
                    raise = raiseFor(0.0)
                }
                if (raise > 100 && !keepsAboveFloor(raise, 0.0)) raise = 100
                if (raise > 100 || smbTenths > 0) {
                    wantedPercent = raise
                    rule = Rule.CORRECTION
                    text = "$glucoseText: прогноз $minPred (после ${peakSteps * 5} мин), цель ${n(goal)} — нужно ${u1(req)} ЕД" +
                        (if (smbTenths > 0) ", микродоза ${u1(smbTenths / 10.0)} ЕД" else "") +
                        (if (raise > 100) ", базал $raise %" else "") +
                        (if (f > 1.0 + 1e-9) " · сопротивление +${((f - 1.0) * 100).roundToInt()} %" else "")
                }
            }
        }

        val running = inputs.delivery.tbrAt(now)
        val runningPercent = running?.percent ?: 100
        val remainingMinutes = running?.let { (it.endEpochMs - now) / 60_000.0 } ?: 0.0
        // What runs now ends before the next reading or two: whatever is wanted has to be set now.
        val runsOut = running != null && remainingMinutes < RENEW_BEFORE_MINUTES
        val set = Action.SetTbr(wantedPercent, if (rule == Rule.RESUME || rule == Rule.ZERO_LIMIT) RESUME_MINUTES else REDUCTION_MINUTES)

        // Every command is a connection to the pump, so one is sent only when it changes something
        // that matters. Stopping basal always does. Trading sixty per cent for fifty does not.
        val action: Action = when {
            // Basal above profile: set at once over a reduction or over nothing, over a smaller
            // raise only by a real step, and left alone when the pump already gives as much or
            // more - unless that much does not keep the forecast above the floor to its end.
            wantedPercent > 100                                               -> when {
                running == null || runsOut || runningPercent < 100       -> set
                wantedPercent - runningPercent >= MIN_RAISE_PERCENT      -> set
                runningPercent > wantedPercent && forecast != null &&
                    (runCatching { forecast.series(basalNow * runningPercent / 100.0, ceil(remainingMinutes).toInt().coerceAtLeast(5)) }
                        .getOrNull()?.minOrNull() ?: Int.MAX_VALUE) < floorMgdl -> set
                else                                                     -> Action.Leave
            }

            wantedPercent >= 100                                              ->
                // Nothing is to be held back. A reduction still running is left to run out. A
                // temporary basal above profile is brought down to just under profile when the
                // forecast does not bear it to its end, or when it is the watch's own raise and
                // the reason for it is gone - the phone, too, re-decides at every reading.
                if (runningPercent > 100 && (
                        (running?.byWatch == true && insulinReq == null) ||
                            (forecast != null &&
                                (runCatching { forecast.series(basalNow * runningPercent / 100.0, ceil(remainingMinutes).toInt().coerceAtLeast(5)) }
                                    .getOrNull()?.minOrNull() ?: Int.MAX_VALUE) < floorMgdl)
                        )
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

        // The rule that held the watch back, for the owner's journal, next to the one that asked.
        val remainingText = "ещё ${ceil(remainingMinutes).toInt()} мин"
        val whose = if (running?.byWatch == true) "от часов" else "от телефона"
        val hold: String? = if (action != Action.Leave) null else when {
            wantedPercent > 100                                -> when {
                runningPercent >= wantedPercent -> "на помпе $runningPercent % $whose ($remainingText): не меньше нужного"
                else                            -> "подъём с $runningPercent % до $wantedPercent % меньше $MIN_RAISE_PERCENT %: не стоит соединения с помпой"
            }

            wantedPercent >= 100                               -> when {
                running == null || runningPercent == 100 -> null
                runningPercent > 100                     -> "на помпе $runningPercent % $whose ($remainingText): прогноз это выдерживает"
                else                                     -> "на помпе уже $runningPercent % $whose, $remainingText: пусть доработает"
            }
            wantedPercent >= NEAR_PROFILE_PERCENT              -> "$wantedPercent % вместо профиля не стоят соединения с помпой"
            wantedPercent < runningPercent                     -> "шаг вниз с $runningPercent % до $wantedPercent % меньше $MIN_LOWER_PERCENT %: ждём конца текущего базала ($remainingText)"
            wantedPercent == runningPercent                    -> "на помпе уже $runningPercent %, $remainingText"
            running?.byWatch != true                           -> "на помпе $runningPercent % $whose ($remainingText): это меньше нужного, а поднимать чужой базал часы не станут"
            wantedPercent - runningPercent < MIN_RAISE_PERCENT -> "подъём с $runningPercent % до $wantedPercent % меньше $MIN_RAISE_PERCENT %: не стоит соединения"
            else                                               -> "базал $runningPercent % держится меньше $MIN_MINUTES_BEFORE_RAISE мин: поднимать рано"
        }

        val asRunning = if (running == null || forecast == null) null else runCatching {
            forecast.series(basalNow * runningPercent / 100.0, ceil(remainingMinutes).toInt().coerceAtLeast(1))
        }.getOrNull()
        val carbsToTarget = snapshot?.let { CarbsNeeded.gramsToAvoidLow(asRunning ?: atProfile ?: emptyList(), it.sensitivityMgdlPerU, it.carbRatioGPerU, floorMgdl = floorMgdl) }
        return Decision(
            action, rule, wantedPercent, text, carbsHint, trend, forecastMin, forecastEnd, atProfile, asRunning, hold,
            forecast?.let { runCatching { it.iobNowU() }.getOrNull()?.takeIf { iob -> iob.isFinite() } },
            carbsToTarget, smbTenths, insulinReq
        )
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

        /** A reading older than this does not correct a meal bolus towards the target. */
        const val BOLUS_MAX_READING_AGE_MINUTES = 15.0

        /** The phone's snapshot serves a meal bolus for a day, like the watch's basal. */
        const val BOLUS_MAX_SNAPSHOT_AGE_MS = 24 * 60 * 60_000L

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

        /**
         * Five-minute steps of the forecast skipped before its lowest point counts, as the phone
         * waits for the insulin peak: 90 minutes, 60 with carbohydrates at work (oref).
         */
        const val PEAK_STEPS = 18
        const val PEAK_STEPS_WITH_CARBS = 12

        /** Less than this is not worth a connection to the pump: no correction at all. */
        const val MIN_CORRECTION_U = 0.3

        /** The watch's own caps on insulin it adds alone, under whatever the phone's settings allow. */
        const val MAX_IOB_ALONE_U = 6.0
        const val MAX_SMB_U = 0.5
        const val MAX_SMB_PER_HOUR_U = 2.0
        const val MAX_RAISE_PERCENT = 150

        /**
         * With the owner's resistance mode on (see [Inputs.resistanceFactor]) the caps widen with the
         * factor, but never beyond these: the mode is the owner's judgement, the ceilings are not.
         */
        const val MAX_RESISTANCE_FACTOR = 1.3
        const val MAX_IOB_RESISTANT_U = 8.0
        const val MAX_SMB_RESISTANT_U = 0.7
        const val MAX_RAISE_RESISTANT_PERCENT = 200

        /** The caps on what the watch adds under the owner's resistance [factor], clamped to [MAX_RESISTANCE_FACTOR]. */
        fun resistantCaps(factor: Double): ResistantCaps {
            val f = factor.coerceIn(1.0, MAX_RESISTANCE_FACTOR)
            return ResistantCaps(
                maxIobU = min(MAX_IOB_RESISTANT_U, MAX_IOB_ALONE_U * f),
                maxSmbU = min(MAX_SMB_RESISTANT_U, MAX_SMB_U * f),
                maxSmbPerHourU = MAX_SMB_PER_HOUR_U * f,
                maxRaisePercent = min(MAX_RAISE_RESISTANT_PERCENT, floor(MAX_RAISE_PERCENT * f / 10.0 + 1e-9).toInt() * 10)
            )
        }

        /** Since any bolus, the pump's or the watch's: the phone's own interval between microboluses. */
        const val SMB_INTERVAL_MS = 5 * 60_000L

        /** Carbohydrates entered this long ago, or this much still on board, mean a meal is under way. */
        const val MEAL_CONTEXT_MS = 3 * 60 * 60_000L
        const val MEAL_COB_MIN_G = 5.0

        /** Without a meal a microbolus needs glucose this high and rising. */
        const val HIGH_BG_SMB_MGDL = 160.0

        /** No microboluses at night; basal above profile still. Hours of the pump's day. */
        const val NIGHT_FROM_HOUR = 23
        const val NIGHT_TO_HOUR = 6
    }
}
