package app.aaps.combobench

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import info.nightscout.comboctl.base.toBluetoothAddress
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One local socket attempt. No streams, Combo packets, state transfer or automatic retry. */
@SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
internal class ManualReconnectProbe(context: Context) {
    private val context = context.applicationContext as android.app.Application
    private val files = BenchFiles(context)
    private val worker = Executors.newSingleThreadExecutor()
    private val active = AtomicBoolean(false)
    @Volatile private var report = if (files.exists("manual-reconnect.json")) files.read("manual-reconnect.json") else JSONObject()

    init {
        if (report.optBoolean("active")) {
            report.put("active", false).put("locked", true).put("stage", "INTERRUPTED")
            save(report)
        }
    }

    fun snapshot() = JSONObject(report.toString())
    fun isActive() = active.get()

    fun canCheckDisconnect(): Boolean = !active.get() && report.optBoolean("locked") &&
        report.optString("pump") == ManualReconnectPolicy.PUMP &&
        PairingTarget.matches(ManualReconnectPolicy.ADDRESS, report.optString("address")) &&
        report.optInt("applicationBytesSent", -1) == 0 && !report.optBoolean("cleanupInterrupted") &&
        (!report.has("errorType") || (report.optString("stage") == "TIMEOUT" && report.optString("errorType") == "IOException")) &&
        ManualReconnectCleanup.canRecheck(report.optString("stage"), report.optBoolean("active"),
            report.optBoolean("socketCloseReturned"),
            report.optBoolean("receiverUnregistered", report.optInt("apkVersionCode") == 20),
            report.optBoolean("bondedAfter"), report.optString("stopReason").isNotEmpty())

    /** Read-only, local stack queries. No socket, remote request or bond operation. */
    fun checkDisconnect() {
        check(BuildConfig.MANUAL_TARGET && canCheckDisconnect())
        check(active.compareAndSet(false, true))
        val original = snapshot()
        try {
            worker.execute {
                val audit = JSONObject().put("at", System.currentTimeMillis()).put("apkVersionCode", BuildConfig.VERSION_CODE)
                try {
                    val adapter = checkNotNull(context.getSystemService(BluetoothManager::class.java).adapter)
                    val device = adapter.getRemoteDevice(ManualReconnectPolicy.ADDRESS)
                    val samples = JSONArray()
                    val states = mutableListOf<Boolean?>()
                    repeat(3) { index ->
                        if (index > 0) Thread.sleep(1_000)
                        val sample = linkState(device)
                        samples.put(sample)
                        states.add(if (sample.has("connected")) sample.getBoolean("connected") else null)
                    }
                    audit.put("samples", samples)
                    val bonded = device.bondState == BluetoothDevice.BOND_BONDED
                    val confirmed = adapter.isEnabled && bonded && ManualReconnectCleanup.disconnected(states)
                    audit.put("bonded", bonded).put("disconnectedConfirmedByStack", confirmed)
                    if (confirmed) original.put("locked", false)
                } catch (e: Exception) {
                    audit.put("errorType", e.javaClass.simpleName)
                } finally {
                    val history = original.optJSONArray("disconnectChecks") ?: JSONArray()
                    original.put("disconnectChecks", history.put(audit))
                    try { save(original) }
                    catch (_: Exception) { report = original.put("locked", true).put("stage", "SAVE_FAILED") }
                    finally { active.set(false) }
                }
            }
        } catch (e: Exception) { active.set(false); throw e }
    }

    private fun linkState(device: BluetoothDevice) = BluetoothLinkObservation.read(context, device)

    fun start(configuredPump: String?, pairing: JSONObject, secure: Boolean = false, direct: Boolean = false, hold: Boolean = false) {
        check(BuildConfig.MANUAL_TARGET)
        check(!report.optBoolean("locked")) { "Прошлая проверка не завершена; повтор заблокирован" }
        ManualReconnectPolicy.validate(configuredPump, pairing.optString("pump"), pairing.optString("address"),
            pairing.optString("stage"), pairing.optBoolean("active"), pairing.optBoolean("completed"),
            pairing.optBoolean("cleanupKnown"), BenchPairingStore(context,
                ManualReconnectPolicy.ADDRESS.toBluetoothAddress(), ManualReconnectPolicy.PUMP)
                .hasPumpState(ManualReconnectPolicy.ADDRESS.toBluetoothAddress()))
        check(active.compareAndSet(false, true)) { "Проверка уже идёт" }
        val initial = JSONObject().put("id", UUID.randomUUID().toString()).put("at", System.currentTimeMillis())
            .put("pump", ManualReconnectPolicy.PUMP).put("address", ManualReconnectPolicy.ADDRESS)
            .put("pairingId", pairing.getString("id")).put("active", true).put("locked", true)
            .put("stage", "PREPARING").put("transport", "${if (direct) "channel1" else "sdp"}-${if (secure) "secure" else "insecure"}")
            .put("timeoutMs", 8_000).put("holdMs", if (hold) 5_000 else 0)
            .put("applicationBytesSent", 0).put("apkVersionCode", BuildConfig.VERSION_CODE)
        try {
            save(initial)
            context.startForegroundService(Intent(context, PairingForegroundService::class.java))
            worker.execute { run(initial) }
        } catch (e: Exception) {
            report = JSONObject(initial.toString()).put("active", false).put("locked", true)
                .put("stage", "START_FAILED").put("errorType", e.javaClass.simpleName)
            runCatching { save(report) }
            active.set(false)
            context.stopService(Intent(context, PairingForegroundService::class.java))
            throw e
        }
    }

    private fun save(value: JSONObject) {
        files.write("manual-reconnect.json", value)
        files.write("manual-reconnect-${value.getString("id")}.json", value)
        report = JSONObject(value.toString())
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun run(result: JSONObject) {
        val events = Collections.synchronizedList(mutableListOf<JSONObject>())
        val started = SystemClock.elapsedRealtime()
        val guard = ProbeBondGuard(true)
        val deadline = ProbeDeadline()
        val timer = Executors.newScheduledThreadPool(2)
        val disconnected = AtomicBoolean(false)
        val connected = AtomicBoolean(false)
        var socket: BluetoothSocket? = null
        var receiver: BroadcastReceiver? = null
        var registered = false
        var device: BluetoothDevice? = null
        val power = context.getSystemService(PowerManager::class.java)
        val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ComboBench:ManualReconnect")
        try {
            val adapter = checkNotNull(context.getSystemService(BluetoothManager::class.java).adapter)
            check(adapter.isEnabled && !adapter.isDiscovering) { "Bluetooth выключен или ещё выполняет поиск" }
            val remote = adapter.getRemoteDevice(ManualReconnectPolicy.ADDRESS)
            device = remote
            check(remote.bondState == BluetoothDevice.BOND_BONDED) { "Существующая Bluetooth-привязка не найдена" }
            val before = linkState(remote)
            result.put("linkBefore", before)
            check(before.has("connected") && !before.getBoolean("connected")) { "Нет подтверждения отсутствия прошлого соединения" }
            val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
            val channel = when (result.getString("transport")) {
                "sdp-secure" -> remote.createRfcommSocketToServiceRecord(uuid)
                "sdp-insecure" -> remote.createInsecureRfcommSocketToServiceRecord(uuid)
                "channel1-secure" -> BluetoothDevice::class.java.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(remote, 1) as BluetoothSocket
                "channel1-insecure" -> BluetoothDevice::class.java.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(remote, 1) as BluetoothSocket
                else -> error("Unknown diagnostic transport")
            }
            socket = channel
            receiver = object : BroadcastReceiver() {
                @Suppress("DEPRECATION")
                override fun onReceive(context: Context, intent: Intent) {
                    val peer = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    if (!PairingTarget.matches(ManualReconnectPolicy.ADDRESS, peer.address)) return
                    events.add(JSONObject().put("action", intent.action).put("elapsedMs", SystemClock.elapsedRealtime() - started))
                    if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) connected.set(true)
                    if (intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) disconnected.set(true)
                    guard.observe(peer.bondState == BluetoothDevice.BOND_BONDED,
                        pairingRequested = intent.action == BluetoothDevice.ACTION_PAIRING_REQUEST,
                        bondChanged = intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    if (guard.stopReason != null) runCatching { channel.close() }
                }
            }
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter)
            registered = true
            wakeLock.acquire(35_000)
            result.put("context", JSONObject().put("interactive", power.isInteractive)
                .put("deviceIdle", power.isDeviceIdleMode).put("scanMode", adapter.scanMode)
                .put("discoveryActive", adapter.isDiscovering))
            result.put("stage", "CONNECTING")
            save(result)
            timer.schedule({ if (deadline.expireConnection()) runCatching { channel.close() } }, 8, TimeUnit.SECONDS)
            for (delay in listOf(2L, 6L)) timer.schedule({
                events.add(JSONObject().put("action", "LOCAL_LINK_STATE")
                    .put("elapsedMs", SystemClock.elapsedRealtime() - started).put("state", linkState(remote)))
            }, delay, TimeUnit.SECONDS)
            val connectStarted = SystemClock.elapsedRealtime()
            channel.connect()
            if (SystemClock.elapsedRealtime() - connectStarted >= 8_000) deadline.expireConnection()
            check(deadline.opened()) { "Connection deadline expired" }
            guard.observe(remote.bondState == BluetoothDevice.BOND_BONDED)
            check(guard.stopReason == null) { "Pairing changed" }
            check(deadline.finish())
            result.put("openedAfterMs", SystemClock.elapsedRealtime() - connectStarted)
                .put("stage", "SOCKET_OPENED_ZERO_BYTES")
            val holdStarted = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - holdStarted < result.getInt("holdMs")) {
                check(guard.stopReason == null && !disconnected.get() && channel.isConnected) { "Connection lost during hold" }
                Thread.sleep(50)
            }
            result.put("heldMs", SystemClock.elapsedRealtime() - holdStarted)
        } catch (e: Exception) {
            result.put("stage", if (deadline.expired) "TIMEOUT" else "FAILED")
                .put("errorType", e.javaClass.simpleName).put("error", e.message.orEmpty().take(300))
        } finally {
            result.put("closeRequestedAfterMs", SystemClock.elapsedRealtime() - started)
            val closed = runCatching { socket?.close() }.isSuccess
            timer.shutdownNow()
            if (socket != null && registered) {
                val until = SystemClock.elapsedRealtime() + 15_000
                try {
                    while (!disconnected.get() && SystemClock.elapsedRealtime() < until) Thread.sleep(50)
                } catch (_: InterruptedException) {
                    result.put("cleanupInterrupted", true)
                    Thread.currentThread().interrupt()
                }
            }
            val unregistered = !registered || runCatching { context.unregisterReceiver(receiver) }.isSuccess
            val bonded = runCatching { device?.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
            guard.observe(bonded)
            val locked = !closed || !unregistered || result.optBoolean("cleanupInterrupted") ||
                guard.stopReason != null || (connected.get() && !disconnected.get())
            result.put("active", false).put("locked", locked).put("bondedAfter", bonded)
                .put("receiverUnregistered", unregistered).put("disconnectObservationMs", 15_000)
                .put("socketCloseReturned", closed).put("aclConnectedObserved", connected.get())
                .put("aclDisconnectedObserved", disconnected.get()).put("stopReason", guard.stopReason)
                .put("durationMs", SystemClock.elapsedRealtime() - started)
                .put("events", synchronized(events) { JSONArray(events.toList()) })
            try { save(result) }
            catch (_: Exception) { report = JSONObject(result.toString()).put("locked", true).put("stage", "SAVE_FAILED") }
            finally {
                if (wakeLock.isHeld) wakeLock.release()
                context.stopService(Intent(context, PairingForegroundService::class.java))
                active.set(false)
            }
        }
    }
}
