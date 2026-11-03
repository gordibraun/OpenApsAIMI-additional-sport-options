package app.aaps.combobench.controller

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log

/**
 * Steps from the watch's own step-counter sensor, not from Health Services: on 03.11.2026 Health
 * Services stopped delivering its Steps intervals for good after their start and end came from
 * different clocks («End timestamp mustn't be earlier than start timestamp», every five seconds,
 * for hours), while the sensor kept counting. The sensor gives a running total since boot with a
 * timestamp on the elapsed-realtime clock; the difference between two readings, over the time
 * between them, is what the detector and the sample log get. Batched up to a minute to save power.
 */
class StepCounter(
    private val context: Context,
    private val onSteps: (startEpochMs: Long, endEpochMs: Long, count: Long) -> Unit
) : SensorEventListener {

    private var lastTotal: Float? = null
    private var lastAtEpochMs = 0L

    @Volatile var registered = false
        private set

    /** True when the sensor exists and the listener is registered. */
    fun start(): Boolean {
        if (registered) return true
        val manager = context.getSystemService(SensorManager::class.java) ?: return false
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensor == null) {
            Log.w(TAG, "no step counter sensor on this watch")
            return false
        }
        registered = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, MAX_REPORT_LATENCY_US)
        Log.i(TAG, if (registered) "step counter registered: ${sensor.name}" else "step counter not registered")
        return registered
    }

    fun stop() {
        if (!registered) return
        context.getSystemService(SensorManager::class.java)?.unregisterListener(this)
        registered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER || event.values.isEmpty()) return
        val total = event.values[0]
        val at = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000L
        val previous = lastTotal
        val previousAt = lastAtEpochMs
        lastTotal = total
        lastAtEpochMs = at
        // The first reading sets the baseline; a smaller total means the count started over.
        if (previous == null || total < previous || previousAt <= 0L || at <= previousAt) return
        val delta = (total - previous).toLong()
        if (delta > 0L) onSteps(previousAt, at, delta)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {

        const val TAG = "ComboSteps"
        const val MAX_REPORT_LATENCY_US = 60_000_000
    }
}
