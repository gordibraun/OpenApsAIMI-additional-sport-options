package app.aaps.combobench

import android.content.Context
import android.content.Intent
import info.nightscout.comboctl.base.toBluetoothAddress
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Local, explicit control-only exchange. An uncertain outcome remains durably locked. */
internal class ManualControlSession(context: Context) {
    private val context = context.applicationContext
    private val files = BenchFiles(context)
    private val worker = Executors.newSingleThreadExecutor()
    private val active = AtomicBoolean(false)
    @Volatile private var report = if (files.exists("manual-control.json")) files.read("manual-control.json") else JSONObject()

    init {
        if (report.optBoolean("active")) save(snapshot().put("active", false).put("locked", true).put("stage", "INTERRUPTED"))
    }

    fun snapshot() = JSONObject(report.toString())
    fun isActive() = active.get()
    fun isBlocked() = isActive() || report.optBoolean("active") || report.optBoolean("locked")

    fun start(configuredPump: String?, pairing: JSONObject, probe: JSONObject, secure: Boolean, direct: Boolean,
              useAapsTransport: Boolean = false, manageForeground: Boolean = true,
              onFinished: ((JSONObject) -> Unit)? = null) {
        check(BuildConfig.MANUAL_TARGET && !isBlocked()) { "Control session is active or unresolved" }
        check(!probe.optBoolean("active") && !probe.optBoolean("locked")) { "Socket probe is unresolved" }
        val address = ManualReconnectPolicy.ADDRESS.toBluetoothAddress()
        ManualReconnectPolicy.validate(configuredPump, pairing.optString("pump"), pairing.optString("address"),
            pairing.optString("stage"), pairing.optBoolean("active"), pairing.optBoolean("completed"),
            pairing.optBoolean("cleanupKnown"), BenchPairingStore(context, address, ManualReconnectPolicy.PUMP).hasPumpState(address))
        check(active.compareAndSet(false, true))
        val id = UUID.randomUUID().toString()
        val initial = JSONObject().put("id", id).put("at", System.currentTimeMillis()).put("pairingId", pairing.getString("id"))
            .put("active", true).put("locked", true).put("stage", "RUNNING").put("apkVersionCode", BuildConfig.VERSION_CODE)
        try {
            save(initial)
            if (manageForeground) context.startForegroundService(Intent(context, PairingForegroundService::class.java))
            worker.execute {
                val final = JSONObject(initial.toString())
                try {
                    val settled = ControlSessionDiagnostic(context).run(id, ManualReconnectPolicy.ADDRESS,
                        ManualReconnectPolicy.PUMP, direct, secure, useAapsTransport)
                    val details = files.read("control-session.json")
                    check(details.getString("id") == id && details.getBoolean("complete"))
                    final.put("result", details).put("locked", !settled)
                        .put("stage", if (details.optBoolean("success")) "COMPLETED" else "FAILED")
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
        files.write("manual-control.json", value)
        files.write("manual-control-${value.getString("id")}.json", value)
        report = JSONObject(value.toString())
    }
}
