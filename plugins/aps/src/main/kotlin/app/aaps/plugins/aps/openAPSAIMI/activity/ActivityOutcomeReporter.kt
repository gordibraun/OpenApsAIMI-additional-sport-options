package app.aaps.plugins.aps.openAPSAIMI.activity

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.objects.activity.ActivityPlanCalculator
import app.aaps.core.objects.extensions.convertedToAbsolute
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Once a session's tail is over, reads what the records say happened and keeps a report: the
 * owner sees it in the overview and on the coefficients dashboard, and after a few sessions of a
 * kind the dashboard says which way the tail should move. Nothing here changes a dose.
 */
@Singleton
class ActivityOutcomeReporter @Inject constructor(
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val sp: SP,
    private val dateUtil: DateUtil,
    private val uiInteraction: UiInteraction,
    private val aapsLogger: AAPSLogger
) {

    private val lock = Any()
    private var loaded = false
    private val reports = mutableListOf<ActivityOutcomeMath.Report>()

    /** Reports of sessions whose tail ended, newest first. */
    fun reports(): List<ActivityOutcomeMath.Report> = synchronized(lock) { load(); reports.sortedByDescending { it.startEpochMs } }

    fun suggestions(): List<ActivityOutcomeMath.Suggestion> = reports().let { all ->
        listOfNotNull(ActivityOutcomeMath.suggestion("SPORT", all), ActivityOutcomeMath.suggestion("WALK", all))
    }

    /** Called every loop run: cheap when there is nothing new to report. */
    fun update(nowEpochMs: Long) {
        synchronized(lock) {
            load()
            val candidates = persistenceLayer.getTherapyEventDataFromTime(nowEpochMs - LOOK_BACK_MS, TE.Type.EXERCISE, true)
                .filter { it.isValid && it.note?.startsWith(NOTE_PREFIX) == true }
                .mapNotNull { session(it) }
                .filter { it.tailEndEpochMs in (nowEpochMs - LOOK_BACK_MS)..nowEpochMs && reports.none { r -> r.startEpochMs == it.startEpochMs } }
            if (candidates.isEmpty()) return
            for (session in candidates) {
                val report = runCatching { ActivityOutcomeMath.evaluate(session, inputs(session)) }
                    .onFailure { aapsLogger.warn(LTag.APS, "activity report failed for ${session.startEpochMs}: ${it.message}") }
                    .getOrNull() ?: continue
                reports += report
                val line = ActivityOutcomeMath.line(report) { dateUtil.dateAndTimeString(it) }
                aapsLogger.info(LTag.APS, "activity report: $line")
                runCatching { uiInteraction.addNotificationValidFor(Notification.USER_MESSAGE, "Отчёт по нагрузке: $line", Notification.INFO, 180) }
            }
            while (reports.size > MAX_REPORTS) reports.removeAt(0)
            save()
        }
    }

    private fun session(event: TE): ActivityOutcomeMath.Session? {
        val tokens = event.note.orEmpty().split(' ').mapNotNull { part ->
            val at = part.indexOf('=')
            if (at <= 0 || at >= part.lastIndex) null else part.substring(0, at) to part.substring(at + 1)
        }.toMap()
        val mode = tokens["mode"]?.takeIf { it == "WALK" || it == "SPORT" } ?: return null
        val duration = (tokens["duration"]?.toIntOrNull() ?: (event.duration / 60_000L).toInt()).coerceAtLeast(5)
        val tail = (tokens["tail"]?.toIntOrNull() ?: ActivityPlanCalculator.tailMinutes(mode, duration)).coerceIn(0, 720)
        return ActivityOutcomeMath.Session(event.timestamp, duration, mode, tail, ActivityOutcomeMath.HrProfile.fromNote(event.note))
    }

    private fun inputs(session: ActivityOutcomeMath.Session): ActivityOutcomeMath.Inputs {
        val start = session.startEpochMs
        val end = session.tailEndEpochMs
        val glucose = persistenceLayer.getBgReadingsDataFromTimeToTime(start, end, true).filter { it.isValid }.map { it.timestamp to it.value }
        val carbs = persistenceLayer.getCarbsFromTimeToTimeExpanded(start, end, true).filter { it.isValid }.map { it.timestamp to it.amount }
        val boluses = persistenceLayer.getBolusesFromTimeToTime(start, end, true).filter { it.isValid && it.type != BS.Type.PRIMING }
            .map { Triple(it.timestamp, it.amount, it.type == BS.Type.SMB) }
        val tbrs = persistenceLayer.getTemporaryBasalsActiveBetweenTimeAndTime(start, end).filter { it.isValid }
        var delivered = 0.0
        var profileSum = 0.0
        var t = start
        while (t < end) {
            val profile = profileFunction.getProfile(t) ?: profileFunction.getProfile() ?: break
            val base = profile.getBasal(t)
            val running = tbrs.lastOrNull { it.timestamp <= t && t < it.end }
            val rate = running?.convertedToAbsolute(t, profile) ?: base
            delivered += rate / 12.0
            profileSum += base / 12.0
            t += 300_000L
        }
        return ActivityOutcomeMath.Inputs(glucose, carbs, boluses, delivered, profileSum)
    }

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val array = sp.getStringOrNull(KEY, null)?.let { JSONArray(it) } ?: return
            for (i in 0 until array.length()) reports += fromJson(array.getJSONObject(i))
        }.onFailure { aapsLogger.warn(LTag.APS, "activity reports unreadable: ${it.message}") }
    }

    private fun save() {
        runCatching { sp.putString(KEY, JSONArray().apply { reports.forEach { put(toJson(it)) } }.toString()) }
    }

    private fun toJson(r: ActivityOutcomeMath.Report): JSONObject = JSONObject()
        .put("start", r.startEpochMs).put("mode", r.mode).put("duration", r.durationMinutes).put("tail", r.tailMinutes)
        .put("hr", r.hr?.toTokens() ?: JSONObject.NULL)
        .put("min", r.minGlucose ?: JSONObject.NULL).put("mean", r.meanGlucose ?: JSONObject.NULL)
        .put("below70", r.minutesBelow70).put("below55", r.minutesBelow55).put("above180", r.minutesAbove180)
        .put("carbs", r.carbsG).put("bolus", r.bolusU).put("smb", r.smbU).put("smbCount", r.smbCount)
        .put("basalPercent", r.basalPercentOfProfile ?: JSONObject.NULL).put("verdict", r.verdict.name).put("why", r.why)

    private fun fromJson(j: JSONObject) = ActivityOutcomeMath.Report(
        j.getLong("start"), j.getString("mode"), j.getInt("duration"), j.getInt("tail"),
        if (j.isNull("hr")) null else ActivityOutcomeMath.HrProfile.fromNote(j.getString("hr")),
        if (j.isNull("min")) null else j.getInt("min"), if (j.isNull("mean")) null else j.getInt("mean"),
        j.getInt("below70"), j.getInt("below55"), j.getInt("above180"), j.getInt("carbs"), j.getDouble("bolus"), j.getDouble("smb"), j.getInt("smbCount"),
        if (j.isNull("basalPercent")) null else j.getInt("basalPercent"),
        runCatching { ActivityOutcomeMath.Verdict.valueOf(j.getString("verdict")) }.getOrDefault(ActivityOutcomeMath.Verdict.UNKNOWN), j.optString("why")
    )

    private companion object {

        const val KEY = "aimi_activity_reports_v1"
        const val NOTE_PREFIX = "AIMI_ACTIVITY_V2 "
        const val MAX_REPORTS = 60
        /** Sessions whose tail ended this long ago are still reported (the phone may have been away). */
        const val LOOK_BACK_MS = 36 * 60 * 60_000L
    }
}
