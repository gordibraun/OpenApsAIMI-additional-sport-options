package app.aaps.core.objects.basaltuner

import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the basal tuner makes of the measured need: a proposal, or nothing.
 *
 * It proposes; the owner decides. The owner's words (16.11.2026): it may propose, with the reasons written out,
 * but must not wear him down - not more often than once in six hours and not at night; he will watch it for a
 * few days and then say whether it stays.
 *
 * An hour of the profile is proposed for a change only when
 *  - there are enough clean hours behind the number ([MIN_SAMPLES] of them from [MIN_NIGHTS] nights),
 *  - the profile lies outside the middle half of what was measured, and by at least [MIN_GAP_SHARE] of itself,
 *  - the hour was not changed in the last [SETTLE_DAYS] days (the change has to show in the data first) and no
 *    proposal for it was declined in the last [DECLINED_REST_DAYS].
 * The step is half the difference, at most [MAX_STEP_DOWN_SHARE] down and [MAX_STEP_UP_SHARE] up, on the pump's
 * steps. Up is the careful direction: never after a low in the hours where that insulin acts. And the whole walk
 * stays within [REFERENCE_BAND] of the profile the tuner started from - beyond that a person has to look.
 */
object BasalTunerRules {

    enum class Verdict(val text: String) {
        NO_DATA("нет часов без еды"),
        FEW("мало данных"),
        SETTLING("недавно менялся — жду новых ночей"),
        DECLINED("предложение отклонено — пауза"),
        WITHIN("в пределах разброса"),
        SMALL("разница мала"),
        GRID("разница меньше шага"),
        LOWS("ниже потребности, но были низкие сахара — не повышаю"),
        BOUND("предел изменений от исходного профиля"),
        LOWER("профиль выше потребности"),
        RAISE("профиль ниже потребности")
    }

    class Change(val hour: Int, val fromUph: Double, val toUph: Double)

    class Judged(val hour: Int, val verdict: Verdict, val change: Change?)

    /**
     * @param profile the running profile's basal by the hour, as the owner wrote it
     * @param reference the profile the tuner started from
     * @param restUntil hour -> the moment until which it is left alone (changed lately, or a proposal declined)
     * @param restIsDeclined hours whose rest is that of a declined proposal
     */
    fun judge(
        estimates: List<BasalNeed.HourEstimate>, profile: List<Double>, reference: List<Double>,
        restUntil: Map<Int, Long>, restIsDeclined: Set<Int>, recentLowClockHours: Set<Int>, nowEpochMs: Long
    ): List<Judged> = estimates.map { e ->
        val hour = e.hour
        val now = profile[hour]
        fun no(verdict: Verdict) = Judged(hour, verdict, null)
        if (e.samples == 0) return@map no(Verdict.NO_DATA)
        if (e.samples < MIN_SAMPLES || e.nights < MIN_NIGHTS) return@map no(Verdict.FEW)
        if ((restUntil[hour] ?: 0L) > nowEpochMs) return@map no(if (hour in restIsDeclined) Verdict.DECLINED else Verdict.SETTLING)
        if (now >= e.q1Uph && now <= e.q3Uph) return@map no(Verdict.WITHIN)
        val gap = e.needUph - now
        if (abs(gap) < MIN_GAP_SHARE * now) return@map no(Verdict.SMALL)
        val up = gap > 0
        // Insulin of the hour H acts in the hours after it: a low there forbids raising H.
        if (up && (1..LOW_GUARD_HOURS).any { (hour + it) % 24 in recentLowClockHours }) return@map no(Verdict.LOWS)
        val step = (STEP_SHARE_OF_GAP * gap).coerceIn(-MAX_STEP_DOWN_SHARE * now, MAX_STEP_UP_SHARE * now)
        val floor = onGrid(reference[hour] * (1 - REFERENCE_BAND))
        val ceiling = onGrid(reference[hour] * (1 + REFERENCE_BAND))
        val wanted = onGrid(now + step)
        val to = wanted.coerceIn(floor, ceiling)
        if (to == onGrid(now) || (up && to <= now) || (!up && to >= now)) return@map no(if (wanted != to) Verdict.BOUND else Verdict.GRID)
        Judged(hour, if (up) Verdict.RAISE else Verdict.LOWER, Change(hour, now, to))
    }

    /** The steps a proposal is made on: five hundredths of a unit an hour (one hundredth below half a unit). The Combo holds all of them. */
    fun onGrid(rateUph: Double): Double {
        val thousandths = (rateUph * 1000.0).roundToInt()
        val step = if (thousandths < 500) 10 else 50
        return ((thousandths + step / 2) / step) * step / 1000.0
    }

    /** The automatic calculation runs once a day, after the night is in the records: at the first chance after [RUN_AFTER_HOUR]. */
    fun dueForDailyRun(nowEpochMs: Long, lastRunEpochMs: Long, zone: TimeZone): Boolean {
        val localNow = nowEpochMs + zone.getOffset(nowEpochMs)
        val today = Math.floorDiv(localNow, DAY_MS)
        val hour = Math.floorMod(localNow, DAY_MS) / HOUR_MS
        if (hour < RUN_AFTER_HOUR || hour >= QUIET_FROM_HOUR) return false
        if (lastRunEpochMs <= 0L) return true
        val localLast = lastRunEpochMs + zone.getOffset(lastRunEpochMs)
        val lastDay = Math.floorDiv(localLast, DAY_MS)
        val lastHour = Math.floorMod(localLast, DAY_MS) / HOUR_MS
        return lastDay < today || lastHour < RUN_AFTER_HOUR
    }

    /** The owner's bounds: not at night, and not more often than once in six hours. */
    fun mayNotify(nowEpochMs: Long, lastNotifiedEpochMs: Long, zone: TimeZone): Boolean {
        val hour = Math.floorMod(nowEpochMs + zone.getOffset(nowEpochMs), DAY_MS) / HOUR_MS
        if (hour < QUIET_UNTIL_HOUR || hour >= QUIET_FROM_HOUR) return false
        return lastNotifiedEpochMs <= 0L || nowEpochMs - lastNotifiedEpochMs >= MIN_BETWEEN_NOTICES_MS
    }

    // ---- words for the owner ----------------------------------------------------------------------------------------

    fun number(value: Double, decimals: Int = 2): String = "%.${decimals}f".format(Locale.US, value).replace('.', ',')

    private fun clock(hour: Int) = "%02d:00".format(Locale.US, Math.floorMod(hour, 24))

    /** «2 часа: 03:00 1,05 → 1,00; 05:00 0,95 → 1,00 ЕД/ч» */
    fun headline(changes: List<Change>): String =
        "${changes.size} ${plural(changes.size, "час", "часа", "часов")}: " + changes.sortedBy { it.hour }.joinToString("; ") { "${clock(it.hour)} ${number(it.fromUph)} → ${number(it.toUph)}" } + " ЕД/ч"

    /** Why this hour, in the owner's language: what was measured, from how much, and how the step was chosen. */
    fun reason(change: Change, e: BasalNeed.HourEstimate, days: Int): String {
        val down = change.toUph < change.fromUph
        val slope = when {
            abs(e.slopeMgdlPerHour) < 1.0 -> "сахар при этом стоял ровно"
            e.slopeMgdlPerHour < 0       -> "сахар при этом шёл вниз на ${number(-e.slopeMgdlPerHour, 0)} мг/дл в час"
            else                         -> "сахар при этом шёл вверх на ${number(e.slopeMgdlPerHour, 0)} мг/дл в час"
        }
        return "${clock(change.hour)}: ${number(change.fromUph)} → ${number(change.toUph)} ЕД/ч. Инсулин этого часа действует в ${clock(change.hour + 1)}–${clock(change.hour + 3)}. " +
            "За $days ${plural(days, "день", "дня", "дней")} там набралось ${e.samples} ${plural(e.samples, "час", "часа", "часов")} без еды из ${e.nights} ${plural(e.nights, "ночи", "ночей", "ночей")}: " +
            "действовало ${number(e.actedUph)} ЕД/ч, $slope — потребность ${number(e.needUph)} ЕД/ч (в половине случаев от ${number(e.q1Uph)} до ${number(e.q3Uph)}). " +
            "Профиль ${if (down) "выше" else "ниже"}; шаг — половина разницы, не больше ${if (down) "10" else "5"} % за раз."
    }

    /** One line of the table the owner checks the tuner by. */
    fun tableLine(e: BasalNeed.HourEstimate, profileUph: Double, judged: Judged): String =
        if (e.samples == 0) "${clock(e.hour)}  профиль ${number(profileUph)} · ${judged.verdict.text}"
        else "${clock(e.hour)}  профиль ${number(profileUph)} · потребность ${number(e.needUph)} (${number(e.q1Uph)}–${number(e.q3Uph)}) · " +
            "${e.samples} ч из ${e.nights} ${plural(e.nights, "ночи", "ночей", "ночей")} · ${judged.verdict.text}" + (judged.change?.let { " → ${number(it.toUph)}" } ?: "")

    fun plural(n: Int, one: String, few: String, many: String): String {
        val tens = n % 100
        val units = n % 10
        return if (tens in 11..14) many else when (units) { 1 -> one; in 2..4 -> few; else -> many }
    }

    const val MIN_SAMPLES = 10
    const val MIN_NIGHTS = 6
    const val MIN_GAP_SHARE = 0.08
    const val STEP_SHARE_OF_GAP = 0.5
    const val MAX_STEP_DOWN_SHARE = 0.10
    const val MAX_STEP_UP_SHARE = 0.05
    const val REFERENCE_BAND = 0.25
    const val SETTLE_DAYS = 3
    const val DECLINED_REST_DAYS = 7
    const val LOW_GUARD_HOURS = 4

    /** The night's data are in by then. */
    const val RUN_AFTER_HOUR = 9L
    const val QUIET_UNTIL_HOUR = 9L
    const val QUIET_FROM_HOUR = 21L
    const val MIN_BETWEEN_NOTICES_MS = 6 * 3_600_000L

    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L
}
