package app.aaps.combobench

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import org.json.JSONObject

/** Local stack metadata only. A false result is not independent radio evidence. */
internal object BluetoothLinkObservation {
    @SuppressLint("MissingPermission", "DiscouragedPrivateApi")
    fun read(context: Context, device: BluetoothDevice): JSONObject {
        val sample = JSONObject().put("at", System.currentTimeMillis()).put("source", "BluetoothDevice.isConnected")
        val started = SystemClock.elapsedRealtime()
        try {
            val adapter = checkNotNull(context.getSystemService(BluetoothManager::class.java).adapter)
            check(adapter.isEnabled && device.bondState == BluetoothDevice.BOND_BONDED)
            val connected = BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as Boolean
            // The platform can return false on IPC timeout; slow/failed queries remain unknown.
            check(SystemClock.elapsedRealtime() - started < 1_000 && adapter.isEnabled &&
                device.bondState == BluetoothDevice.BOND_BONDED)
            sample.put("connected", connected)
            if (connected) sample.put("encrypted", BluetoothDevice::class.java.getMethod("isEncrypted").invoke(device) as Boolean)
        } catch (e: Exception) { sample.put("errorType", e.javaClass.simpleName) }
        return sample.put("elapsedMs", SystemClock.elapsedRealtime() - started)
    }
}
