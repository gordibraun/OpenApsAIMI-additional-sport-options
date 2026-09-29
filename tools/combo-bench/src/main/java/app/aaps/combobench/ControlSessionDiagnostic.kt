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
import info.nightscout.comboctl.base.*
import info.nightscout.comboctl.android.AndroidBluetoothDevice
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bench-only, one attempt. It never enables a Combo application service. */
internal class ControlSessionDiagnostic(private val context: Context) {
    @SuppressLint("MissingPermission", "DiscouragedPrivateApi", "UnspecifiedRegisterReceiverFlag")
    fun run(id: String, address: String, pump: String, direct: Boolean = true, secure: Boolean = true,
            useAapsTransport: Boolean = false): Boolean {
        ControlSessionTarget.validate(BuildConfig.FLAVOR, pump, address)
        check(!useAapsTransport || BuildConfig.MANUAL_TARGET)
        val files = BenchFiles(context)
        val start = SystemClock.elapsedRealtime()
        val result = JSONObject().put("id", id).put("at", System.currentTimeMillis())
            .put("pump", pump).put("address", address)
            .put("transport", if (useAapsTransport) "aaps-AndroidBluetoothDevice" else
                "${if (direct) "channel1" else "sdp"}-${if (secure) "secure" else "insecure"}")
            .put("mode", "control-handshake-only").put("apkVersionCode", BuildConfig.VERSION_CODE)
            .put("apkVersionName", BuildConfig.VERSION_NAME).put("complete", false)
            .put("therapyCommandsSent", 0).put("serviceActivationsSent", 0)
        val events = JSONArray()
        fun phase(name: String) = synchronized(result) {
            result.put("phase", name)
            events.put(JSONObject().put("name", name).put("elapsedMs", SystemClock.elapsedRealtime() - start))
            files.write("control-session.json", result.put("events", events))
        }
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        check(adapter != null && adapter.isEnabled)
        val device = adapter.getRemoteDevice(address)
        val guard = ProbeBondGuard(device.bondState == BluetoothDevice.BOND_BONDED)
        guard.requireExistingBond()
        val btAddress = address.toBluetoothAddress()
        val store = ControlSessionStore(BenchPairingStore(context, btAddress, pump))
        check(store.hasPumpState(btAddress) && store.getInvariantPumpData(btAddress).pumpID == pump)
        val uuid = java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val aapsDevice = if (useAapsTransport) AndroidBluetoothDevice(context, adapter, btAddress) else null
        val socket = if (useAapsTransport) null else if (direct) BluetoothDevice::class.java.getMethod(
            if (secure) "createRfcommSocket" else "createInsecureRfcommSocket", Int::class.javaPrimitiveType)
            .invoke(device, 1) as BluetoothSocket
        else if (secure) device.createRfcommSocketToServiceRecord(uuid)
        else device.createInsecureRfcommSocketToServiceRecord(uuid)
        fun closeSocket() {
            if (aapsDevice != null) aapsDevice.disconnect() else checkNotNull(socket).close()
        }
        val power = context.getSystemService(PowerManager::class.java)
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ComboBench:control-session")
        val timer = Executors.newSingleThreadScheduledExecutor()
        val expired = AtomicBoolean(false)
        val packets = ControlSessionPackets()
        val disconnectWritten = AtomicBoolean(false)
        var connectComplete = false
        var socketClosed = false
        var settled = false
        var registered = false
        val aclConnected = AtomicBoolean(false)
        val aclDisconnected = AtomicBoolean(false)
        val receiver = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(context: Context, intent: Intent) {
                val remote = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                if (remote.address != address) return
                if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) { aclConnected.set(true); phase("ACL_CONNECTED") }
                if (intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) { aclDisconnected.set(true); phase("ACL_DISCONNECTED") }
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, remote.bondState)
                val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, state)
                guard.observe(state == BluetoothDevice.BOND_BONDED,
                    pairingRequested = intent.action == BluetoothDevice.ACTION_PAIRING_REQUEST,
                    bondChanged = intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED && state != previous)
                if (guard.stopReason != null) runCatching { closeSocket() }
            }
        }
        fun checkActive() {
            guard.observe(device.bondState == BluetoothDevice.BOND_BONDED)
            check(!expired.get() && guard.stopReason == null) { "Diagnostic deadline or bond guard" }
        }
        val writer = ControlSessionWriter(SystemClock::elapsedRealtime, Thread::sleep, ::checkActive,
            packets::inspect, {
                if (aapsDevice != null) aapsDevice.blockingSend(it)
                else checkNotNull(socket).outputStream.write(it.toByteArray())
            },
            { phase("TX_${it}_ATTEMPT") }, { name ->
                if (name == "CTRL_DISCONNECT") disconnectWritten.set(true)
                phase("TX_${name}_WRITTEN")
            })
        val raw = object : BlockingComboIO(Dispatchers.IO) {
            override fun blockingSend(dataToSend: List<Byte>) = writer.send(dataToSend)
            override fun blockingReceive(): List<Byte> {
                if (aapsDevice != null) return aapsDevice.blockingReceive()
                val bytes = ByteArray(512)
                val count = checkNotNull(socket).inputStream.read(bytes)
                check(count > 0) { "Control-session socket closed" }
                return bytes.take(count)
            }
        }
        val transport = TransportLayer.IO(store, btAddress, FramedComboIO(raw)) {
            phase("RECEIVER_FAILED_${it.javaClass.simpleName}")
        }
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val totalDeadline = timer.schedule({
            expired.set(true)
            runCatching { closeSocket() }
        }, if (useAapsTransport) 60 else 30, TimeUnit.SECONDS)
        val connectDeadline = timer.schedule({
            expired.set(true)
            runCatching { closeSocket() }
        }, if (useAapsTransport) 35 else 8, TimeUnit.SECONDS)
        try {
            wake.acquire(if (useAapsTransport) 80_000L else 50_000L)
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter)
            registered = true
            if (BuildConfig.MANUAL_TARGET) {
                val before = BluetoothLinkObservation.read(context, device)
                result.put("linkBefore", before)
                check(before.has("connected") && !before.getBoolean("connected")) { "Previous connection not confirmed closed" }
            }
            phase("RFCOMM_CONNECTING")
            ProbePreparation.stopDiscovery({ adapter.isDiscovering }, { adapter.cancelDiscovery() },
                SystemClock::elapsedRealtime, Thread::sleep)
            checkActive()
            if (aapsDevice != null) aapsDevice.connect() else checkNotNull(socket).connect()
            connectDeadline.cancel(false)
            checkActive()
            result.put("openedAt", System.currentTimeMillis())
            phase("RFCOMM_OPEN")
            runBlocking {
                try {
                    withTimeout(18_000L) {
                        transport.start(receiverScope) { packet ->
                            phase("RX_${packet.command.name}")
                            TransportLayer.IO.ReceiverBehavior.FORWARD_PACKET
                        }
                        ControlSessionFlow.open(object : ControlSessionExchange {
                            override suspend fun send(packet: TransportLayer.OutgoingPacketInfo) = transport.send(packet)
                            override suspend fun receive(command: TransportLayer.Command) = transport.receive(command)
                        }, packets::accepted, ::phase)
                        connectComplete = true
                    }
                } finally {
                    withContext(NonCancellable) {
                        val close = if (packets.connectSent) ApplicationLayer.createCTRLDisconnectPacket().toTransportLayerPacketInfo() else null
                        transport.stop(close) { closeSocket(); socketClosed = true }
                    }
                }
            }
        } catch (e: Exception) {
            // Exception messages and packet dumps can contain credentials; log only the class.
            synchronized(result) { result.put("errorClass", e.javaClass.simpleName) }
            phase("FAILED")
        } finally {
            connectDeadline.cancel(false)
            totalDeadline.cancel(false)
            runCatching { closeSocket(); socketClosed = true }
            receiverScope.cancel()
            timer.shutdownNow()
            var cleanupFailed = false
            try {
                if (BuildConfig.MANUAL_TARGET && registered) {
                    val until = SystemClock.elapsedRealtime() + 15_000L
                    while (!aclDisconnected.get() && SystemClock.elapsedRealtime() < until) Thread.sleep(50)
                }
            } catch (_: InterruptedException) {
                cleanupFailed = true
                Thread.currentThread().interrupt()
            } finally {
                if (registered) runCatching { context.unregisterReceiver(receiver) }.onFailure { cleanupFailed = true }
                runCatching { if (wake.isHeld) wake.release() }.onFailure { cleanupFailed = true }
            }
            guard.observe(device.bondState == BluetoothDevice.BOND_BONDED)
            val complete = connectComplete && disconnectWritten.get() && socketClosed && !expired.get() && guard.stopReason == null
            val after = if (BuildConfig.MANUAL_TARGET) BluetoothLinkObservation.read(context, device) else null
            val linkCleanupKnown = !BuildConfig.MANUAL_TARGET || aclDisconnected.get() ||
                (!aclConnected.get() && store.advances == 0 && after?.has("connected") == true && !after.getBoolean("connected"))
            settled = ControlSessionCleanup.settled(socketClosed, guard.stopReason == null, complete,
                store.advances, linkCleanupKnown, cleanupFailed)
            synchronized(result) {
                result.put("complete", true).put("handshakeCompleted", connectComplete)
                    .put("disconnectWritten", disconnectWritten.get()).put("socketClosed", socketClosed)
                    .put("success", complete && settled).put("ownershipLocked", !settled)
                    .put("watchdogExpired", expired.get()).put("nonceAdvances", store.advances)
                    .put("bondState", device.bondState).put("bondStopReason", guard.stopReason)
                    .put("linkAfter", after).put("aclConnectedObserved", aclConnected.get())
                    .put("aclDisconnectedObserved", aclDisconnected.get()).put("linkCleanupKnown", linkCleanupKnown)
                    .put("cleanupFailed", cleanupFailed)
                    .put("durationMs", SystemClock.elapsedRealtime() - start)
                phase(if (complete && settled) "CONTROL_SESSION_COMPLETED" else "CONTROL_SESSION_FAILED")
            }
        }
        return settled
    }
}
