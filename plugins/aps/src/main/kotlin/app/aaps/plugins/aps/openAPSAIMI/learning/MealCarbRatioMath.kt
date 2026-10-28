package app.aaps.plugins.aps.openAPSAIMI.learning

/**
 * The arithmetic of learning the carb ratio from meals that have run their course.
 *
 * Nothing here reads the database or the clock; the learner feeds it what it found. A meal is the
 * carbohydrates entered within an hour of each other, and its window the four hours from the first
 * entry. The insulin the meal turned out to need is what was on board at the start, plus what was
 * given in and around the window (boluses from 45 minutes before to 150 minutes after the entry,
 * and the temp basal above or below profile), less what was still on board at the end, plus what
 * glucose moved over the window converted at the sensitivity the loop was dosing corrections by.
 * The effective ratio is the grams over that insulin. Per time of day the median of recent meals is
 * the lesson; it is applied to the profile's ratio within bounds and never in one jump.
 *
 * 28 Oct 2026: 59 meals over 45 days gave a median of 4.9 g/U against a profile of 10.3, which is why
 * every forecast promised a fall that never came.
 */
object MealCarbRatioMath {

    class CarbEntry(val atEpochMs: Long, val grams: Double)

    /** Carbohydrates entered close together are one meal, timed from the first entry. */
    class Meal(val startEpochMs: Long, val grams: Double, val entries: Int) {
        val endEpochMs: Long get() = startEpochMs + WINDOW_MS
    }

    fun meals(entries: List<CarbEntry>): List<Meal> {
        val sorted = entries.filter { it.grams > 0.0 }.sortedBy { it.atEpochMs }
        val result = mutableListOf<Meal>()
        var start = 0L
        var last = 0L
        var grams = 0.0
        var count = 0
        fun close() { if (count > 0) result += Meal(start, grams, count) }
        for (entry in sorted) {
            if (count > 0 && entry.atEpochMs - last <= CLUSTER_MS) {
                grams += entry.grams; last = entry.atEpochMs; count++
            } else {
                close()
                start = entry.atEpochMs; last = entry.atEpochMs; grams = entry.grams; count = 1
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

    fun outcome(w: Window): Outcome {
        val needed = w.iobStartU + w.bolusesU + w.tempBasalNetU - w.iobEndU + (w.bgEndMgdl - w.bgStartMgdl) / w.sensitivityMgdlPerU
        val ic = if (needed >= MIN_NEEDED_U) (w.meal.grams / needed).takeIf { it.isFinite() && it in MIN_IC_G_PER_U..MAX_IC_G_PER_U } else null
        return Outcome(w.meal, needed, ic, w.profileIcGPerU, w.bgStartMgdl, w.bgEndMgdl, w.bolusesU)
    }

    /** Four parts of the day; the night goes with the late evening, meals there being few. */
    enum class Bucket(val label: String) { MORNING("утро 06–11"), DAY("день 11–16"), EVENING("вечер 16–21"), LATE("поздно 21–06") }

    fun bucket(hourOfDay: Int): Bucket = when (hourOfDay) {
        in 6..10  -> Bucket.MORNING
        in 11..15 -> Bucket.DAY
        in 16..20 -> Bucket.EVENING
        else      -> Bucket.LATE
    }

    class Lesson(val bucket: Bucket, val icGPerU: Double, val meals: Int, val fromAllDay: Boolean)

    /**
     * Per bucket, the median effective ratio of the meals given; with fewer than [MIN_MEALS_PER_BUCKET]
     * in a bucket the whole day's median stands in, and with fewer than [MIN_MEALS_ALL_DAY] in all
     * there is no lesson.
     */
    fun learn(outcomes: List<Pair<Bucket, Double>>): Map<Bucket, Lesson> {
        val all = outcomes.map { it.second }
        val allDay = if (all.size >= MIN_MEALS_ALL_DAY) median(all) else null
        return Bucket.entries.mapNotNull { bucket ->
            val own = outcomes.filter { it.first == bucket }.map { it.second }
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
    const val MIN_NEEDED_U = 0.3
    const val MIN_IC_G_PER_U = 1.0
    const val MAX_IC_G_PER_U = 60.0
    const val KEEP_DAYS = 21
    const val MIN_MEALS_PER_BUCKET = 5
    const val MIN_MEALS_ALL_DAY = 8

    /** The learner may make the ratio half or one and a half times the profile's, never more. */
    const val MIN_FACTOR = 0.5
    const val MAX_FACTOR = 1.5

    /** The applied ratio moves by at most this share of itself per day. */
    const val MAX_STEP_PER_DAY = 0.15
}
