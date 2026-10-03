package app.aaps.wear.combo

import android.content.Context
import org.json.JSONObject

/**
 * Whether the phone drives the pump through this watch - as its last lease said.
 *
 * The relay passes leases on without reading them, with this one exception: it notes whether the
 * phone named the watch as the controller. The watch's own entry screens need to know, because in
 * that mode carbohydrates go to the controller on this watch and nobody waits for the phone; in
 * the phone's own mode everything stays exactly as it always was.
 */
object ComboWatchMode {

    private const val PREFS = "combo_watch_mode"
    private const val KEY_CONTROLLER_IS_WATCH = "controller_is_watch"
    private const val KEY_SEEN_AT = "seen_at"

    fun remember(context: Context, leaseJson: String) {
        val controllerIsWatch = runCatching { JSONObject(leaseJson).optBoolean("controllerIsWatch", false) }.getOrDefault(false)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CONTROLLER_IS_WATCH, controllerIsWatch)
            .putLong(KEY_SEEN_AT, System.currentTimeMillis())
            .apply()
    }

    fun isWatchMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CONTROLLER_IS_WATCH, false)
}
