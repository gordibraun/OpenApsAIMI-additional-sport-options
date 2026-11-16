package app.aaps.core.objects.basaltuner

import android.content.Context
import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.defs.determineCorrectBasalSize
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.objects.profile.ProfileHours
import app.aaps.core.objects.profile.RunningProfileEditor
import app.aaps.core.objects.trials.TrialRules
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The basal tuner: once a day, after the night is in the records, it measures the need for basal in the hours
 * without food ([BasalNeed]), compares it with the running profile ([BasalTunerRules]) and, when an hour of the
 * profile stands clearly apart from what was measured, puts a proposal in front of the owner - on the main
 * screen, with the reasons written out, and as a quiet line among the rules on trial (which the watch shows too).
 *
 * It changes nothing by itself. A proposal becomes the profile only when the owner presses «Применить»; then the
 * hours are saved into the running local profile and the profile is activated the usual way, which writes the
 * pump (through the watch, when the watch is its controller).
 *
 * On trial since 16.11.2026, with a switch among the rules on trial.
 */
@Singleton
class BasalTuner @Inject constructor(
    private val context: Context,
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val activePlugin: ActivePlugin,
    private val profileEditor: RunningProfileEditor,
    private val uiInteraction: UiInteraction,
    private val aapsLogger: AAPSLogger
) {

    private val prefs by lazy { context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
    private val running = AtomicBoolean(false)

    private val trials get() = TrialRules.get(context)

    val enabled: Boolean get() = runCatching { trials.tunerEnabled }.getOrDefault(false)

    // ---- what is remembered -----------------------------------------------------------------------------------------

    fun lastRun(): BasalTunerState.Run? = read(PREF_RUN) { BasalTunerState.Run.fromJson(JSONObject(it)) }

    /** The proposal that awaits the owner's answer, if there is one. */
    fun pending(): BasalTunerState.Proposal? = read(PREF_PENDING) { BasalTunerState.Proposal.fromJson(JSONObject(it)) }?.takeIf { it.status == BasalTunerState.Status.PENDING }

    /** Proposals already answered or overtaken, newest first. */
    fun history(): List<BasalTunerState.Proposal> = read(PREF_HISTORY) { text -> JSONArray(text).let { array -> List(array.length()) { BasalTunerState.Proposal.fromJson(array.getJSONObject(it)) } } } ?: emptyList()

    /** The profile the tuner started from, by the hour. */
    fun reference(): List<Double>? = read(PREF_REFERENCE) { text -> JSONArray(text).let { array -> List(array.length()) { array.getDouble(it) } } }?.takeIf { it.size == 24 }

    private fun rest(now: Long): BasalTunerState.Rest = (read(PREF_REST) { BasalTunerState.Rest.fromJson(JSONObject(it)) } ?: BasalTunerState.Rest.NONE).at(now)

    private fun <T> read(key: String, parse: (String) -> T): T? = prefs.getString(key, null)?.let { text -> runCatching { parse(text) }.getOrNull() }

    private fun file(proposal: BasalTunerState.Proposal) {
        val kept = (listOf(proposal) + history()).take(HISTORY_KEPT)
        prefs.edit().putString(PREF_HISTORY, JSONArray().apply { kept.forEach { put(it.toJson()) } }.toString()).remove(PREF_PENDING).apply()
    }

    // ---- the daily run ----------------------------------------------------------------------------------------------

    /** From every run of the loop: cheap unless today's calculation is due, and then the work goes to a thread of its own. */
    fun onLoopRun(nowEpochMs: Long) {
        if (!enabled) return
        if (!BasalTunerRules.dueForDailyRun(nowEpochMs, prefs.getLong(PREF_RUN_AT, 0L), TimeZone.getDefault())) return
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                calculate(nowEpochMs, announce = true)
            } catch (t: Throwable) {
                aapsLogger.error(LTag.APS, "Basal tuner: the daily calculation failed", t)
            } finally {
                running.set(false)
            }
        }, "basal-tuner").start()
    }

    class Calculation(val run: BasalTunerState.Run, val proposal: BasalTunerState.Proposal?)

    /** Measures and judges without remembering anything: for a look from adb, and the first half of [calculate]. */
    fun measure(nowEpochMs: Long): Calculation {
        val zone = TimeZone.getDefault()
        val from = nowEpochMs - (BasalNeed.DAYS + 2) * DAY_MS
        val source = activePlugin.activeProfileSource
        val editing = source.currentProfile()
        val runningProfile = profileFunction.getProfile(nowEpochMs)
        val profile = editing?.let { ProfileHours.hourly(it.basal) }
        val note = when {
            runningProfile == null || editing == null || profile == null -> "нет работающего локального профиля"
            editing.name != profileFunction.getOriginalProfileName()        -> "в редакторе открыт профиль «${editing.name}», а работает «${profileFunction.getOriginalProfileName()}»"
            runningProfile.percentage != 100 || runningProfile.timeshift != 0 -> "профиль запущен с процентом или сдвигом — предложений нет"
            else                                                            -> null
        }
        val profiles = (listOfNotNull(persistenceLayer.getEffectiveProfileSwitchActiveAt(from)) + persistenceLayer.getEffectiveProfileSwitchesFromTimeToTime(from, nowEpochMs, true))
            .distinctBy { it.timestamp }.filter { it.isValid }
            .mapNotNull { eps -> hourlyFromBlocks(eps.basalBlocks.map { it.duration to it.amount })?.let { BasalNeed.ProfileBasal(eps.timestamp, it) } }
        check(profiles.isNotEmpty()) { "no profile in the records for the last ${BasalNeed.DAYS} days" }
        val input = BasalNeed.Input(
            readings = persistenceLayer.getBgReadingsDataFromTimeToTime(from, nowEpochMs, true).filter { it.isValid }.map { BasalNeed.Reading(it.timestamp, it.value) },
            doses = persistenceLayer.getBolusesFromTimeToTime(from, nowEpochMs, true).filter { it.isValid && it.type != BS.Type.PRIMING }
                .map { BasalNeed.Dose(it.timestamp, it.amount, it.type == BS.Type.SMB) },
            carbs = persistenceLayer.getCarbsFromTimeToTimeExpanded(from, nowEpochMs, true).filter { it.isValid }.map { BasalNeed.Carbs(it.timestamp, it.amount) },
            tempBasals = persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(from, nowEpochMs, true).filter { it.isValid }
                .map { BasalNeed.TempBasal(it.timestamp, it.duration, it.rate, it.isAbsolute) },
            profiles = profiles,
            toEpochMs = nowEpochMs,
            diaMinutes = ((runningProfile?.dia ?: 5.0) * 60.0).roundToInt().coerceIn(180, 600),
            peakMinutes = runCatching { activePlugin.activeInsulin.peak }.getOrDefault(55).coerceIn(30, 120),
            zone = zone,
            tempBasalsFiledShortBeforeEpochMs = TEMP_BASALS_FILED_SHORT_BEFORE
        )
        val result = BasalNeed.estimate(input)
        val shown = profile ?: profiles.last().hourlyUph
        val rest = rest(nowEpochMs)
        val judged =
            if (note != null) result.hours.map { BasalTunerRules.Judged(it.hour, if (it.samples == 0) BasalTunerRules.Verdict.NO_DATA else BasalTunerRules.Verdict.WITHIN, null) }
            else BasalTunerRules.judge(result.hours, shown, reference() ?: shown, rest.until, rest.declined, result.recentLowClockHours, nowEpochMs)
        val rows = result.hours.zip(judged).map { (e, j) ->
            BasalTunerState.Row(e.hour, shown[e.hour], e.samples, e.nights, e.needUph, e.q1Uph, e.q3Uph, e.actedUph, e.slopeMgdlPerHour, e.glucoseMgdl, j.verdict, j.change?.toUph)
        }
        val run = BasalTunerState.Run(nowEpochMs, result.fromEpochMs, result.toEpochMs, result.days, result.totalDailyU, result.sensitivityMgdlPerU, result.cleanHours, result.refused, rows, note)
        val changes = judged.mapNotNull { it.change }
        val proposal = if (changes.isEmpty()) null else {
            val after = shown.mapIndexed { hour, rate -> changes.firstOrNull { it.hour == hour }?.toUph ?: rate }
            BasalTunerState.Proposal(
                createdAtEpochMs = nowEpochMs, changes = changes, headline = BasalTunerRules.headline(changes),
                reasons = changes.sortedBy { it.hour }.map { change -> BasalTunerRules.reason(change, result.hours[change.hour], result.days) },
                totalBeforeU = shown.sum(), totalAfterU = after.sum()
            )
        }
        return Calculation(run, proposal)
    }

    /**
     * Measures, judges and remembers. A proposal that differs from the one already waiting takes its place; with
     * [announce] (the daily run, not the owner's own «Пересчитать») it is also announced - within the owner's bounds.
     */
    @Synchronized
    fun calculate(nowEpochMs: Long, announce: Boolean): Calculation {
        val calculation = measure(nowEpochMs)
        val edit = prefs.edit().putString(PREF_RUN, calculation.run.toJson().toString()).putLong(PREF_RUN_AT, nowEpochMs)
        if (reference() == null && calculation.run.note == null) edit.putString(PREF_REFERENCE, JSONArray(calculation.run.rows.map { it.profileUph }).toString())
        edit.apply()
        val waiting = pending()
        val proposal = calculation.proposal
        when {
            proposal == null && waiting != null                            -> file(waiting.decided(BasalTunerState.Status.OUTDATED, nowEpochMs, "после пересчёта менять нечего"))
            proposal != null && waiting?.sameChangesAs(proposal.changes) == true -> Unit // the owner already has it in front of him
            proposal != null                                               -> {
                waiting?.let { file(it.decided(BasalTunerState.Status.OUTDATED, nowEpochMs, "заменено новым расчётом")) }
                prefs.edit().putString(PREF_PENDING, proposal.toJson().toString()).apply()
                val text = "Подстройщик базала предлагает: ${proposal.headline}. Причины и кнопка «Применить» — главный экран телефона, «📈 Базал»"
                if (announce && BasalTunerRules.mayNotify(nowEpochMs, prefs.getLong(PREF_NOTIFIED_AT, 0L), TimeZone.getDefault())) {
                    prefs.edit().putLong(PREF_NOTIFIED_AT, nowEpochMs).apply()
                    runCatching { trials.add(TrialRules.KEY_TUNER, nowEpochMs, text) }
                    runCatching { uiInteraction.addNotificationValidFor(NOTIFICATION, "📈 $text", Notification.INFO, NOTICE_VALID_MINUTES) }
                }
            }
        }
        aapsLogger.info(
            LTag.APS,
            "Basal tuner: ${calculation.run.cleanHours} clean hours of ${calculation.run.days} days (not taken: ${calculation.run.refused}); " +
                (calculation.run.note?.let { "no proposal: $it" } ?: proposal?.let { "proposes ${it.headline}" } ?: "nothing to change") +
                "; by the hour: " + calculation.run.rows.filter { it.samples > 0 }.joinToString(" ") { "%02d:%.2f/%.2f(%d)%s".format(java.util.Locale.US, it.hour, it.profileUph, it.needUph, it.samples, it.toUph?.let { to -> "->$to" } ?: "") }
        )
        return calculation
    }

    // ---- the owner's answer -----------------------------------------------------------------------------------------

    /**
     * Makes the waiting proposal the profile: saves the hours into the running local profile and activates it.
     * @return what happened, for the owner
     */
    @Synchronized
    fun apply(nowEpochMs: Long): String {
        val proposal = pending() ?: return "Предложения нет."
        return try {
            val pump = activePlugin.activePump.pumpDescription.pumpType
            val plan = profileEditor.plan(RunningProfileEditor.Edit(basal = proposal.changes.associate { it.hour to it.toUph })) { rate -> abs(pump.determineCorrectBasalSize(rate) - rate) < 1e-6 }
            check(proposal.changes.all { plan.basalBefore[it.hour] == it.fromUph }) { "профиль изменился после расчёта — нажмите «Пересчитать»" }
            val switched = profileEditor.apply(plan, "Подстройщик базала: ${proposal.headline}", nowEpochMs)
            check(switched) { "профиль сохранён, но переключение не создано" }
            file(proposal.decided(BasalTunerState.Status.APPLIED, nowEpochMs))
            prefs.edit().putString(PREF_REST, rest(nowEpochMs).after(proposal.changes.map { it.hour }, nowEpochMs + BasalTunerRules.SETTLE_DAYS * DAY_MS, declined = false).toJson().toString()).apply()
            runCatching { trials.add(TrialRules.KEY_TUNER, nowEpochMs, "Подстройщик базала: применено владельцем — ${proposal.headline}") }
            runCatching { uiInteraction.dismissNotification(NOTIFICATION) }
            aapsLogger.info(LTag.APS, "Basal tuner: applied by the owner: ${proposal.headline}")
            "Профиль сохранён и активирован: ${proposal.headline}. Запись в помпу идёт несколько минут; эти часы подстройщик теперь не трогает ${BasalTunerRules.SETTLE_DAYS} дня."
        } catch (t: Throwable) {
            aapsLogger.error(LTag.APS, "Basal tuner: the proposal was not applied", t)
            "Не применено: ${t.message}"
        }
    }

    /** The owner does not want it: the hours are left alone for a week. */
    @Synchronized
    fun decline(nowEpochMs: Long) {
        val proposal = pending() ?: return
        file(proposal.decided(BasalTunerState.Status.DECLINED, nowEpochMs))
        prefs.edit().putString(PREF_REST, rest(nowEpochMs).after(proposal.changes.map { it.hour }, nowEpochMs + BasalTunerRules.DECLINED_REST_DAYS * DAY_MS, declined = true).toJson().toString()).apply()
        runCatching { trials.add(TrialRules.KEY_TUNER, nowEpochMs, "Подстройщик базала: отклонено владельцем — ${proposal.headline}") }
        runCatching { uiInteraction.dismissNotification(NOTIFICATION) }
        aapsLogger.info(LTag.APS, "Basal tuner: declined by the owner: ${proposal.headline}")
    }

    companion object {

        /** The rate in each clock hour from a profile's blocks (duration in ms, U/h); null when they do not make a day of whole hours. */
        fun hourlyFromBlocks(blocks: List<Pair<Long, Double>>): List<Double>? {
            val hourly = ArrayList<Double>(24)
            for ((duration, rate) in blocks) {
                if (duration <= 0L || duration % HOUR_MS != 0L) return null
                repeat((duration / HOUR_MS).toInt()) { hourly += rate }
            }
            return hourly.takeIf { it.size == 24 }
        }

        /** 16.11.2026 09:39:33 (UTC+3), when the phone began filing temp basals at their true length (commit 5ef01a672d). */
        const val TEMP_BASALS_FILED_SHORT_BEFORE = 1_794_811_173_000L

        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L
        private const val FILE = "basal_tuner"
        private const val PREF_RUN = "run"
        private const val PREF_RUN_AT = "run_at"
        private const val PREF_PENDING = "pending"
        private const val PREF_HISTORY = "history"
        private const val PREF_REFERENCE = "reference"
        private const val PREF_REST = "rest"
        private const val PREF_NOTIFIED_AT = "notified_at"
        private const val HISTORY_KEPT = 20
        private const val NOTIFICATION = 9743
        private const val NOTICE_VALID_MINUTES = 12 * 60
    }
}
