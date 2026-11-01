package app.aaps.plugins.aps.openAPSAIMI.learning

/**
 * The arithmetic of learning the carb ratio from meals that have run their course.
 *
 * Nothing here reads the database or the clock; the learner feeds it what it found. A meal is the
 * carbohydrates entered within an hour of each other, and its window the four hours from the first
 * entry. Fast carbohydrates eaten inside that window with no bolus of their own are a rescue against
 * the meal's insulin and count towards the meal's grams (01.11.2026: every meal bolus was followed by
 * 8–18 g of them within the hour, and the meals taught nothing because the rescue looked like the
 * next meal). The insulin the meal turned out to need is what was on board at the start, plus what was
 * given in and around the window (boluses from 45 minutes before to 150 minutes after the entry,
 * and the temp basal above or below profile), less what was still on board at the end, plus what
 * glucose moved over the window converted at the sensitivity the loop was dosing corrections by.
 * The effective ratio is the grams over that insulin. Per time of day the median of the most recent
 * meals is the lesson; it is applied to the profile's ratio within bounds and never in one jump.
 *
 * 28 Oct 2026: 59 meals over 45 days gave a median of 4.9 g/U against a profile of 10.3, which is why
 * every forecast promised a fall that never came.
 */
object MealCarbRatioMath {

    /** One carbohydrate entry; [fast] when the owner marked it as fast carbohydrates. */
    class CarbEntry(val atEpochMs: Long, val grams: Double, val fast: Boolean = false)

    /**
     * Carbohydrates entered close together are one meal, timed from the first entry. A meal made of
     * rescue carbohydrates only ([rescueOnly]) is not a meal to learn from.
     */
    class Meal(val startEpochMs: Long, val grams: Double, val entries: Int, val rescueOnly: Boolean = false) {
        val endEpochMs: Long get() = startEpochMs + WINDOW_MS
    }

    /** Fast carbohydrates of up to [RESCUE_MAX_GRAMS] with no manual bolus within [RESCUE_BOLUS_MS] are a rescue, not food. */
    fun isRescue(entry: CarbEntry, manualBolusTimesMs: List<Long>): Boolean =
        entry.fast && entry.grams <= RESCUE_MAX_GRAMS && manualBolusTimesMs.none { kotlin.math.abs(it - entry.atEpochMs) <= RESCUE_BOLUS_MS }

    fun meals(entries: List<CarbEntry>, manualBolusTimesMs: List<Long> = emptyList()): List<Meal> {
        val sorted = entries.filter { it.grams > 0.0 }.sortedBy { it.atEpochMs }
        val result = mutableListOf<Meal>()
        var start = 0L
        var last = 0L
        var grams = 0.0
        var count = 0
        var rescueOnly = false
        fun close() { if (count > 0) result += Meal(start, grams, count, rescueOnly) }
        for (entry in sorted) {
            val rescue = isRescue(entry, manualBolusTimesMs)
            when {
                // A rescue inside an open meal's window: the body got it against the meal's insulin.
                count > 0 && rescue && !rescueOnly && entry.atEpochMs < start + WINDOW_MS -> { grams += entry.grams; count++ }
                count > 0 && rescueOnly == rescue && entry.atEpochMs - last <= CLUSTER_MS -> { grams += entry.grams; last = entry.atEpochMs; count++ }
                else -> {
                    close()
                    start = entry.atEpochMs; last = entry.atEpochMs; grams = entry.grams; count = 1; rescueOnly = rescue
                }
            }
        }
        close()
        return result
    }

    /** What the learner found for one meal's window. */
    class Window(
        val meal: Meal,
        val bgStartMgdl: Double,
        val bgEndMgdl: Double,
        val iobStartU: Double,
        val iobEndU: Double,
        val bolusesU: Double,
        val tempBasalNetU: Double,
        val sensitivityMgdlPerU: Double,
        val profileIcGPerU: Double
    )

    class Outcome(
        val meal: Meal,
        val neededU: Double,
        /** Grams per unit the meal turned out to need; null when the window says nothing usable. */
        val effectiveIcGPerU: Double?,
        val profileIcGPerU: Double,
        val bgStartMgdl: Double,
        val bgEndMgdl: Double,
        val bolusesU: Double
    )

    fun outcome(w: Window): Outcome =
        Outcome(w.meal, needed(w), effectiveIc(w.meal.grams, w.bolusesU, needed(w)), w.profileIcGPerU, w.bgStartMgdl, w.bgEndMgdl, w.bolusesU)

    fun needed(w: Window): Double = w.iobStartU + w.bolusesU + w.tempBasalNetU - w.iobEndU + (w.bgEndMgdl - w.bgStartMgdl) / w.sensitivityMgdlPerU

    /**
     * Grams per unit a meal turned out to need, or null when the window cannot teach: a meal nobody
     * bolused for, or one that needed next to nothing, gives ratios that are noise (28 Oct 2026: 15 g
     * with no bolus and 0.5 U "needed" came out as 28 g/U and pulled the day's median up).
     */
    fun effectiveIc(grams: Double, bolusesU: Double, neededU: Double): Double? =
        if (bolusesU >= MIN_BOLUSES_U && neededU >= MIN_NEEDED_U) (grams / neededU).takeIf { it.isFinite() && it in MIN_IC_G_PER_U..MAX_IC_G_PER_U } else null

    /** Four parts of the day; the night goes with the late evening, meals there being few. */
    enum class Bucket(val label: String) { MORNING("утро 06–11"), DAY("день 11–16"), EVENING("вечер 16–21"), LATE("поздно 21–06") }

    fun bucket(hourOfDay: Int): Bucket = when (hourOfDay) {
        in 6..10  -> Bucket.MORNING
        in 11..15 -> Bucket.DAY
        in 16..20 -> Bucket.EVENING
        else      -> Bucket.LATE
    }

    class Lesson(val bucket: Bucket, val icGPerU: Double, val meals: Int, val fromAllDay: Boolean)

    /** One meal's lesson: its time of day, its effective ratio, when it was. */
    class Learned(val bucket: Bucket, val icGPerU: Double, val atEpochMs: Long)

    /**
     * Per bucket, the median effective ratio of that bucket's most recent [RECENT_PER_BUCKET] meals;
     * with fewer than [MIN_MEALS_PER_BUCKET] of them the median of the whole day's most recent
     * [RECENT_ALL_DAY] stands in, and with fewer than [MIN_MEALS_ALL_DAY] in all there is no lesson.
     * Recent meals only, so that a week of changed sensitivity is heard within days, not weeks.
     */
    fun learn(outcomes: List<Learned>): Map<Bucket, Lesson> {
        val newestFirst = outcomes.sortedByDescending { it.atEpochMs }
        val all = newestFirst.take(RECENT_ALL_DAY).map { it.icGPerU }
        val allDay = if (all.size >= MIN_MEALS_ALL_DAY) median(all) else null
        return Bucket.entries.mapNotNull { bucket ->
            val own = newestFirst.filter { it.bucket == bucket }.take(RECENT_PER_BUCKET).map { it.icGPerU }
            when {
                own.size >= MIN_MEALS_PER_BUCKET -> Lesson(bucket, median(own), own.size, fromAllDay = false)
                allDay != null                   -> Lesson(bucket, allDay, all.size, fromAllDay = true)
                else                             -> null
            }
        }.associateBy { it.bucket }
    }

    /**
     * The ratio to dose by: the lesson held within [MIN_FACTOR]..[MAX_FACTOR] of the profile's ratio,
     * approached from [previousIcGPerU] (the profile's ratio when there is none) by at most
     * [MAX_STEP_PER_DAY] per day, pro rata for the time since [previousAtEpochMs].
     */
    fun applied(lessonIcGPerU: Double, profileIcGPerU: Double, previousIcGPerU: Double?, previousAtEpochMs: Long?, nowEpochMs: Long): Double {
        val floor = profileIcGPerU * MIN_FACTOR
        val ceiling = profileIcGPerU * MAX_FACTOR
        val target = lessonIcGPerU.coerceIn(floor, ceiling)
        val from = previousIcGPerU ?: profileIcGPerU
        val days = if (previousAtEpochMs == null) 1.0 else ((nowEpochMs - previousAtEpochMs) / 86_400_000.0).coerceIn(0.0, 1.0)
        val step = MAX_STEP_PER_DAY * days
        return target.coerceIn(from * (1.0 - step), from * (1.0 + step)).coerceIn(floor, ceiling)
    }

    fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    const val CLUSTER_MS = 60 * 60_000L
    const val WINDOW_MS = 4 * 60 * 60_000L
    const val BOLUS_BEFORE_MS = 45 * 60_000L
    const val BOLUS_AFTER_MS = 150 * 60_000L
    const val MIN_MEAL_GRAMS = 8.0

    /** Less insulin than this over four hours says nothing about a ratio. */
    const val MIN_NEEDED_U = 1.0

    /** A meal with less bolus than this was not dosed for; what glucose did then is not the ratio's doing. */
    const val MIN_BOLUSES_U = 0.5
    const val MIN_IC_G_PER_U = 2.0
    const val MAX_IC_G_PER_U = 25.0
    const val KEEP_DAYS = 21
    const val MIN_MEALS_PER_BUCKET = 5
    const val MIN_MEALS_ALL_DAY = 6
    /** The lesson rests on this many of the newest meals of a bucket, or of the whole day. */
    const val RECENT_PER_BUCKET = 8
    const val RECENT_ALL_DAY = 10
    /** Fast carbohydrates up to this, with no manual bolus within [RESCUE_BOLUS_MS], are a rescue. */
    const val RESCUE_MAX_GRAMS = 25.0
    const val RESCUE_BOLUS_MS = 20 * 60_000L

    /** The learner may make the ratio half or one and a half times the profile's, never more. */
    /** 0.5 until 01.11.2026, when every meal at the floor ended in rescue carbohydrates. */
    const val MIN_FACTOR = 0.7
    const val MAX_FACTOR = 1.5

    /** The applied ratio moves by at most this share of itself per day. */
    const val MAX_STEP_PER_DAY = 0.15
}
