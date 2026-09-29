package app.aaps.combobench

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Negative control: only the two known Android devices, no pump and no application data. */
internal class PeerSocketControl(private val context: Context) {
    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    fun run(mode: String, session: String) {
        val files = BenchFiles(context)
        val permit = files.consumePeerArm(session, mode)
        val peer = PeerControlPolicy.expectedPeer(BuildConfig.FLAVOR)
        val adapter = checkNotNull(context.getSystemService(BluetoothManager::class.java).adapter)
        check(adapter.isEnabled)
        val device = adapter.getRemoteDevice(peer)
        val bond = ProbeBondGuard(device.bondState == BluetoothDevice.BOND_BONDED)
        bond.requireExistingBond()
        val result = JSONObject().put("id", permit.getString("id")).put("mode", mode)
            .put("startedAt", System.currentTimeMillis()).put("peer", peer).put("pumpAccess", false)
            .put("applicationBytesSent", 0).put("apkVersion", BuildConfig.VERSION_NAME)
            .put("interactive", context.getSystemService(PowerManager::class.java).isInteractive)
        val socket = AtomicReference<BluetoothSocket?>()
        val server = AtomicReference<BluetoothServerSocket?>()
        val timedOut = AtomicBoolean(false)
        fun close() {
            runCatching { socket.get()?.close() }
            runCatching { server.get()?.close() }
        }
        val receiver = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(context: Context, intent: Intent) {
                val remote = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                if (!remote.address.equals(peer, ignoreCase = true)) return
                val current = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, remote.bondState)
                val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, current)
                bond.observe(current == BluetoothDevice.BOND_BONDED,
                    pairingRequested = intent.action == BluetoothDevice.ACTION_PAIRING_REQUEST,
                    bondChanged = current != previous)
                if (bond.stopReason != null) close()
            }
        }
        val timer = Executors.newSingleThreadScheduledExecutor()
        var registered = false
        val started = SystemClock.elapsedRealtime()
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter)
            registered = true
            timer.schedule({ timedOut.set(true); close() }, 8, TimeUnit.SECONDS)
            val uuid = UUID.fromString(PeerControlPolicy.SERVICE_UUID)
            if (mode == "server") {
                server.set(adapter.listenUsingInsecureRfcommWithServiceRecord("Bench peer control", uuid))
                check(!timedOut.get() && bond.stopReason == null)
                result.put("stage", "LISTENING")
                files.write("peer-control.json", result)
                socket.set(server.get()!!.accept(7_500))
                check(socket.get()!!.remoteDevice.address.equals(peer, ignoreCase = true)) { "Unexpected peer" }
            } else {
                val prep = ProbePreparation.stopDiscovery(
                    { adapter.isDiscovering }, { adapter.cancelDiscovery() }, SystemClock::elapsedRealtime, Thread::sleep)
                result.put("discoveryWasActive", prep.wasActive).put("stage", "CONNECTING")
                files.write("peer-control.json", result)
                socket.set(device.createInsecureRfcommSocketToServiceRecord(uuid))
                check(!timedOut.get() && bond.stopReason == null)
                socket.get()!!.connect()
            }
            bond.observe(device.bondState == BluetoothDevice.BOND_BONDED)
            check(bond.stopReason == null && !timedOut.get())
            result.put("stage", "PEER_SOCKET_OK_ZERO_BYTES")
        } catch (e: Exception) {
            result.put("stage", if (timedOut.get()) "PEER_SOCKET_TIMEOUT" else "PEER_SOCKET_FAILED")
                .put("errorType", e.javaClass.simpleName)
        } finally {
            close()
            timer.shutdownNow()
            if (registered) context.unregisterReceiver(receiver)
            bond.observe(device.bondState == BluetoothDevice.BOND_BONDED)
            if (bond.stopReason != null) result.put("stage", "PEER_PAIRING_BLOCKED").put("stopReason", bond.stopReason)
            result.put("durationMs", SystemClock.elapsedRealtime() - started).put("finishedAt", System.currentTimeMillis())
            files.write("peer-control.json", result)
        }
    }
}
