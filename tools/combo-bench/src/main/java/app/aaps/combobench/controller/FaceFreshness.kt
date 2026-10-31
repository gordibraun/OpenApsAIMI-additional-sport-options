package app.aaps.combobench.controller

/**
 * How old a forecast or an insulin figure may be and still stand on the face. 31.10.2026 10:18: the
 * phone had just gone quiet for a reinstall, the watch counted as alone, and the face took the
 * watch's last own forecast - from the evening before - showing «мин 98» at glucose 79 and
 * IOB 5.7 U (that forecast's 0.6 U plus every bolus since). Age limits keep that from happening.
 */
object FaceFreshness {

    /** The watch decides every reading or tick (five minutes); an own forecast older than this is from before the phone went quiet. */
    const val OWN_FORECAST_MS = 15 * 60_000L

    /** The phone's snapshot comes with every loop run; older than this, its forecast and carbs ask are history. */
    const val PHONE_FORECAST_MS = 30 * 60_000L

    /** Insulin on board decays slowly: the phone's figure plus the boluses since stays a fair base for hours. */
    const val PHONE_IOB_MS = 4 * 60 * 60_000L

    /** The two clocks may differ by a little; a figure stamped slightly in the future is still fresh. */
    const val CLOCK_TOLERANCE_MS = 3 * 60_000L

    fun ownUsable(now: Long, at: Long): Boolean = at > 0L && now - at in -CLOCK_TOLERANCE_MS..OWN_FORECAST_MS
    fun phoneForecastUsable(now: Long, madeAt: Long): Boolean = madeAt > 0L && now - madeAt in -CLOCK_TOLERANCE_MS..PHONE_FORECAST_MS
    fun phoneIobUsable(now: Long, madeAt: Long): Boolean = madeAt > 0L && now - madeAt in -CLOCK_TOLERANCE_MS..PHONE_IOB_MS
}
