package app.aaps.combobench

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Only Android Bluetooth bonding. No Combo protocol, keys import or dosing commands. */
@SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
internal class BluetoothPairingSession(
    private val context: Context,
    private val address: String?,
    private val update: (String, String) -> Unit,
    private val finished: (String, String, Boolean) -> Unit,
    private val diagnostic: (String) -> Unit = {},
    private val manualTarget: ManualPumpTarget? = null,
    private val targetSelected: (String) -> Unit = {}
) {
    @Volatile private var resolvedAddress: String? = address
    private val closed = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val deadline = Runnable { stop("TIMEOUT", "Время Bluetooth-сопряжения истекло") }
    private var server: BluetoothServerSocket? = null
    private var registered = false
    private var wasDiscoverable = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val adapter get() = context.getSystemService(BluetoothManager::class.java).adapter

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            if (closed.get()) return
            if (intent.action == BluetoothAdapter.ACTION_SCAN_MODE_CHANGED) {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_SCAN_MODE, -1) == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE)
                    update("DISCOVERABLE", "Часы видны для поиска пульта на тестовой Combo")
                return
            }
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            if (manualTarget != null && resolvedAddress == null) {
                if (intent.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return
                if (!manualTarget.accepts(device.address, device.name)) {
                    diagnostic("Запрос отклонён: неподходящее Bluetooth-устройство")
                    return
                }
                if (device.bondState == BluetoothDevice.BOND_BONDED) {
                    diagnostic("Запрос отклонён: устройство уже сопряжено; существующая привязка не меняется")
                    return
                }
                resolvedAddress = device.address
                targetSelected(device.address)
            }
            if (!PairingTarget.matches(resolvedAddress ?: return, device.address)) return
            when (intent.action) {
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    update("PAIRING_REQUEST", "Получен Bluetooth-запрос тестовой Combo")
                    // Same legacy PIN exchange as ComboCtl AndroidBluetoothInterface.
                    // This public protocol constant is not the pump's ten-digit application PIN.
                    runCatching { device.setPin("}gZ='GD?gj2r|B}>".toByteArray(Charsets.US_ASCII)) }
                        .onFailure { update("PAIRING_REQUEST", "Android не принял Bluetooth PIN: ${it.javaClass.simpleName}") }
                    runCatching { device.createBond() }
                    runCatching { device.setPairingConfirmation(true) }
                        .onFailure { update("PAIRING_REQUEST", "Может потребоваться подтверждение в системном окне часов") }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                    BluetoothDevice.BOND_BONDING -> update("BONDING", "Выполняется Bluetooth-сопряжение")
                    BluetoothDevice.BOND_BONDED -> stop("BONDED", "Bluetooth сопряжён; авторизация Combo ещё не выполнена")
                    BluetoothDevice.BOND_NONE -> if (intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1) == BluetoothDevice.BOND_BONDING)
                        stop("FAILED", "Android не завершил Bluetooth-сопряжение")
                }
            }
        }
    }

    fun start() {
        val adapter = checkNotNull(adapter) { "Bluetooth is unavailable" }
        check(adapter.isEnabled) { "Bluetooth is disabled" }
        check(address != null || manualTarget != null) { "Pairing target is missing" }
        address?.let {
            check(adapter.getRemoteDevice(it).bondState == BluetoothDevice.BOND_NONE) { "Target is already bonded or bonding" }
            targetSelected(it)
        }
        wasDiscoverable = adapter.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
        registered = true
        server = adapter.listenUsingInsecureRfcommWithServiceRecord("SerialLink", UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
        wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ComboBench:Pairing").apply { acquire(190_000) }
        handler.postDelayed(deadline, 180_000)
        thread(name = "ComboBench-pairing", isDaemon = true) {
            try {
                while (!closed.get()) server?.accept()?.close()
            } catch (_: Exception) {
                if (!closed.get()) stop("FAILED", "Bluetooth-служба сопряжения закрылась")
            }
        }
        if (wasDiscoverable) update("DISCOVERABLE", "Часы уже видны. На тестовой Combo запустите поиск нового пульта")
        else update("WAITING_VISIBILITY", "Нужно разрешить видимость часов в системном окне")
    }

    fun visibilityResult(resultCode: Int?) {
        if (closed.get()) return
        val scanMode = runCatching { adapter?.scanMode }.getOrNull()
        val isDiscoverable = scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
        diagnostic("Ответ окна видимости: resultCode=${resultCode ?: "нет"}, scanMode=${scanMode ?: "неизвестен"}, ранее видны=$wasDiscoverable")
        when {
            resultCode == null -> stop("FAILED", "Не удалось открыть системное окно видимости Bluetooth")
            !DiscoverabilityResult.accepted(resultCode, BuildConfig.FLAVOR == "watch" || BuildConfig.MANUAL_TARGET, wasDiscoverable, isDiscoverable) ->
                stop("CANCELLED", "Android не разрешил видимость часов или окно было отменено")
            isDiscoverable ->
                update("DISCOVERABLE", "Часы видны. На тестовой Combo запустите поиск нового пульта")
            else -> update("WAITING_DISCOVERABLE", "Разрешение получено. Ждём включения видимости Bluetooth")
        }
    }

    fun stop(stage: String = "STOPPED", detail: String = "Bluetooth-сопряжение остановлено") {
        if (!closed.compareAndSet(false, true)) return
        handler.removeCallbacks(deadline)
        val serverClosed = runCatching { server?.close() }.isSuccess
        val receiverClosed = !registered || runCatching { context.unregisterReceiver(receiver) }.isSuccess
        wakeLock?.let { if (it.isHeld) it.release() }
        val settled = runCatching {
            resolvedAddress?.let { adapter?.getRemoteDevice(it)?.bondState }.let {
                if (resolvedAddress == null) return@runCatching true
                it == BluetoothDevice.BOND_NONE || it == BluetoothDevice.BOND_BONDED
            }
        }.getOrDefault(false) && serverClosed && receiverClosed
        finished(if (settled) stage else "UNRESOLVED",
            if (settled) detail else "$detail. Системное сопряжение не завершено; повторные действия заблокированы", settled)
    }
}
