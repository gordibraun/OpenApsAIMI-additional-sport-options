package app.aaps.plugins.aps.openAPSAIMI.learning

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.utils.MidnightUtils
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Learns the carb ratio from meals that have run their course, and answers the profile's
 * question "what ratio now?" with it.
 *
 * The profile asks through [app.aaps.core.interfaces.aps.APS.getIc], so the bolus wizard, the loop's
 * own profile, the wear preview and the snapshot the watch doses by all get the same number; the
 * watch does not learn, it is told. The lessons come from the records on this phone, looked at a few
 * meals at a time from the loop's own thread, so that no run is held up. See [MealCarbRatioMath] for
 * the arithmetic and the bounds.
 */
@Singleton
class MealCarbRatioLearner @Inject constructor(
    private val persistenceLayer: PersistenceLayer,
    private val iobCobCalculator: IobCobCalculator,
    private val profileFunction: ProfileFunction,
    private val preferences: Preferences,
    private val sp: SP,
    private val dateUtil: DateUtil,
    private val aapsLogger: AAPSLogger
) {

    /** One evaluated meal, kept for the lessons and for the owner to read. */
    private class Stored(
        val startEpochMs: Long, val grams: Double, val bolusesU: Double, val neededU: Double,
        val effectiveIc: Double?, val profileIc: Double, val bgStart: Double, val bgEnd: Double
    )

    private class Applied(val icGPerU: Double, val atEpochMs: Long)

    private val lock = Any()
    private var loaded = false
    private val stored = mutableListOf<Stored>()
    private val applied = mutableMapOf<MealCarbRatioMath.Bucket, Applied>()
    private var lessons = mapOf<MealCarbRatioMath.Bucket, MealCarbRatioMath.Lesson>()
    private var evaluatedUpToEpochMs = 0L
    private var lastUpdateEpochMs = 0L
    private var lastRampEpochMs = 0L

    /**
     * True once every closed meal of the window has been looked at. Until then the lessons rest on
     * the oldest meals only and must not be applied: on 28 Oct the first eight meals (7-13 Oct) gave
     * a median above the profile, which the late-evening ratio then started from.
     */
    private var caughtUp = false

    fun enabled(): Boolean = preferences.get(BooleanKey.OApsAIMIMealIcLearnerEnabled)

    /** The ratio to dose carbohydrates by at [timestamp], or null when nothing has been learned for that time of day. */
    fun icFor(profile: Profile, timestamp: Long): Double? {
        if (!enabled()) return null
        val base = profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight(timestamp))
        if (!(base.isFinite() && base > 0.0)) return null
        val bucket = MealCarbRatioMath.bucket(hourOfDay(timestamp))
        val use = synchronized(lock) { load(); applied[bucket]?.icGPerU } ?: return null
        return use.coerceIn(base * MealCarbRatioMath.MIN_FACTOR, base * MealCarbRatioMath.MAX_FACTOR)
    }

    /** What is applied, from how many meals; for the log and the owner. */
    fun describe(): String = synchronized(lock) {
        load()
        if (applied.isEmpty()) return "IC учитель: пока нет урока (обедов учтено ${stored.count { it.effectiveIc != null }})"
        val parts = MealCarbRatioMath.Bucket.entries.mapNotNull { bucket ->
            val a = applied[bucket] ?: return@mapNotNull null
            val lesson = lessons[bucket]
            "${bucket.label}: ${u1(a.icGPerU)}" + (lesson?.let { " (урок ${u1(it.icGPerU)}, n=${it.meals}${if (it.fromAllDay) " весь день" else ""})" } ?: "")
        }
        "IC учитель: " + parts.joinToString(" · ") + "; обедов с уроком ${stored.count { it.effectiveIc != null }} за ${MealCarbRatioMath.KEEP_DAYS} дн"
    }

    class BucketFacts(val bucket: MealCarbRatioMath.Bucket, val lessonIc: Double?, val meals: Int, val fromAllDay: Boolean, val appliedIc: Double?, val current: Boolean, val ownMeals: Int)
    class MealFacts(val startEpochMs: Long, val grams: Double, val bolusesU: Double, val neededU: Double, val effectiveIc: Double?, val bgStart: Double, val bgEnd: Double)
    class Facts(
        val enabled: Boolean, val lessons: List<BucketFacts>, val mealsEvaluated: Int, val mealsWithResult: Int,
        val evaluatedUpToEpochMs: Long, val meals: List<MealFacts>
    )

    /** The state as data, for the dashboard. */
    fun facts(nowEpochMs: Long): Facts = synchronized(lock) {
        load()
        val now = MealCarbRatioMath.bucket(hourOfDay(nowEpochMs))
        Facts(
            enabled = enabled(),
            lessons = MealCarbRatioMath.Bucket.entries.map { b ->
                val lesson = lessons[b]
                val ownMeals = stored.count { it.effectiveIc != null && MealCarbRatioMath.bucket(hourOfDay(it.startEpochMs)) == b }
                BucketFacts(b, lesson?.icGPerU, lesson?.meals ?: ownMeals, lesson?.fromAllDay ?: false, applied[b]?.icGPerU, b == now, ownMeals)
            },
            mealsEvaluated = stored.size,
            mealsWithResult = stored.count { it.effectiveIc != null },
            evaluatedUpToEpochMs = evaluatedUpToEpochMs,
            meals = stored.sortedByDescending { it.startEpochMs }.take(12).map { MealFacts(it.startEpochMs, it.grams, it.bolusesU, it.neededU, it.effectiveIc, it.bgStart, it.bgEnd) }
        )
    }

    /** The owner's view: what is applied now and why, the lessons, the meals they rest on. */
    fun report(nowEpochMs: Long, profile: Profile): String = synchronized(lock) {
        load()
        val sb = StringBuilder()
        val bucket = MealCarbRatioMath.bucket(hourOfDay(nowEpochMs))
        val base = profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight(nowEpochMs))
        val using = icFor(profile, nowEpochMs)
        sb.appendLine(
            (using?.let { "Применяется ${u1(it)} г/ЕД (профиль ${u1(base)}), сейчас ${bucket.label}." } ?: "Применяется профильный ${u1(base)} г/ЕД: у учителя пока нет урока.") +
                (if (!enabled()) " Учитель выключен." else "")
        )
        sb.appendLine("Уроки по времени суток (медиана за ${MealCarbRatioMath.KEEP_DAYS} дн, к уроку идём не быстрее 15 % в день, в пределах 0,5–1,5 от профиля):")
        for (b in MealCarbRatioMath.Bucket.entries) {
            val lesson = lessons[b]
            val a = applied[b]
            sb.appendLine(
                "  ${b.label}: " + (lesson?.let { "урок ${u1(it.icGPerU)} (n=${it.meals}${if (it.fromAllDay) ", весь день" else ""})" } ?: "урока нет") +
                    (a?.let { ", применяется ${u1(it.icGPerU)} с ${dateUtil.timeString(it.atEpochMs)}" } ?: "")
            )
        }
        val usable = stored.filter { it.effectiveIc != null }.sortedByDescending { it.startEpochMs }
        sb.appendLine("Обедов с результатом ${usable.size} из ${stored.size} оценённых; просмотрено до ${if (evaluatedUpToEpochMs > 0) dateUtil.dateAndTimeString(evaluatedUpToEpochMs) else "—"}.")
        usable.take(8).forEach { s ->
            sb.appendLine("  ${dateUtil.dateAndTimeString(s.startEpochMs)} ${s.grams.roundToInt()} г: подано ${u1(s.bolusesU)} ЕД, нужно было ${u1(s.neededU)} → IC ${u1(s.effectiveIc!!)} (сахар ${s.bgStart.roundToInt()}→${s.bgEnd.roundToInt()} за 4 ч)")
        }
        sb.toString()
    }

    /**
     * Called by the loop. Looks at meals whose window closed since the last look (a few at a time),
     * relearns from the meals kept, and moves the applied ratios a step towards the lessons. Rare
     * and bounded: nothing happens more than once in [UPDATE_EVERY_MS], and the ratio moves once an hour.
     */
    fun update(nowEpochMs: Long) {
        if (!enabled()) return
        synchronized(lock) {
            load()
            if (nowEpochMs - lastUpdateEpochMs < UPDATE_EVERY_MS) return
            lastUpdateEpochMs = nowEpochMs
            val evaluated = evaluateClosedMeals(nowEpochMs)
            relearn(nowEpochMs)
            if (nowEpochMs - lastRampEpochMs >= RAMP_EVERY_MS) {
                ramp(nowEpochMs)
                lastRampEpochMs = nowEpochMs
            }
            save()
            if (evaluated > 0) aapsLogger.info(LTag.APS, "MealCarbRatioLearner: $evaluated meal(s) evaluated; ${describe()}")
        }
    }

    // ---- evaluation ------------------------------------------------------------------------------

    private fun evaluateClosedMeals(nowEpochMs: Long): Int {
        val horizon = nowEpochMs - MealCarbRatioMath.KEEP_DAYS * 86_400_000L
        val from = max(evaluatedUpToEpochMs, horizon) - MealCarbRatioMath.CLUSTER_MS
        val entries = persistenceLayer.getCarbsFromTimeToTimeExpanded(from, nowEpochMs, true)
            .map { MealCarbRatioMath.CarbEntry(it.timestamp, it.amount) }
        val meals = MealCarbRatioMath.meals(entries)
        var looked = 0
        var stoppedAtTheLimit = false
        for ((index, meal) in meals.withIndex()) {
            if (meal.startEpochMs <= evaluatedUpToEpochMs || meal.startEpochMs < horizon) continue
            if (meal.endEpochMs > nowEpochMs) break
            if (looked >= MAX_MEALS_PER_UPDATE) { stoppedAtTheLimit = true; break }
            evaluatedUpToEpochMs = meal.startEpochMs
            looked++
            if (meal.grams < MealCarbRatioMath.MIN_MEAL_GRAMS) continue
            // Another meal inside the window would be charged to this one.
            val next = meals.getOrNull(index + 1)
            if (next != null && next.startEpochMs < meal.endEpochMs) continue
            runCatching { evaluate(meal) }
                .onFailure { aapsLogger.warn(LTag.APS, "MealCarbRatioLearner: meal at ${dateUtil.dateAndTimeString(meal.startEpochMs)} not evaluated: ${it.javaClass.simpleName}") }
                .getOrNull()?.let { stored += it }
        }
        if (!stoppedAtTheLimit && !caughtUp) {
            caughtUp = true
            aapsLogger.info(LTag.APS, "MealCarbRatioLearner: caught up with the window; lessons may be applied from now on")
        }
        return looked
    }

    private fun evaluate(meal: MealCarbRatioMath.Meal): Stored? {
        val t0 = meal.startEpochMs
        val t1 = meal.endEpochMs
        val profile = profileFunction.getProfile(t0) ?: return null
        val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(t0 - READING_TOLERANCE_MS, t1 + READING_TOLERANCE_MS, true)
        val bg0 = readings.filter { kotlin.math.abs(it.timestamp - t0) <= READING_TOLERANCE_MS }.minByOrNull { kotlin.math.abs(it.timestamp - t0) }?.value ?: return null
        val bg1 = readings.filter { kotlin.math.abs(it.timestamp - t1) <= READING_TOLERANCE_MS }.minByOrNull { kotlin.math.abs(it.timestamp - t1) }?.value ?: return null
        val iob0 = iobCobCalculator.calculateFromTreatmentsAndTemps(t0, profile).iob
        val iob1 = iobCobCalculator.calculateFromTreatmentsAndTemps(t1, profileFunction.getProfile(t1) ?: profile).iob
        val boluses = persistenceLayer.getBolusesFromTimeToTime(t0 - MealCarbRatioMath.BOLUS_BEFORE_MS, t0 + MealCarbRatioMath.BOLUS_AFTER_MS, true)
            .filter { it.type != BS.Type.PRIMING }
            .sumOf { it.amount }
        val tempBasalNet = tempBasalNetUnits(t0, t1, profile)
        val sensitivity = sensitivityAt(t0, profile)
        val base = profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight(t0))
        if (!(sensitivity.isFinite() && sensitivity > 0.0 && base.isFinite() && base > 0.0)) return null
        val outcome = MealCarbRatioMath.outcome(
            MealCarbRatioMath.Window(meal, bg0, bg1, iob0, iob1, boluses, tempBasalNet, sensitivity, base)
        )
        aapsLogger.debug(
            LTag.APS,
            "MealCarbRatioLearner: meal ${dateUtil.dateAndTimeString(t0)} ${meal.grams} g: boluses ${u2(boluses)} U, IOB ${u2(iob0)}→${u2(iob1)}, " +
                "TBR ${u2(tempBasalNet)} U, BG $bg0→$bg1 at ISF ${u1(sensitivity)} → needed ${u2(outcome.neededU)} U, " +
                "IC ${outcome.effectiveIcGPerU?.let { u1(it) } ?: "—"} (profile ${u1(base)})"
        )
        return Stored(t0, meal.grams, boluses, outcome.neededU, outcome.effectiveIcGPerU, base, bg0, bg1)
    }

    /** Insulin the temp basals delivered above (or withheld below) profile over the window, walking it in five-minute slots. */
    private fun tempBasalNetUnits(t0: Long, t1: Long, profile: Profile): Double {
        val tbrs = persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(t0 - 3 * 60 * 60_000L, t1, true)
        var net = 0.0
        var t = t0
        while (t < t1) {
            val running = tbrs.filter { it.timestamp <= t && t < it.end }.maxByOrNull { it.timestamp }
            if (running != null) net += (running.convertedToAbsolute(t, profile) - profile.getBasal(t)) * (5.0 / 60.0)
            t += 5 * 60_000L
        }
        return net
    }

    /** The sensitivity the loop was dosing corrections by at [t]: its own variable one from the run nearest before, else the profile's now. */
    private fun sensitivityAt(t: Long, profile: Profile): Double {
        val fromRuns = runCatching { persistenceLayer.getApsResults(t - 20 * 60_000L, t + 5 * 60_000L) }.getOrDefault(emptyList())
            .filter { (it.variableSens ?: 0.0) > 0.0 }
            .maxByOrNull { it.date }
            ?.variableSens
        return fromRuns ?: profile.getIsfMgdl("MealCarbRatioLearner")
    }

    // ---- lessons -----------------------------------------------------------------------------------

    private fun relearn(nowEpochMs: Long) {
        val horizon = nowEpochMs - MealCarbRatioMath.KEEP_DAYS * 86_400_000L
        stored.removeAll { it.startEpochMs < horizon }
        lessons = MealCarbRatioMath.learn(
            stored.mapNotNull { s -> s.effectiveIc?.let { MealCarbRatioMath.bucket(hourOfDay(s.startEpochMs)) to it } }
        )
        // A bucket that lost its lesson loses its applied ratio: the profile's own stands again.
        applied.keys.retainAll(lessons.keys)
    }

    private fun ramp(nowEpochMs: Long) {
        // Lessons from a window only half looked at are not lessons yet.
        if (!caughtUp) return
        for ((bucket, lesson) in lessons) {
            // The profile's ratio at the middle of that part of the day, as the bounds and the first step refer to it.
            val profile = profileFunction.getProfile(nowEpochMs) ?: continue
            val base = profile.getIcTimeFromMidnight(bucketMiddleSecondsFromMidnight(bucket))
            if (!(base.isFinite() && base > 0.0)) continue
            val previous = applied[bucket]
            applied[bucket] = Applied(
                MealCarbRatioMath.applied(lesson.icGPerU, base, previous?.icGPerU, previous?.atEpochMs, nowEpochMs),
                nowEpochMs
            )
        }
    }

    private fun bucketMiddleSecondsFromMidnight(bucket: MealCarbRatioMath.Bucket): Int = when (bucket) {
        MealCarbRatioMath.Bucket.MORNING -> 8 * 3600 + 1800
        MealCarbRatioMath.Bucket.DAY     -> 13 * 3600 + 1800
        MealCarbRatioMath.Bucket.EVENING -> 18 * 3600 + 1800
        MealCarbRatioMath.Bucket.LATE    -> 22 * 3600 + 1800
    }

    private fun hourOfDay(timestamp: Long): Int = MidnightUtils.secondsFromMidnight(timestamp) / 3600

    // ---- persistence -----------------------------------------------------------------------------

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val saved = sp.getStringOrNull(KEY, null)?.let { JSONObject(it) } ?: return
            evaluatedUpToEpochMs = saved.optLong("evaluatedUpTo")
            lastRampEpochMs = saved.optLong("lastRamp")
            val version = saved.optInt("version", 1)
            caughtUp = saved.optBoolean("caughtUp", false)
            val meals = saved.optJSONArray("meals") ?: JSONArray()
            for (i in 0 until meals.length()) {
                val m = meals.getJSONObject(i)
                val grams = m.getDouble("g")
                val given = m.optDouble("given")
                val needed = m.optDouble("needed")
                // The ratio is re-derived with the current rules, so that a stricter rule reaches meals evaluated under a looser one.
                stored += Stored(m.getLong("t0"), grams, given, needed, MealCarbRatioMath.effectiveIc(grams, given, needed), m.optDouble("profileIc"), m.optDouble("bg0"), m.optDouble("bg1"))
            }
            // Ratios applied by version 1 started from a half-read window; they start again from the profile.
            if (version >= STATE_VERSION) {
                val app = saved.optJSONObject("applied") ?: JSONObject()
                for (bucket in MealCarbRatioMath.Bucket.entries) {
                    app.optJSONObject(bucket.name)?.let { applied[bucket] = Applied(it.getDouble("ic"), it.getLong("at")) }
                }
            } else aapsLogger.info(LTag.APS, "MealCarbRatioLearner: state version $version → $STATE_VERSION, applied ratios start again from the profile")
            lessons = MealCarbRatioMath.learn(stored.mapNotNull { s -> s.effectiveIc?.let { MealCarbRatioMath.bucket(hourOfDay(s.startEpochMs)) to it } })
        }.onFailure { aapsLogger.warn(LTag.APS, "MealCarbRatioLearner: saved state unreadable: ${it.javaClass.simpleName}") }
    }

    private fun save() {
        runCatching {
            val meals = JSONArray()
            stored.forEach { s ->
                meals.put(
                    JSONObject().put("t0", s.startEpochMs).put("g", s.grams).put("given", s.bolusesU).put("needed", s.neededU)
                        .put("ic", s.effectiveIc ?: JSONObject.NULL).put("profileIc", s.profileIc).put("bg0", s.bgStart).put("bg1", s.bgEnd)
                )
            }
            val app = JSONObject()
            applied.forEach { (bucket, a) -> app.put(bucket.name, JSONObject().put("ic", a.icGPerU).put("at", a.atEpochMs)) }
            sp.putString(
                KEY,
                JSONObject().put("version", STATE_VERSION).put("evaluatedUpTo", evaluatedUpToEpochMs).put("lastRamp", lastRampEpochMs)
                    .put("caughtUp", caughtUp).put("meals", meals).put("applied", app).toString()
            )
        }.onFailure { aapsLogger.warn(LTag.APS, "MealCarbRatioLearner: state not saved: ${it.javaClass.simpleName}") }
    }

    private fun u1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun u2(v: Double) = String.format(Locale.US, "%.2f", v)

    companion object {

        const val KEY = "aimi_meal_ic_learner_v1"
        const val STATE_VERSION = 2
        const val UPDATE_EVERY_MS = 15 * 60_000L
        const val RAMP_EVERY_MS = 60 * 60_000L

        /** A reading within this of the window's edge stands for glucose there. */
        const val READING_TOLERANCE_MS = 12 * 60_000L

        /** Meals looked at per update; the first look at three weeks of history takes a few hours this way, and holds nothing up. */
        const val MAX_MEALS_PER_UPDATE = 6
    }
}
