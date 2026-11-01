package app.aaps.plugins.aps.loop

/**
 * A temp basal right after a manual bolus is stacking, not dosing: the bolus has not started to act,
 * the forecast still sees the meal's rise, and the algorithm asks for 300–400 % on top. On 01.11.2026
 * that happened at 11:33 (2.9 U/h) and 18:20 (4.2 U/h), each time followed by carbohydrates to stop
 * the fall. While a manual bolus of at least [MIN_BOLUS_U] given in the last [WINDOW_MS] is being
 * absorbed, the loop's temp basal is held at [MAX_FACTOR] of the profile's basal. Micro-boluses and
 * basal cuts are untouched.
 */
object PostBolusBasalCap {

    const val WINDOW_MS = 30 * 60_000L
    const val MIN_BOLUS_U = 0.5
    const val MAX_FACTOR = 1.5
    const val MAX_PERCENT = 150

    /** The rate to set instead of [requestedUh], or null when the request may pass as it is. */
    fun cappedRate(requestedUh: Double, profileBasalUh: Double, manualBolusU: Double): Double? {
        if (manualBolusU < MIN_BOLUS_U || profileBasalUh <= 0.0) return null
        val cap = profileBasalUh * MAX_FACTOR
        return if (requestedUh > cap + 1e-9) cap else null
    }

    /** The percentage to set instead of [requestedPercent], or null when the request may pass. */
    fun cappedPercent(requestedPercent: Int, manualBolusU: Double): Int? =
        if (manualBolusU >= MIN_BOLUS_U && requestedPercent > MAX_PERCENT) MAX_PERCENT else null

    fun note(manualBolusU: Double, from: String, to: String): String =
        "Post-bolus cap: %.2f U given by hand in the last %d min, temp basal %s → %s (≤ %d %% of profile)".format(
            java.util.Locale.US, manualBolusU, WINDOW_MS / 60_000, from, to, MAX_PERCENT
        )
}
