package app.aaps.core.objects.trials

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rules that are on trial: switched on at the owner's word, marked as trials wherever they act, and easy to
 * switch off again - so that if something goes wrong it is plain what to take back.
 *
 * Each rule has a key, a name the owner sees, and a switch. Every time a rule acts it leaves an event here:
 * what it did, in one sentence. The main screen shows the rules, their switches and their last events; the
 * loop's reason carries a «🧪 Проба» line for the run in question; the watch gets the same events with every
 * lease and shows them in its journal. Nothing here decides anything: the rules themselves live where they act.
 *
 * Kept in a preferences file of its own, so that it is the same object for the loop, the pump driver and the
 * main screen, which do not know each other.
 */
class TrialRules private constructor(context: Context) {

    /** What the early warning does when it fires. */
    enum class WarnMode { OFF, RECORD, BUZZ }

    data class Event(val key: String, val atEpochMs: Long, val text: String)

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The first microbolus on a rise that one reading alone shows is held to half a unit. */
    var capEnabled: Boolean
        get() = prefs.getBoolean(PREF_CAP, true)
        set(value) { prefs.edit().putBoolean(PREF_CAP, value).apply(); noteSwitch(KEY_CAP, if (value) "включено" else "выключено") }

    /** The loop's own forecast of a low: recorded only, or also a buzz on the wrist, or nothing at all. */
    var warnMode: WarnMode
        get() = runCatching { WarnMode.valueOf(prefs.getString(PREF_WARN, null) ?: DEFAULT_WARN_MODE.name) }.getOrDefault(DEFAULT_WARN_MODE)
        set(value) {
            prefs.edit().putString(PREF_WARN, value.name).apply()
            noteSwitch(KEY_WARN, when (value) { WarnMode.OFF -> "выключено"; WarnMode.RECORD -> "только запись"; WarnMode.BUZZ -> "запись и жужжание на часах" })
        }

    /**
     * AIMI's own figure for "this person's basal" is held to the mean of the profile's basal instead of being
     * multiplied by what the basal learner last learned; see BasalEstimateAnchor. On trial since 16.11.2026.
     */
    var anchorEnabled: Boolean
        get() = prefs.getBoolean(PREF_ANCHOR, true)
        set(value) { prefs.edit().putBoolean(PREF_ANCHOR, value).apply(); noteSwitch(KEY_ANCHOR, if (value) "включено" else "выключено") }

    /** How a rule stands right now, in one sentence, for the screen; not an event - a rule that acts on every run says it here. */
    fun state(key: String): String? = prefs.getString(PREF_STATE + key, null)

    fun setState(key: String, text: String) {
        if (state(key) != text) prefs.edit().putString(PREF_STATE + key, text).apply()
    }

    private fun noteSwitch(key: String, state: String) = add(key, System.currentTimeMillis(), "${name(key)}: $state владельцем")

    /** A rule acted, or was switched: one sentence, kept for the screens. */
    @Synchronized fun add(key: String, atEpochMs: Long, text: String) {
        val kept = (events() + Event(key, atEpochMs, text)).takeLast(MAX_EVENTS)
        prefs.edit().putString(PREF_EVENTS, JSONArray().apply { kept.forEach { put(JSONObject().put("key", it.key).put("at", it.atEpochMs).put("text", it.text)) } }.toString()).apply()
    }

    /** Oldest first. */
    @Synchronized fun events(sinceEpochMs: Long = 0L): List<Event> = runCatching {
        val array = JSONArray(prefs.getString(PREF_EVENTS, "[]"))
        List(array.length()) { array.getJSONObject(it) }.map { Event(it.getString("key"), it.getLong("at"), it.getString("text")) }
            .filter { it.atEpochMs >= sinceEpochMs }
    }.getOrDefault(emptyList())

    /** How many times a rule acted since [sinceEpochMs]; switching it on or off is not acting. */
    fun timesActed(key: String, sinceEpochMs: Long): Int = events(sinceEpochMs).count { it.key == key && !it.text.endsWith("владельцем") }

    companion object {

        const val KEY_CAP = "unconfirmed-rise-cap"
        const val KEY_WARN = "low-forecast-warning"
        const val KEY_ANCHOR = "basal-estimate-anchor"

        /** The day both went on trial. */
        const val SINCE = "15.11.2026"

        /**
         * Recorded, not buzzed, until the owner says otherwise: replayed over four weeks the warning would have
         * fired 66 times and a reading below 70 followed 8 of them; at night 14 times, 3 of them followed by a low.
         */
        val DEFAULT_WARN_MODE = WarnMode.RECORD

        fun name(key: String): String = when (key) {
            KEY_CAP  -> "Первая микродоза на неподтверждённом скачке не больше 0,5 ЕД"
            KEY_WARN -> "Раннее предупреждение о низком прогнозе"
            KEY_ANCHOR -> "Внутренний базал AIMI держится за средний базал профиля"
            else     -> key
        }

        private const val FILE = "trial_rules"
        private const val PREF_CAP = "cap_enabled"
        private const val PREF_WARN = "warn_mode"
        private const val PREF_ANCHOR = "anchor_enabled"
        private const val PREF_STATE = "state_"
        private const val PREF_EVENTS = "events"
        private const val MAX_EVENTS = 60

        @Volatile private var instance: TrialRules? = null

        fun get(context: Context): TrialRules = instance ?: synchronized(this) { instance ?: TrialRules(context).also { instance = it } }
    }
}
