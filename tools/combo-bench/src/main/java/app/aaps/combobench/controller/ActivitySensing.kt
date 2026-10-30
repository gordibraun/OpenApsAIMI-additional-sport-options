package app.aaps.combobench.controller

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * The Android side of [ActivityDetector]: Health Services hands over the heart rate and the state of
 * the fitness app's workout passively, i.e. without this app running a workout of its own, and
 * the controller is told. Needs BODY_SENSORS (also in the background) and ACTIVITY_RECOGNITION,
 * granted once on the watch or from adb.
 */
object ActivitySensing {

    const val TAG = "ComboSensing"
    val PERMISSIONS = arrayOf(Manifest.permission.BODY_SENSORS, Manifest.permission.ACTIVITY_RECOGNITION)
    val BACKGROUND_PERMISSIONS = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.BODY_SENSORS_BACKGROUND) else emptyArray()

    fun granted(context: Context): Boolean =
        PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun backgroundGranted(context: Context): Boolean =
        BACKGROUND_PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /**
     * Ask Health Services to call [ActivityDetectionService] with heart rate and the workout state.
     * Blocks for a few seconds at most; call off the main thread. Returns what was asked for.
     */
    fun register(context: Context): String {
        if (!granted(context)) return "нет разрешений на датчики"
        val client = HealthServices.getClient(context).passiveMonitoringClient
        val capabilities = client.getCapabilitiesAsync().get(15, TimeUnit.SECONDS)
        val types = mutableSetOf<DataType<*, *>>()
        if (DataType.HEART_RATE_BPM in capabilities.supportedDataTypesPassiveMonitoring) types += DataType.HEART_RATE_BPM
        val activityInfo = UserActivityState.USER_ACTIVITY_EXERCISE in capabilities.supportedUserActivityStates
        if (types.isEmpty() && !activityInfo) return "Health Services не даёт ни пульса, ни состояния тренировки"
        val config = PassiveListenerConfig.builder()
            .setDataTypes(types)
            .setShouldUserActivityInfoBeRequested(activityInfo)
            .build()
        client.setPassiveListenerServiceAsync(ActivityDetectionService::class.java, config).get(15, TimeUnit.SECONDS)
        val what = listOfNotNull("пульс".takeIf { types.isNotEmpty() }, "тренировки".takeIf { activityInfo }).joinToString(" и ")
        Log.i(TAG, "passive monitoring registered: $what")
        return what
    }

    fun unregister(context: Context) {
        runCatching { HealthServices.getClient(context).passiveMonitoringClient.clearPassiveListenerServiceAsync().get(10, TimeUnit.SECONDS) }
    }
}

/** Health Services calls this with new heart-rate samples and with changes of the fitness app's workout state. */
class ActivityDetectionService : PassiveListenerService() {

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val bootInstant = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
        val host = ControllerHost.get(this)
        dataPoints.getData(DataType.HEART_RATE_BPM).forEach { sample ->
            host.onHeartRate(sample.getTimeInstant(bootInstant).toEpochMilli(), sample.value.roundToInt())
        }
    }

    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
        val exercising = info.userActivityState == UserActivityState.USER_ACTIVITY_EXERCISE
        val type = info.exerciseInfo?.exerciseType?.toString()
        ControllerHost.get(this).onWorkout(info.stateChangeTime.toEpochMilli(), type, exercising)
    }

    override fun onPermissionLost() {
        ControllerHost.get(this).onSensingPermissionLost()
    }
}

/** The owner's answer to «Нагрузка?» on the notification: SPORT, WALK or NONE, for the session that started at [EXTRA_START]. */
class ActivityAnswerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val answer = intent.getStringExtra(EXTRA_ANSWER) ?: return
        val start = intent.getLongExtra(EXTRA_START, 0L)
        ControllerHost.get(context).answerSensedActivity(answer, start)
    }

    companion object {

        const val ACTION = "app.aaps.combo.sensing.ANSWER"
        const val EXTRA_ANSWER = "answer"
        const val EXTRA_START = "start"
    }
}

/**
 * Asks the owner for the sensor permissions - heart rate, then heart rate in the background, and
 * activity recognition - and registers with Health Services once they are given. Opened from the
 * controller's notice when the permissions are missing.
 */
class ActivitySensingPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when {
            !ActivitySensing.granted(this)           -> requestPermissions(ActivitySensing.PERMISSIONS, REQUEST_FOREGROUND)
            !ActivitySensing.backgroundGranted(this) -> requestPermissions(ActivitySensing.BACKGROUND_PERMISSIONS, REQUEST_BACKGROUND)
            else                                     -> done()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_FOREGROUND && ActivitySensing.granted(this) && !ActivitySensing.backgroundGranted(this) && ActivitySensing.BACKGROUND_PERMISSIONS.isNotEmpty()) {
            requestPermissions(ActivitySensing.BACKGROUND_PERMISSIONS, REQUEST_BACKGROUND)
            return
        }
        done()
    }

    private fun done() {
        Thread { runCatching { ControllerHost.get(this).startSensing() } }.start()
        finish()
    }

    private companion object {

        const val REQUEST_FOREGROUND = 71
        const val REQUEST_BACKGROUND = 72
    }
}
