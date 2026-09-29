package app.aaps.combobench

import android.content.Context
import android.content.Intent
import info.nightscout.comboctl.base.toBluetoothAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit, one-at-a-time AAPS driver session on the test pump. An unclear outcome stays durably
 * locked; only a status read (which lets the driver reconcile the TBR state) can clear it.
 */
internal class ManualTherapySession(context: Context) {
    private val context = context.applicationContext
    private val files = BenchFiles(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val active = AtomicBoolean(false)
    @Volatile private var report = if (files.exists("manual-therapy.json")) files.read("manual-therapy.json") else JSONObject()

    init {
        if (report.optBoolean("active")) save(snapshot().put("active", false).put("locked", true).put("stage", "INTERRUPTED"))
    }

    fun snapshot() = JSONObject(report.toString())
    fun isActive() = active.get()
    fun isLocked() = report.optBoolean("active") || report.optBoolean("locked")
    fun isBlocked() = isActive() || isLocked()
    fun mayStart(kind: TherapySessionPolicy.Kind, otherWorkActive: Boolean) =
        TherapySessionPolicy.mayStart(kind, isActive(), isLocked(), otherWorkActive)

    fun start(request: TherapySessionPolicy.Request, configuredPump: String?, pairing: JSONObject, probe: JSONObject,
              otherWorkActive: Boolean, manageForeground: Boolean = true, onFinished: ((JSONObject) -> Unit)? = null) {
        check(BuildConfig.MANUAL_TARGET)
        check(mayStart(request.kind, otherWorkActive || probe.optBoolean("active") || probe.optBoolean("locked"))) {
            "Сеанс драйвера уже идёт или его результат не выяснен"
        }
        val address = TherapySessionPolicy.ADDRESS.toBluetoothAddress()
        ManualReconnectPolicy.validate(configuredPump, pairing.optString("pump"), pairing.optString("address"),
            pairing.optString("stage"), pairing.optBoolean("active"), pairing.optBoolean("completed"),
            pairing.optBoolean("cleanupKnown"), BenchPairingStore(context, address, TherapySessionPolicy.PUMP).hasPumpState(address))
        check(active.compareAndSet(false, true))
        val id = UUID.randomUUID().toString()
        val initial = JSONObject().put("id", id).put("at", System.currentTimeMillis()).put("pairingId", pairing.getString("id"))
            .put("kind", request.kind.name).put("percentage", request.percentage ?: JSONObject.NULL)
            .put("durationMinutes", request.durationMinutes).put("active", true).put("locked", true)
            .put("stage", "RUNNING").put("apkVersionCode", BuildConfig.VERSION_CODE)
        try {
            save(initial)
            if (manageForeground) context.startForegroundService(Intent(context, PairingForegroundService::class.java))
            scope.launch {
                val final = JSONObject(initial.toString())
                try {
                    val outcome = TherapySessionRunner(context, files).run(id, request)
                    check(outcome.result.getString("id") == id && outcome.result.getBoolean("complete"))
                    final.put("result", outcome.result).put("locked", !outcome.settled)
                        .put("stage", if (outcome.success) "COMPLETED" else "FAILED")
                } catch (e: Exception) {
                    final.put("locked", true).put("stage", "FAILED").put("errorClass", e.javaClass.simpleName)
                } finally {
                    try { save(final.put("active", false)) }
                    catch (_: Exception) { report = final.put("locked", true).put("stage", "SAVE_FAILED") }
                    finally {
                        active.set(false)
                        if (manageForeground) context.stopService(Intent(context, PairingForegroundService::class.java))
                        onFinished?.invoke(snapshot())
                    }
                }
            }
        } catch (e: Exception) {
            report = initial.put("active", false).put("locked", true).put("stage", "START_FAILED")
            runCatching { save(report) }
            active.set(false)
            if (manageForeground) context.stopService(Intent(context, PairingForegroundService::class.java))
            throw e
        }
    }

    private fun save(value: JSONObject) {
        files.write("manual-therapy.json", value)
        files.write("manual-therapy-${value.getString("id")}.json", value)
        report = JSONObject(value.toString())
    }
}
