package app.aaps.combobench.controller

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max

/**
 * Tells a walk or a sport session from the watch's own senses, so that the owner's exercise is on
 * record even when nobody entered it (29.10.2026: a session entered for 17:02 ran at 21:00, and the
 * watch alone dosed into its rise).
 *
 * Two sources. The workout the watch's fitness app is tracking, as Health Services reports it, is
 * taken at its word: it starts and ends the session and names its kind. Heart rate alone is the
 * fallback: a pulse held well above the owner's resting rate - and above where it sat just before -
 * for a while starts a session that the owner is asked about, and a pulse back down for a while
 * ends it. Steps tell a walk from effort without steps (03.11.2026: a brisk fifteen-minute walk at
 * 105-117 was filed as sport and kept «going» for two hours, because the resting rate is the
 * night's and the owner sits at 85-100 by day). Pure logic; the Android side feeds it and acts on
 * what comes back.
 */
class ActivityDetector(
    val restingDefaultBpm: Int = DEFAULT_RESTING_BPM
) {

    sealed class Signal {

        /** A session begins: record it from [startEpochMs] with a provisional [durationMinutes]; [sure] when the fitness app says so. */
        data class Started(val mode: String, val startEpochMs: Long, val durationMinutes: Int, val sure: Boolean, val why: String) : Signal()

        /** A running session has outgrown its provisional duration. */
        data class Extended(val startEpochMs: Long, val durationMinutes: Int) : Signal()

        /** The session is over; [durationMinutes] is what it really lasted. */
        data class Ended(val startEpochMs: Long, val durationMinutes: Int) : Signal()
    }

    class Session(
        val startEpochMs: Long,
        var mode: String,
        var durationMinutes: Int,
        val fromWorkout: Boolean,
        var confirmed: Boolean = false,
        var lastEffortEpochMs: Long = startEpochMs,
        var calmSinceEpochMs: Long? = null,
        var ended: Boolean = false,
        /** Where the pulse sat in the half hour before it rose; null when that was not known. */
        val baselineBpm: Int? = null
    )

    /** Minute-by-minute heart rate of the last day, for the resting estimate; (at, bpm). */
    private val heartRate = ArrayDeque<Pair<Long, Int>>()

    /** Step counts as Health Services gives them, (start, end, steps), for the last few hours. */
    private val steps = ArrayDeque<Triple<Long, Long, Int>>()
    private var elevatedSinceEpochMs: Long? = null

    /** The pulse before it rose: the median of the half hour before [elevatedSinceEpochMs]; null when too few samples. */
    private var elevatedBaselineBpm: Int? = null

    /** Samples at or above the hard threshold since the pulse rose. */
    private var hardMinutes = 0
    private var declinedUntilEpochMs = 0L
    var session: Session? = null
        private set

    /** When the newest pulse sample came; 0 before the first. */
    val newestSampleEpochMs: Long get() = heartRate.lastOrNull()?.first ?: 0L

    /** The owner's resting pulse as the lowest tenth of the last day's samples, within sense; the default until enough is known. */
    val restingBpm: Int
        get() {
            if (heartRate.size < MIN_SAMPLES_FOR_RESTING) return restingDefaultBpm
            val sorted = heartRate.map { it.second }.sorted()
            return sorted[(sorted.size * 0.1).toInt().coerceIn(0, sorted.lastIndex)].coerceIn(MIN_RESTING_BPM, MAX_RESTING_BPM)
        }

    fun onHeartRate(atEpochMs: Long, bpm: Int): List<Signal> {
        if (bpm !in 30..230) return emptyList()
        if (heartRate.isEmpty() || atEpochMs - heartRate.last().first >= 60_000L) {
            heartRate.addLast(atEpochMs to bpm)
            while (heartRate.isNotEmpty() && atEpochMs - heartRate.first().first > HISTORY_MS) heartRate.removeFirst()
        }
        val resting = restingBpm
        val out = mutableListOf<Signal>()
        val current = session
        if (current != null && !current.ended) {
            if (current.fromWorkout) return out // the fitness app ends its own workout
            if (bpm >= keepBpm(resting, current.baselineBpm)) {
                current.lastEffortEpochMs = atEpochMs
                current.calmSinceEpochMs = null
                extendIfOutgrown(current, atEpochMs)?.let { out += it }
            } else {
                if (current.calmSinceEpochMs == null) current.calmSinceEpochMs = atEpochMs
                if (atEpochMs - checkNotNull(current.calmSinceEpochMs) >= CALM_MINUTES * 60_000L) out += end(current, checkNotNull(current.calmSinceEpochMs))
            }
            return out
        }
        // Nothing on: is the pulse up for long enough, and far enough above where it sat, to mean exercise?
        val baseline = if (elevatedSinceEpochMs == null) baselineBefore(atEpochMs) else elevatedBaselineBpm
        val start = startBpm(resting, baseline)
        if (bpm >= start) {
            if (elevatedSinceEpochMs == null) { elevatedSinceEpochMs = atEpochMs; elevatedBaselineBpm = baseline; hardMinutes = 0 }
            if (bpm >= hardBpm(resting, baseline)) hardMinutes++
            val since = checkNotNull(elevatedSinceEpochMs)
            if (atEpochMs - since >= ELEVATED_MINUTES * 60_000L && atEpochMs >= declinedUntilEpochMs) {
                val stepsPerMin = stepsPerMinute(since, atEpochMs)
                val walking = stepsPerMin != null && stepsPerMin >= WALK_STEPS_PER_MINUTE
                val mode = when {
                    hardMinutes >= HARD_MINUTES_FOR_SPORT                     -> "SPORT"
                    !walking && hardMinutes >= HARD_MINUTES_WITHOUT_STEPS     -> "SPORT"
                    else                                                      -> "WALK"
                }
                val duration = if (mode == "SPORT") PROVISIONAL_SPORT_MINUTES else PROVISIONAL_WALK_MINUTES
                session = Session(since, mode, duration, fromWorkout = false, lastEffortEpochMs = atEpochMs, baselineBpm = elevatedBaselineBpm)
                val why = "пульс $bpm при покое $resting" + (elevatedBaselineBpm?.let { ", до этого $it" } ?: "") +
                    " уже ${ELEVATED_MINUTES} мин, " + (stepsPerMin?.let { "шагов ${it.toInt()}/мин" } ?: "шагов нет") +
                    (if (hardMinutes > 0) ", тяжело $hardMinutes мин" else "")
                elevatedSinceEpochMs = null
                elevatedBaselineBpm = null
                hardMinutes = 0
                out += Signal.Started(mode, since, duration, sure = false, why = why)
            }
        } else if (bpm < start - ELEVATED_HYSTERESIS_BPM) {
            elevatedSinceEpochMs = null
            elevatedBaselineBpm = null
            hardMinutes = 0
        }
        return out
    }

    /** Steps counted over an interval, as Health Services reports them. */
    fun onSteps(startEpochMs: Long, endEpochMs: Long, count: Int) {
        if (endEpochMs <= startEpochMs || count < 0) return
        steps.addLast(Triple(startEpochMs, endEpochMs, count))
        while (steps.isNotEmpty() && endEpochMs - steps.first().second > STEPS_HISTORY_MS) steps.removeFirst()
    }

    /** Steps per minute over a window, from the intervals that overlap it; null when none does. */
    fun stepsPerMinute(fromEpochMs: Long, toEpochMs: Long): Double? {
        if (toEpochMs <= fromEpochMs) return null
        var total = 0.0
        var touched = false
        for ((s, e, n) in steps) {
            val overlap = minOf(e, toEpochMs) - maxOf(s, fromEpochMs)
            if (overlap <= 0L) continue
            touched = true
            total += n * overlap.toDouble() / (e - s)
        }
        return if (touched) total / ((toEpochMs - fromEpochMs) / 60_000.0) else null
    }

    /** The median of the samples in the half hour before [atEpochMs]; null with too few of them. */
    private fun baselineBefore(atEpochMs: Long): Int? {
        val before = heartRate.filter { it.first >= atEpochMs - BASELINE_WINDOW_MS && it.first < atEpochMs }.map { it.second }.sorted()
        return if (before.size < MIN_BASELINE_SAMPLES) null else before[before.size / 2]
    }

    /** Effort that starts a session: well above rest, and above where the pulse sat just before. */
    private fun startBpm(resting: Int, baseline: Int?): Int = maxOf(resting + ELEVATED_BPM, (baseline ?: 0) + ABOVE_BASELINE_START_BPM)

    /** Effort that keeps one going: a little under that, and still above where the pulse sat before it. */
    private fun keepBpm(resting: Int, baseline: Int?): Int = maxOf(resting + KEEP_BPM, (baseline ?: 0) + ABOVE_BASELINE_KEEP_BPM)

    /** Hard effort, the kind that means sport: far above rest, and far above where the pulse sat (a brisk walk from 93 reaches 117). */
    private fun hardBpm(resting: Int, baseline: Int?): Int = maxOf(resting + HARD_BPM, (baseline ?: 0) + ABOVE_BASELINE_HARD_BPM)

    /** The fitness app's word: a workout of [type] is running, or has stopped. */
    fun onWorkout(atEpochMs: Long, type: String?, running: Boolean): List<Signal> {
        val current = session
        if (running) {
            if (current != null && !current.ended) {
                // A workout confirms and names a session the pulse had started.
                val mode = modeOf(type)
                if (!current.fromWorkout && current.mode != mode) {
                    current.mode = mode
                    return listOf(Signal.Extended(current.startEpochMs, current.durationMinutes))
                }
                return emptyList()
            }
            val mode = modeOf(type)
            val duration = if (mode == "SPORT") PROVISIONAL_SPORT_MINUTES else PROVISIONAL_WALK_MINUTES
            session = Session(atEpochMs, mode, duration, fromWorkout = true, confirmed = true, lastEffortEpochMs = atEpochMs)
            return listOf(Signal.Started(mode, atEpochMs, duration, sure = true, why = "тренировка в приложении часов: ${type ?: "без типа"}"))
        }
        if (current == null || current.ended || !current.fromWorkout) return emptyList()
        return listOf(end(current, atEpochMs))
    }

    /** Time passing without a heart-rate sample; ends a pulse-started session that has gone quiet, extends one still on. */
    fun onTick(nowEpochMs: Long): List<Signal> {
        val current = session?.takeIf { !it.ended } ?: return emptyList()
        if (current.fromWorkout) return extendIfOutgrown(current, nowEpochMs)?.let { listOf(it) } ?: emptyList()
        if (nowEpochMs - current.lastEffortEpochMs >= QUIET_MINUTES * 60_000L) return listOf(end(current, current.lastEffortEpochMs))
        return extendIfOutgrown(current, nowEpochMs)?.let { listOf(it) } ?: emptyList()
    }

    /** The heart rate within a window as a few numbers; null without at least three samples. */
    data class HrProfile(val avgBpm: Int, val maxBpm: Int, val effortMinutes: Int, val hardMinutes: Int, val restingBpm: Int, val samples: Int) {

        fun toTokens(): String = "hr_avg=$avgBpm hr_max=$maxBpm hr_effort=$effortMinutes hr_hard=$hardMinutes hr_rest=$restingBpm hr_samples=$samples"
    }

    fun profile(startEpochMs: Long, endEpochMs: Long): HrProfile? {
        val inWindow = heartRate.filter { it.first in startEpochMs..endEpochMs }.map { it.second }
        if (inWindow.size < 3) return null
        val resting = restingBpm
        return HrProfile(
            avgBpm = inWindow.average().toInt(), maxBpm = inWindow.max(),
            effortMinutes = inWindow.count { it >= resting + ELEVATED_BPM }, hardMinutes = inWindow.count { it >= resting + HARD_BPM },
            restingBpm = resting, samples = inWindow.size
        )
    }

    /** The owner: «не нагрузка». The session is forgotten and the pulse is not asked about again for a while. */
    fun decline(nowEpochMs: Long) {
        session = null
        elevatedSinceEpochMs = null
        elevatedBaselineBpm = null
        hardMinutes = 0
        declinedUntilEpochMs = nowEpochMs + DECLINE_QUIET_MS
    }

    /** The owner names the session; true when the mode changed. */
    fun confirm(mode: String): Boolean {
        val current = session ?: return false
        current.confirmed = true
        if (current.mode == mode) return false
        current.mode = mode
        return true
    }

    private fun extendIfOutgrown(current: Session, nowEpochMs: Long): Signal.Extended? {
        val runningMinutes = (nowEpochMs - current.startEpochMs) / 60_000.0
        if (runningMinutes < current.durationMinutes - EXTEND_AHEAD_MINUTES) return null
        current.durationMinutes += EXTEND_STEP_MINUTES
        return Signal.Extended(current.startEpochMs, current.durationMinutes)
    }

    private fun end(current: Session, endEpochMs: Long): Signal.Ended {
        val minutes = ceil((endEpochMs - current.startEpochMs) / 300_000.0).toInt() * 5
        current.durationMinutes = max(MIN_SESSION_MINUTES, minutes)
        current.ended = true
        return Signal.Ended(current.startEpochMs, current.durationMinutes)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("heartRate", JSONArray().apply { heartRate.forEach { put(JSONArray().put(it.first).put(it.second)) } })
        .put("steps", JSONArray().apply { steps.forEach { put(JSONArray().put(it.first).put(it.second).put(it.third)) } })
        .put("elevatedSince", elevatedSinceEpochMs ?: JSONObject.NULL)
        .put("elevatedBaseline", elevatedBaselineBpm ?: JSONObject.NULL)
        .put("hardMinutes", hardMinutes)
        .put("declinedUntil", declinedUntilEpochMs)
        .put("session", session?.let {
            JSONObject().put("start", it.startEpochMs).put("mode", it.mode).put("minutes", it.durationMinutes).put("fromWorkout", it.fromWorkout)
                .put("confirmed", it.confirmed).put("lastEffort", it.lastEffortEpochMs).put("calmSince", it.calmSinceEpochMs ?: JSONObject.NULL).put("ended", it.ended)
                .put("baseline", it.baselineBpm ?: JSONObject.NULL)
        } ?: JSONObject.NULL)

    companion object {

        const val DEFAULT_RESTING_BPM = 62
        const val MIN_RESTING_BPM = 45
        const val MAX_RESTING_BPM = 80
        const val MIN_SAMPLES_FOR_RESTING = 120
        const val HISTORY_MS = 24 * 60 * 60_000L

        /** Pulse above rest that counts as effort, that keeps a session going, and that means sport rather than a walk. */
        const val ELEVATED_BPM = 30
        const val ELEVATED_HYSTERESIS_BPM = 5
        const val KEEP_BPM = 25
        const val HARD_BPM = 45

        /** Above where the pulse sat in the half hour before: by day the owner sits well above the night's rest. */
        const val ABOVE_BASELINE_START_BPM = 15
        const val ABOVE_BASELINE_KEEP_BPM = 10
        const val ABOVE_BASELINE_HARD_BPM = 30
        const val BASELINE_WINDOW_MS = 30 * 60_000L
        const val MIN_BASELINE_SAMPLES = 5

        /** Sport: this many hard minutes; or effort without the steps of a walk and a couple of hard minutes. */
        const val HARD_MINUTES_FOR_SPORT = 5
        const val HARD_MINUTES_WITHOUT_STEPS = 2
        const val WALK_STEPS_PER_MINUTE = 40.0
        const val STEPS_HISTORY_MS = 3 * 60 * 60_000L
        const val ELEVATED_MINUTES = 8
        const val CALM_MINUTES = 10
        const val QUIET_MINUTES = 20
        const val PROVISIONAL_WALK_MINUTES = 30
        const val PROVISIONAL_SPORT_MINUTES = 45
        const val EXTEND_AHEAD_MINUTES = 5
        const val EXTEND_STEP_MINUTES = 15
        const val MIN_SESSION_MINUTES = 10
        const val DECLINE_QUIET_MS = 30 * 60_000L

        /** The fitness app's workout kind as a walk or sport; anything not a walk is sport. */
        fun modeOf(type: String?): String {
            val t = type?.uppercase().orEmpty()
            return if (t.contains("WALK") || t.contains("HIKING") || t.contains("STROLL")) "WALK" else "SPORT"
        }

        fun fromJson(json: JSONObject, restingDefaultBpm: Int = DEFAULT_RESTING_BPM): ActivityDetector {
            val detector = ActivityDetector(restingDefaultBpm)
            json.optJSONArray("heartRate")?.let { array ->
                for (i in 0 until array.length()) {
                    val pair = array.getJSONArray(i)
                    detector.heartRate.addLast(pair.getLong(0) to pair.getInt(1))
                }
            }
            json.optJSONArray("steps")?.let { array ->
                for (i in 0 until array.length()) {
                    val t = array.getJSONArray(i)
                    detector.steps.addLast(Triple(t.getLong(0), t.getLong(1), t.getInt(2)))
                }
            }
            detector.elevatedSinceEpochMs = if (json.isNull("elevatedSince")) null else json.optLong("elevatedSince")
            detector.elevatedBaselineBpm = if (json.isNull("elevatedBaseline")) null else json.optInt("elevatedBaseline")
            // Older state kept a boolean; it read as hard enough for sport.
            detector.hardMinutes = if (json.has("hardMinutes")) json.optInt("hardMinutes") else if (json.optBoolean("hardSeen")) HARD_MINUTES_FOR_SPORT else 0
            detector.declinedUntilEpochMs = json.optLong("declinedUntil")
            json.optJSONObject("session")?.let { s ->
                detector.session = Session(
                    s.getLong("start"), s.getString("mode"), s.getInt("minutes"), s.optBoolean("fromWorkout"), s.optBoolean("confirmed"),
                    s.optLong("lastEffort", s.getLong("start")), if (s.isNull("calmSince")) null else s.optLong("calmSince"), s.optBoolean("ended"),
                    baselineBpm = if (s.isNull("baseline")) null else s.optInt("baseline")
                )
            }
            return detector
        }
    }
}
