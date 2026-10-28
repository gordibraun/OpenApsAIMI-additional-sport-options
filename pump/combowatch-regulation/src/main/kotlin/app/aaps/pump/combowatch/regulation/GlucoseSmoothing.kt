package app.aaps.pump.combowatch.regulation

import app.aaps.core.data.iob.InMemoryGlucoseValue
import kotlin.math.abs

/**
 * The phone's "average smoothing" on the watch.
 *
 * Every reading with evenly spaced neighbours becomes the mean of itself and the two; the newest
 * and the oldest stay as read. This is the three-point mean the phone's AvgSmoothing plugin applies
 * to its bucketed data before the loop reads rates of change, so that "falling by five" is judged on
 * the same values on both. The newest value, the one shown, is still the sensor's own.
 */
object GlucoseSmoothing {

    /** Sets [InMemoryGlucoseValue.smoothed] on the inner readings of a newest-first list; see the phone's AvgSmoothingPlugin. */
    fun smooth(newestFirst: MutableList<InMemoryGlucoseValue>): MutableList<InMemoryGlucoseValue> {
        if (newestFirst.lastIndex < MIN_LAST_INDEX) return newestFirst
        for (i in newestFirst.lastIndex - 1 downTo 1) {
            val newer = newestFirst[i - 1]
            val here = newestFirst[i]
            val older = newestFirst[i + 1]
            val evenlySpaced = abs((newer.timestamp - here.timestamp) - (here.timestamp - older.timestamp)) < SPACING_TOLERANCE_MS
            if (valid(newer.value) && valid(here.value) && valid(older.value) && evenlySpaced)
                here.smoothed = (newer.value + here.value + older.value) / 3.0
        }
        return newestFirst
    }

    /** For Dexcom, below 39 is LOW and above 401 HI: not numbers to average. */
    private fun valid(mgdl: Double): Boolean = mgdl > 39.0 && mgdl < 401.0

    /** Fewer readings than this (five) are left alone, as on the phone. */
    private const val MIN_LAST_INDEX = 4
    private const val SPACING_TOLERANCE_MS = 30_000L
}
