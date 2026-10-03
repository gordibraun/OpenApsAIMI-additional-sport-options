package app.aaps.combobench.controller

import app.aaps.pump.combowatch.regulation.GlucoseReading

/**
 * When the glucose sensor is due to talk, and so when the pump must be left alone.
 *
 * The watch has one radio for both. The Dexcom transmitter wakes once every five minutes, to the
 * second, advertises for about fifteen seconds, and gives the watch one chance to connect; a
 * Bluetooth Classic session with the pump at that moment makes that chance fail, and the reading
 * is lost until the next window. The transmitter keeps its rhythm whether or not a reading was
 * caught, so from the readings the watch has received it knows when the next window falls.
 *
 * A pump session takes about a minute. This says whether starting one now would run into the
 * next window, and until when to wait if so. Nothing here reads a clock.
 */
object SensorWindows {

    /** The transmitter's period. */
    const val PERIOD_MS = 5 * 60_000L

    /** How long a pump session is allowed for when deciding whether it fits before a window: a first connection with its reads. */
    const val PUMP_SESSION_MS = 80_000L

    /** From the predicted window start, how long the sensor is left alone: connect, read, disconnect. */
    const val WINDOW_MS = 30_000L

    /** Without a reading in this long the rhythm is not trusted any more. */
    const val MAX_PREDICTION_AGE_MS = 3 * 60 * 60_000L

    /**
     * The moment the pump may be started, or null if it may be started now.
     *
     * @param readings sensor readings received by this watch; their times are when the transmitter spoke.
     */
    fun waitUntil(nowEpochMs: Long, readings: List<GlucoseReading>): Long? {
        val last = readings.maxOfOrNull { it.atEpochMs } ?: return null
        if (nowEpochMs - last > MAX_PREDICTION_AGE_MS) return null
        // The reading was the end of its window; the first window that a session started now
        // could still touch is the next one not yet over.
        var next = last + PERIOD_MS
        while (next + WINDOW_MS <= nowEpochMs) next += PERIOD_MS
        val sessionEnd = nowEpochMs + PUMP_SESSION_MS
        return if (sessionEnd > next && nowEpochMs < next + WINDOW_MS) next + WINDOW_MS else null
    }
}
