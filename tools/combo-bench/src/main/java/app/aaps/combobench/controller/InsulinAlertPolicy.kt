package app.aaps.combobench.controller

import app.aaps.pump.combowatch.regulation.InsulinEffectiveness
import org.json.JSONObject

/**
 * When the owner is told that insulin is not acting, and when the telling stops.
 *
 * Told once; again an hour later while it goes on; at once when a suspicion hardens into a
 * verdict; and not at all while the owner has asked for quiet (a set just changed, a mode just
 * switched on, "I know"). The notice is taken down when glucose is back under [CLEAR_BELOW_MGDL].
 * The state is a JSON object the store keeps; the rules live here, where they can be tested.
 */
internal object InsulinAlertPolicy {

    class Step(val notify: Boolean, val clear: Boolean, val state: JSONObject)

    fun onVerdict(state: JSONObject, verdict: InsulinEffectiveness.Verdict, nowEpochMs: Long): Step {
        val next = JSONObject(state.toString())
        if (verdict.level == InsulinEffectiveness.Level.NONE) {
            val shown = next.optLong(KEY_NOTIFIED_AT) > 0L
            val clear = shown && verdict.bgMgdl in 1 until CLEAR_BELOW_MGDL
            if (clear) {
                next.remove(KEY_NOTIFIED_AT)
                next.remove(KEY_NOTIFIED_LEVEL)
            }
            return Step(notify = false, clear = clear, state = next)
        }
        next.put(KEY_VERDICT_TEXT, verdict.text).put(KEY_VERDICT_AT, nowEpochMs).put(KEY_VERDICT_LEVEL, verdict.level.name)
        val quiet = nowEpochMs < next.optLong(KEY_QUIET_UNTIL)
        val notifiedAt = next.optLong(KEY_NOTIFIED_AT)
        val due = notifiedAt == 0L || nowEpochMs - notifiedAt >= REPEAT_MS
        val hardened = verdict.level == InsulinEffectiveness.Level.STRONG &&
            next.optString(KEY_NOTIFIED_LEVEL) == InsulinEffectiveness.Level.SUSPECT.name
        val notify = !quiet && (due || hardened)
        if (notify) next.put(KEY_NOTIFIED_AT, nowEpochMs).put(KEY_NOTIFIED_LEVEL, verdict.level.name)
        return Step(notify, clear = false, state = next)
    }

    /** The owner asked for quiet for [minutes], with [why] on record. */
    fun quiet(state: JSONObject, nowEpochMs: Long, minutes: Int, why: String): JSONObject =
        JSONObject(state.toString()).put(KEY_QUIET_UNTIL, nowEpochMs + minutes * 60_000L).put(KEY_QUIET_WHY, why)

    const val KEY_QUIET_UNTIL = "quietUntil"
    const val KEY_QUIET_WHY = "quietWhy"
    const val KEY_NOTIFIED_AT = "notifiedAt"
    const val KEY_NOTIFIED_LEVEL = "notifiedLevel"
    const val KEY_VERDICT_TEXT = "verdictText"
    const val KEY_VERDICT_AT = "verdictAt"
    const val KEY_VERDICT_LEVEL = "verdictLevel"
    const val KEY_SET_CHANGED_AT = "setChangedAt"
    const val KEY_SET_CHANGED_BG = "setChangedBg"

    /** Said again this much later while it goes on. */
    const val REPEAT_MS = 60 * 60_000L

    /** Glucose back under this takes the notice down. */
    const val CLEAR_BELOW_MGDL = 160
}
