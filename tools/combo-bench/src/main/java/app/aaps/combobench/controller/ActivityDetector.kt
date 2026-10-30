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
 * fallback: a pulse held well above the owner's resting rate for a while starts a session that the
 * owner is asked about, and a pulse back near rest for a while ends it. Pure logic; the Android
 * side feeds it and acts on what comes back.
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
        var ended: Boolean = false
    )

    /** Minute-by-minute heart rate of the last day, for the resting estimate; (at, bpm). */
    private val heartRate = ArrayDeque<Pair<Long, Int>>()
    private var elevatedSinceEpochMs: Long? = null
    private var hardSeen = false
    private var declinedUntilEpochMs = 0L
    var session: Session? = null
        private set

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
            if (bpm >= resting + EFFORT_KEEP_BPM) {
                current.lastEffortEpochMs = atEpochMs
                current.calmSinceEpochMs = null
                extendIfOutgrown(current, atEpochMs)?.let { out += it }
            } else if (bpm < resting + CALM_BPM) {
                if (current.calmSinceEpochMs == null) current.calmSinceEpochMs = atEpochMs
                if (atEpochMs - checkNotNull(current.calmSinceEpochMs) >= CALM_MINUTES * 60_000L) out += end(current, checkNotNull(current.calmSinceEpochMs))
            }
            return out
        }
        // Nothing on: is the pulse up for long enough to mean exercise?
        if (bpm >= resting + ELEVATED_BPM) {
            if (elevatedSinceEpochMs == null) { elevatedSinceEpochMs = atEpochMs; hardSeen = false }
            if (bpm >= resting + HARD_BPM) hardSeen = true
            val since = checkNotNull(elevatedSinceEpochMs)
            if (atEpochMs - since >= ELEVATED_MINUTES * 60_000L && atEpochMs >= declinedUntilEpochMs) {
                val mode = if (hardSeen) "SPORT" else "WALK"
                val duration = if (mode == "SPORT") PROVISIONAL_SPORT_MINUTES else PROVISIONAL_WALK_MINUTES
                session = Session(since, mode, duration, fromWorkout = false, lastEffortEpochMs = atEpochMs)
                elevatedSinceEpochMs = null
                out += Signal.Started(mode, since, duration, sure = false, why = "пульс $bpm при покое $resting уже ${ELEVATED_MINUTES} мин")
            }
        } else if (bpm < resting + ELEVATED_BPM - ELEVATED_HYSTERESIS_BPM) {
            elevatedSinceEpochMs = null
            hardSeen = false
        }
        return out
    }

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

    /** The owner: «не нагрузка». The session is forgotten and the pulse is not asked about again for a while. */
    fun decline(nowEpochMs: Long) {
        session = null
        elevatedSinceEpochMs = null
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
        .put("elevatedSince", elevatedSinceEpochMs ?: JSONObject.NULL)
        .put("hardSeen", hardSeen)
        .put("declinedUntil", declinedUntilEpochMs)
        .put("session", session?.let {
            JSONObject().put("start", it.startEpochMs).put("mode", it.mode).put("minutes", it.durationMinutes).put("fromWorkout", it.fromWorkout)
                .put("confirmed", it.confirmed).put("lastEffort", it.lastEffortEpochMs).put("calmSince", it.calmSinceEpochMs ?: JSONObject.NULL).put("ended", it.ended)
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
        const val EFFORT_KEEP_BPM = 20
        const val HARD_BPM = 45
        const val CALM_BPM = 15
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
            detector.elevatedSinceEpochMs = if (json.isNull("elevatedSince")) null else json.optLong("elevatedSince")
            detector.hardSeen = json.optBoolean("hardSeen")
            detector.declinedUntilEpochMs = json.optLong("declinedUntil")
            json.optJSONObject("session")?.let { s ->
                detector.session = Session(
                    s.getLong("start"), s.getString("mode"), s.getInt("minutes"), s.optBoolean("fromWorkout"), s.optBoolean("confirmed"),
                    s.optLong("lastEffort", s.getLong("start")), if (s.isNull("calmSince")) null else s.optLong("calmSince"), s.optBoolean("ended")
                )
            }
            return detector
        }
    }
}
