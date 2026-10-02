package app.aaps.pump.combowatch.regulation

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.plugins.aps.openAPS.DeltaCalculator
import kotlin.math.abs

/** One sensor reading as the watch received it. */
data class GlucoseReading(val atEpochMs: Long, val mgdl: Double)

/**
 * Where glucose is and which way it is going, from the watch's own sensor readings.
 *
 * The three rates of change are worked out by the phone's own calculator, so "falling by five"
 * means on the watch exactly what it means in the algorithm.
 */
data class GlucoseTrend(
    val mgdl: Double,
    val atEpochMs: Long,
    /** Change per five minutes, from the last reading or two. Zero when [known] is false. */
    val delta: Double,
    /** Change per five minutes, averaged over the last quarter of an hour. */
    val shortAvgDelta: Double,
    /** Change per five minutes, averaged over the twenty-five minutes before that. */
    val longAvgDelta: Double,
    /**
     * False when no earlier reading is close enough to say which way glucose is going, or when
     * the change is more than a body can do in five minutes and so is the sensor's doing.
     */
    val known: Boolean,
    /** The fall over about the last half hour as mg/dL per hour, positive when falling; null without a reading that old. */
    val fallPerHour: Double?
) {

    companion object {

        /** Readings outside what the sensor reports as a number are not glucose values. */
        private const val MIN_MGDL = 40.0
        private const val MAX_MGDL = 400.0

        /** A change this large in five minutes is a sensor artefact, not a trend to act on. */
        private const val MAX_BELIEVABLE_DELTA = 40.0

        fun from(readings: List<GlucoseReading>, nowEpochMs: Long): GlucoseTrend? {
            val usable = readings
                .filter { it.mgdl in MIN_MGDL..MAX_MGDL && it.atEpochMs <= nowEpochMs + 60_000L }
                .distinctBy { it.atEpochMs }
                .sortedByDescending { it.atEpochMs }
            val newest = usable.firstOrNull() ?: return null

            val deltas = DeltaCalculator(QuietLogger).calculateDeltas(
                usable.mapTo(mutableListOf()) { InMemoryGlucoseValue(timestamp = it.atEpochMs, value = it.mgdl) }
            )
            fun minutesBefore(reading: GlucoseReading) = (newest.atEpochMs - reading.atEpochMs) / 60_000.0
            val hasNeighbour = usable.drop(1).any { minutesBefore(it) in 2.5..17.5 }
            val believable = abs(deltas.delta) <= MAX_BELIEVABLE_DELTA

            val halfHourAgo = usable.drop(1)
                .filter { minutesBefore(it) in 20.0..40.0 }
                .minByOrNull { abs(minutesBefore(it) - 30.0) }

            return GlucoseTrend(
                mgdl = newest.mgdl,
                atEpochMs = newest.atEpochMs,
                delta = if (hasNeighbour && believable) deltas.delta else 0.0,
                shortAvgDelta = if (hasNeighbour && believable) deltas.shortAvgDelta else 0.0,
                longAvgDelta = if (believable) deltas.longAvgDelta else 0.0,
                known = hasNeighbour && believable,
                fallPerHour = halfHourAgo?.let { (it.mgdl - newest.mgdl) * 60.0 / minutesBefore(it) }
            )
        }
    }
}
