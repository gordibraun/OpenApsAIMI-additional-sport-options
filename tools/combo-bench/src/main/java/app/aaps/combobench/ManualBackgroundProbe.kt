package app.aaps.combobench

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One explicitly armed deferred session that starts with the screen off; never resumes after process death.
 * [MODE_HANDSHAKE] is the control-only greeting, [MODE_STOP_DELIVERY] the AAPS driver session that sets a 0 % TBR.
 */
internal class ManualBackgroundProbe(context: Context) {
    private val context = context.applicationContext
    private val files = BenchFiles(context)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private var report = if (files.exists("manual-background.json")) files.read("manual-background.json") else JSONObject()

    init {
        if (report.optBoolean("active")) {
            alarms.cancel(pending(report.getString("id")))
            save(JSONObject(report.toString()).put("active", false).put("stage", "INTERRUPTED"))
        }
    }

    @Synchronized fun snapshot() = JSONObject(report.toString())
    @Synchronized fun isActive() = report.optBoolean("active")
    @Synchronized fun mode(): String = report.optString("mode", MODE_HANDSHAKE)

    @Synchronized fun arm(runtime: ManualPumpRuntime, mode: String = MODE_HANDSHAKE) {
        check(mode == MODE_HANDSHAKE || mode == MODE_STOP_DELIVERY)
        check(BuildConfig.MANUAL_TARGET && !isActive() && !runtime.control.isBlocked() && !runtime.therapy.isActive())
        check(!runtime.reconnect.isActive() && !runtime.reconnect.snapshot().optBoolean("locked"))
        if (mode == MODE_STOP_DELIVERY) check(!runtime.therapy.isLocked()) { "Результат прошлого сеанса драйвера не выяснен" }
        val pairing = runtime.pairing.snapshot()
        check(pairing.optString("stage") == "PAIRED" && !pairing.optBoolean("active") && pairing.optBoolean("cleanupKnown"))
        check(runtime.target()?.pump == ManualReconnectPolicy.PUMP)
        val now = SystemClock.elapsedRealtime()
        val initial = JSONObject().put("id", UUID.randomUUID().toString()).put("at", System.currentTimeMillis())
            .put("pairingId", pairing.getString("id")).put("active", true).put("stage", "WAITING").put("mode", mode)
            .put("armedElapsedMs", now).put("armedUptimeMs", SystemClock.uptimeMillis())
            .put("dueElapsedMs", now + BackgroundProbePolicy.DELAY_MS).put("apkVersionCode", BuildConfig.VERSION_CODE)
            .put("screenEvents", JSONArray()).put("waitingWakeLockHeldByBench", false)
        save(initial)
        noteScreen(power.isInteractive)
        try { context.startForegroundService(Intent(context, BackgroundProbeService::class.java)) }
        catch (e: Exception) { finish("START_FAILED", e.javaClass.simpleName); throw e }
    }

    @Synchronized fun installAlarm() {
        check(report.optString("stage") == "WAITING")
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            report.getLong("dueElapsedMs"), pending(report.getString("id")))
    }

    @Synchronized fun noteScreen(interactive: Boolean) {
        if (!isActive()) return
        val next = snapshot()
        next.getJSONArray("screenEvents").put(JSONObject().put("interactive", interactive)
            .put("elapsedMs", SystemClock.elapsedRealtime()))
        if (interactive) next.remove("screenOffSinceElapsedMs")
        else next.put("screenOffSinceElapsedMs", SystemClock.elapsedRealtime())
        save(next)
    }

    @Synchronized fun trigger(id: String) {
        if (id != report.optString("id") || report.optString("stage") != "WAITING") return
        val runtime = ManualPumpRuntime.get(context)
        val now = SystemClock.elapsedRealtime()
        if (!BackgroundProbePolicy.mayRun(report.optString("stage"), report.optString("id"), id,
                report.optLong("dueElapsedMs"), now, BackgroundProbeService.running,
                report.optString("pairingId") == runtime.pairing.snapshot().optString("id"))) {
            finish("REJECTED")
            return
        }
        try {
            check(!runtime.control.isBlocked() && !runtime.reconnect.isActive() && !runtime.therapy.isActive())
            val next = snapshot().put("stage", "RUNNING").put("triggeredAt", System.currentTimeMillis())
                .put("triggeredElapsedMs", now).put("interactiveAtTrigger", power.isInteractive)
                .put("deviceIdleAtTrigger", power.isDeviceIdleMode).put("powerSaveAtTrigger", power.isPowerSaveMode)
                .put("alarmLateMs", now - report.getLong("dueElapsedMs"))
                .put("waitingSuspendedMs", (now - report.getLong("armedElapsedMs")) -
                    (SystemClock.uptimeMillis() - report.getLong("armedUptimeMs")))
            if (!power.isInteractive && report.has("screenOffSinceElapsedMs"))
                next.put("screenOffForMs", now - report.getLong("screenOffSinceElapsedMs"))
            save(next)
            if (mode() == MODE_STOP_DELIVERY) {
                runtime.therapy.start(TherapySessionPolicy.request(TherapySessionPolicy.Kind.STOP, STOP_MINUTES),
                    runtime.target()?.pump, runtime.pairing.snapshot(), runtime.reconnect.snapshot(),
                    otherWorkActive = false, manageForeground = false) { result -> complete(id, result) }
            } else {
                runtime.control.start(runtime.target()?.pump, runtime.pairing.snapshot(), runtime.reconnect.snapshot(),
                    secure = false, direct = false, useAapsTransport = true, manageForeground = false) { result ->
                    complete(id, result)
                }
            }
        } catch (e: Exception) { finish("FAILED", e.javaClass.simpleName) }
    }

    @Synchronized private fun complete(id: String, result: JSONObject) {
        if (report.optString("id") != id) return
        try {
            save(snapshot().put("active", false).put("stage", result.optString("stage", "FAILED"))
                .put("control", result).put("finishedAt", System.currentTimeMillis())
                .put("interactiveAtFinish", power.isInteractive))
        } finally { stop() }
    }

    @Synchronized fun cancelWaiting() {
        if (report.optString("stage") == "WAITING") finish("CANCELLED")
    }

    @Synchronized fun serviceLost() {
        if (isActive()) finish("INTERRUPTED")
    }

    private fun finish(stage: String, error: String? = null) {
        try { save(snapshot().put("active", false).put("stage", stage).put("errorClass", error)) }
        finally { stop() }
    }

    private fun stop() {
        if (report.has("id")) alarms.cancel(pending(report.getString("id")))
        context.stopService(Intent(context, BackgroundProbeService::class.java))
    }

    private fun pending(id: String) = PendingIntent.getBroadcast(context, 26,
        Intent(context, BackgroundProbeReceiver::class.java).setAction("app.aaps.combobench.BACKGROUND_PROBE")
            .setData(Uri.parse("combobench://background/$id"))
            .putExtra("id", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun save(value: JSONObject) {
        files.write("manual-background.json", value)
        files.write("manual-background-${value.getString("id")}.json", value)
        report = JSONObject(value.toString())
    }

    companion object {
        const val MODE_HANDSHAKE = "handshake"
        const val MODE_STOP_DELIVERY = "stop-delivery"
        const val STOP_MINUTES = 30
    }
}
