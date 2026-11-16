package app.aaps.core.objects.basaltuner

import java.util.TimeZone
import kotlin.math.exp
import kotlin.math.floor

/**
 * The need for basal by the hour, measured where it can be measured: in hours without food.
 *
 * In such an hour two things are known - how much insulin really acted (every dose and every minute of basal the
 * pump ran, spread over the insulin's action curve) and what glucose did. Insulin that acted while glucose stood
 * still is the need; glucose that rose or fell says how much was missing or too much:
 *
 *     need = insulin that acted in the hour + slope of glucose over the hour / sensitivity
 *
 * An hour counts only when it is clean: no carbohydrates in the five hours before it (three for a snack), no dose
 * given by hand in the five hours before it (a hand-given dose usually has food beside it, entered or not), no
 * low in the two hours before it (what follows a low is rescue food and the body's own answer), and readings all
 * the way through. With the owner's days that leaves the small hours and the early morning; the daytime is never
 * clean, and nothing is said about it.
 *
 * An hour of the PROFILE is judged by the two clock hours after it: insulin given in the hour from H acts, in the
 * mean, an hour and a half later.
 *
 * It measures; it decides nothing. See [BasalTunerRules] for what is made of the numbers.
 * The reference this was checked against number for number is `aaps-bridge/basal_tuner_proto.py`.
 */
object BasalNeed {

    class Reading(val atEpochMs: Long, val mgdl: Double)

    /** Any bolus the pump gave; [microbolus] is the loop's own (they do not make an hour unclean). */
    class Dose(val atEpochMs: Long, val units: Double, val microbolus: Boolean)

    class Carbs(val atEpochMs: Long, val grams: Double)

    /** [rate] is U/h when [absolute], else per cent of the profile's basal. */
    class TempBasal(val atEpochMs: Long, val durationMs: Long, val rate: Double, val absolute: Boolean)

    /** The profile's basal from [sinceEpochMs] on, U/h by clock hour. */
    class ProfileBasal(val sinceEpochMs: Long, val hourlyUph: List<Double>)

    class Input(
        val readings: List<Reading>, val doses: List<Dose>, val carbs: List<Carbs>, val tempBasals: List<TempBasal>, val profiles: List<ProfileBasal>,
        /** The estimate covers [days] whole days up to the last full hour before this. */
        val toEpochMs: Long, val days: Int = DAYS,
        val diaMinutes: Int = 300, val peakMinutes: Int = 55, val zone: TimeZone = TimeZone.getDefault(),
        /**
         * Temp basals that started before this moment and stand in the records as a few seconds long really ran until
         * the next one started, or for their planned half hour: until 16.11.2026 09:39 a copy of a basal's "ended"
         * event cut the basal that had replaced it (fixed in the driver; the records were left as they were).
         */
        val tempBasalsFiledShortBeforeEpochMs: Long = 0L
    )

    /** One clean hour. */
    class Sample(val atEpochMs: Long, val actedU: Double, val slopeMgdlPerHour: Double, val needUph: Double, val glucoseMgdl: Double)

    /** What the clean hours say about one hour of the profile. Without samples the numbers are zero. */
    class HourEstimate(
        val hour: Int, val samples: Int, val nights: Int,
        val needUph: Double, val q1Uph: Double, val q3Uph: Double,
        val actedUph: Double, val slopeMgdlPerHour: Double, val glucoseMgdl: Double
    )

    class Result(
        val fromEpochMs: Long, val toEpochMs: Long, val days: Int,
        /** All insulin, a day. */
        val totalDailyU: Double,
        /** What the glucose term is divided by: 1800 / total daily insulin. */
        val sensitivityMgdlPerU: Double,
        /** 24 of them, by the profile's hour. */
        val hours: List<HourEstimate>,
        val cleanHours: Int,
        /** Why the other hours were not taken: «carbs», «bolus», «low», «readings», «slope». */
        val refused: Map<String, Int>,
        /** Clock hours in which a reading below [LOW_MGDL] fell during the last [RECENT_LOW_DAYS] days. */
        val recentLowClockHours: Set<Int>
    )

    /** Share of a dose still to act [minutes] after it was given (the oref exponential curve). */
    fun stillToAct(minutes: Double, diaMinutes: Int, peakMinutes: Int): Double {
        if (minutes <= 0.0) return 1.0
        if (minutes >= diaMinutes) return 0.0
        val dia = diaMinutes.toDouble()
        val peak = peakMinutes.toDouble()
        val tau = peak * (1 - peak / dia) / (1 - 2 * peak / dia)
        val a = 2 * tau / dia
        val s = 1 / (1 - a + (1 + a) * exp(-dia / tau))
        return 1 - s * (1 - a) * ((minutes * minutes / (tau * dia * (1 - a)) - minutes / tau - 1) * exp(-minutes / tau) + 1)
    }

    /** What really ran: see [Input.tempBasalsFiledShortBeforeEpochMs]. Sorted by start. */
    fun asTheyRan(tempBasals: List<TempBasal>, filedShortBeforeEpochMs: Long): List<TempBasal> {
        val sorted = tempBasals.sortedBy { it.atEpochMs }
        return sorted.mapIndexed { index, basal ->
            if (basal.atEpochMs < filedShortBeforeEpochMs && basal.durationMs < FILED_SHORT_MS) {
                val planned = basal.atEpochMs + PLANNED_MS
                val next = sorted.getOrNull(index + 1)?.atEpochMs ?: planned
                TempBasal(basal.atEpochMs, minOf(planned, next) - basal.atEpochMs, basal.rate, basal.absolute)
            } else basal
        }
    }

    /** Median of the slopes between all pairs of readings at least 20 minutes apart, mg/dl per hour. */
    fun slopePerHour(readings: List<Reading>): Double? {
        val slopes = ArrayList<Double>()
        for (i in readings.indices) for (j in i + 1 until readings.size) {
            val minutes = (readings[j].atEpochMs - readings[i].atEpochMs) / 60_000.0
            if (minutes >= MIN_SLOPE_PAIR_MINUTES) slopes += (readings[j].mgdl - readings[i].mgdl) / minutes * 60.0
        }
        return if (slopes.isEmpty()) null else quantile(slopes, 0.5)
    }

    fun quantile(values: List<Double>, share: Double): Double {
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0]
        val position = share * (sorted.size - 1)
        val low = floor(position).toInt()
        val high = minOf(sorted.size - 1, low + 1)
        return sorted[low] + (sorted[high] - sorted[low]) * (position - low)
    }

    fun estimate(input: Input): Result {
        val end = input.toEpochMs - Math.floorMod(input.toEpochMs, HOUR_MS)
        val start = end - input.days * DAY_MS
        val first = start - LEAD_MS
        val minutes = ((end - first) / MINUTE_MS).toInt()
        val readings = input.readings.filter { it.atEpochMs >= first && it.atEpochMs < end }.sortedBy { it.atEpochMs }
        val readingTimes = LongArray(readings.size) { readings[it].atEpochMs }
        val doses = input.doses.filter { it.units > 0.0 && it.atEpochMs >= first && it.atEpochMs < end }.sortedBy { it.atEpochMs }
        val carbs = input.carbs.filter { it.grams > 0.0 && it.atEpochMs >= first && it.atEpochMs < end }.sortedBy { it.atEpochMs }
        val profiles = input.profiles.sortedBy { it.sinceEpochMs }
        require(profiles.isNotEmpty()) { "no basal profile to count from" }
        fun clockHour(at: Long) = Math.floorMod((at + input.zone.getOffset(at)) / HOUR_MS, 24L).toInt()

        // What the pump gave, by the minute: the profile's basal or the temp basal that ran then, and the doses.
        val delivered = DoubleArray(minutes)
        val ran = asTheyRan(input.tempBasals, input.tempBasalsFiledShortBeforeEpochMs)
        var nextBasal = 0
        var running: TempBasal? = null
        var profile = 0
        for (minute in 0 until minutes) {
            val at = first + minute * MINUTE_MS
            while (nextBasal < ran.size && ran[nextBasal].atEpochMs <= at) running = ran[nextBasal++]
            while (profile + 1 < profiles.size && profiles[profile + 1].sinceEpochMs <= at) profile++
            val ofProfile = profiles[profile].hourlyUph[clockHour(at)]
            val temp = running?.takeIf { at < it.atEpochMs + it.durationMs }
            val rate = if (temp == null) ofProfile else if (temp.absolute) temp.rate else ofProfile * temp.rate / 100.0
            delivered[minute] = rate / 60.0
        }
        doses.forEach { dose ->
            val minute = ((dose.atEpochMs - first) / MINUTE_MS).toInt()
            if (minute in 0 until minutes) delivered[minute] += dose.units
        }

        // What acted, by the minute.
        val kernel = DoubleArray(input.diaMinutes) { stillToAct(it.toDouble(), input.diaMinutes, input.peakMinutes) - stillToAct(it + 1.0, input.diaMinutes, input.peakMinutes) }
        val acted = DoubleArray(minutes)
        for (minute in 0 until minutes) {
            val given = delivered[minute]
            if (given == 0.0) continue
            val reach = minOf(kernel.size, minutes - minute)
            for (k in 0 until reach) acted[minute + k] += given * kernel[k]
        }
        val totalDaily = delivered.sum() / (minutes / 1440.0)
        val sensitivity = 1800.0 / totalDaily

        fun firstReadingAtOrAfter(at: Long): Int {
            val found = readingTimes.binarySearch(at)
            if (found < 0) return -found - 1
            var index = found
            while (index > 0 && readingTimes[index - 1] == at) index--
            return index
        }

        fun whyNot(from: Long): String? {
            val to = from + HOUR_MS
            val i = firstReadingAtOrAfter(from)
            val j = firstReadingAtOrAfter(to)
            if (j - i < MIN_READINGS_PER_HOUR) return "readings"
            if (carbs.any { it.atEpochMs < to && it.atEpochMs >= from - (if (it.grams >= MEAL_GRAMS) CLEAN_AFTER_MEAL_MS else CLEAN_AFTER_SNACK_MS) }) return "carbs"
            if (doses.any { !it.microbolus && it.atEpochMs < to && it.atEpochMs >= from - CLEAN_AFTER_HAND_DOSE_MS }) return "bolus"
            for (index in firstReadingAtOrAfter(from - CLEAN_AFTER_LOW_MS) until j) if (readings[index].mgdl < LOW_MGDL) return "low"
            return null
        }

        val refused = HashMap<String, Int>()
        val byClockHour = Array(24) { ArrayList<Sample>() }
        var from = start
        while (from + HOUR_MS <= end) {
            val reason = whyNot(from)
            var sample: Sample? = null
            if (reason == null) {
                val around = readings.subList(firstReadingAtOrAfter(from - SLOPE_MARGIN_MS), firstReadingAtOrAfter(from + HOUR_MS + SLOPE_MARGIN_MS))
                val slope = if (around.size >= MIN_SLOPE_READINGS) slopePerHour(around) else null
                if (slope != null) {
                    val minute = ((from - first) / MINUTE_MS).toInt()
                    var sum = 0.0
                    for (m in minute until minute + 60) sum += acted[m]
                    sample = Sample(from, sum, slope, sum + slope / sensitivity, quantile(around.map { it.mgdl }, 0.5))
                }
            }
            if (sample != null) byClockHour[clockHour(from)] += sample
            else refused.merge(reason ?: "slope", 1, Int::plus)
            from += HOUR_MS
        }

        val hours = List(24) { hour ->
            val samples = byClockHour[(hour + 1) % 24] + byClockHour[(hour + 2) % 24]
            if (samples.isEmpty()) HourEstimate(hour, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            else {
                val needs = samples.map { it.needUph }
                // A night runs from noon to noon, so that 23:00 and 02:00 are one night.
                val nights = samples.map { Math.floorDiv(it.atEpochMs - 12 * HOUR_MS + input.zone.getOffset(it.atEpochMs), DAY_MS) }.toSet().size
                HourEstimate(
                    hour, samples.size, nights, quantile(needs, 0.5), quantile(needs, 0.25), quantile(needs, 0.75),
                    quantile(samples.map { it.actedU }, 0.5), quantile(samples.map { it.slopeMgdlPerHour }, 0.5), quantile(samples.map { it.glucoseMgdl }, 0.5)
                )
            }
        }
        val recentLows = readings.filter { it.atEpochMs >= end - RECENT_LOW_DAYS * DAY_MS && it.mgdl < LOW_MGDL }.map { clockHour(it.atEpochMs) }.toSet()
        return Result(start, end, input.days, totalDaily, sensitivity, hours, byClockHour.sumOf { it.size }, refused, recentLows)
    }

    /** How long back the clean hours are gathered from. */
    const val DAYS = 28

    const val LOW_MGDL = 70.0
    const val RECENT_LOW_DAYS = 7
    const val MEAL_GRAMS = 20.0

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L

    /** Insulin given this long before the first counted hour still acts in it, and a meal that long before still matters. */
    private const val LEAD_MS = 8 * HOUR_MS
    private const val CLEAN_AFTER_MEAL_MS = 5 * HOUR_MS
    private const val CLEAN_AFTER_SNACK_MS = 3 * HOUR_MS
    private const val CLEAN_AFTER_HAND_DOSE_MS = 5 * HOUR_MS
    private const val CLEAN_AFTER_LOW_MS = 2 * HOUR_MS
    private const val MIN_READINGS_PER_HOUR = 9
    private const val SLOPE_MARGIN_MS = 30 * MINUTE_MS
    private const val MIN_SLOPE_READINGS = 14
    private const val MIN_SLOPE_PAIR_MINUTES = 20.0
    private const val FILED_SHORT_MS = 90_000L
    private const val PLANNED_MS = 30 * MINUTE_MS
}
