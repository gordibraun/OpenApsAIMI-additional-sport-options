package app.aaps.combobench.controller

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import app.aaps.combobench.BuildConfig
import app.aaps.pump.combowatch.protocol.BolusKind
import app.aaps.pump.combowatch.protocol.ComboCommand
import app.aaps.pump.combowatch.protocol.ComboWatchProtocol
import app.aaps.pump.combowatch.protocol.CommandKind
import app.aaps.pump.combowatch.protocol.TbrKind
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the controller alive while it works and runs its work strictly one item at a time.
 *
 * It is a foreground service of the connected-device kind for the length of the work and stops
 * itself afterwards; nothing runs and nothing is held between commands, which is what keeps the
 * controller cheap on the watch's battery.
 */
class ControllerService : Service() {

    private val worker = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Управление помпой", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Combo: контроллер")
                .setContentText("Сеанс связи с помпой")
                .setOngoing(true).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val path = intent?.getStringExtra(ControllerHost.EXTRA_PATH)
        val payload = intent?.getStringExtra(ControllerHost.EXTRA_PAYLOAD)
        pending.incrementAndGet()
        worker.execute {
            // Held for the whole item: the wait before a healing read must not be stretched by the
            // CPU going to sleep, and the pump session takes its own lock on top of this one.
            val wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ComboController:work")
            try {
                wake.acquire(WORK_WAKE_LOCK_MS)
                handle(path, payload)
            } catch (t: Throwable) {
                // Nothing here may take the process down: an unclear ending is already recorded in
                // the journal by the executor, and the next item must still be able to run.
                Log.e(TAG, "controller work failed: ${t.javaClass.simpleName}")
            } finally {
                runCatching { if (wake.isHeld) wake.release() }
                if (pending.decrementAndGet() == 0) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun handle(path: String?, payload: String?) {
        val host = ControllerHost.get(this)
        when (path) {
            ComboWatchProtocol.PATH_LEASE      -> host.onLease(checkNotNull(payload))
            ComboWatchProtocol.PATH_EVENTS_ACK -> host.onEventsAck(checkNotNull(payload))
            ComboWatchProtocol.PATH_COMMAND    -> {
                host.onCommand(checkNotNull(payload))
                heal(host, attempt = 0)
            }

            PATH_HEAL                          -> heal(host, attempt = payload?.toIntOrNull() ?: 0)
            PATH_STATE                         -> { host.sendEvents(); host.sendHeartbeat() }
        }
    }

    /**
     * An unclear ending is settled by reading the pump, without waiting for anybody to ask. The
     * first read follows shortly, once the Bluetooth link has had time to close; if the pump
     * cannot be reached, later reads are spaced out with an alarm rather than a held wake lock.
     */
    private fun heal(host: ControllerHost, attempt: Int) {
        if (!host.awaitingReconciliation) return
        if (attempt == 0) SystemClock.sleep(FIRST_HEAL_DELAY_MS)
        if (host.healIfNeeded()) return
        if (attempt + 1 < MAX_HEAL_ATTEMPTS)
            schedule(this, PATH_HEAL, (attempt + 1).toString(), HEAL_RETRY_DELAYS_MS[attempt.coerceAtMost(HEAL_RETRY_DELAYS_MS.lastIndex)])
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {

        private const val TAG = "ComboController"
        private const val CHANNEL = "combo-controller"
        private const val NOTIFICATION_ID = 41
        private const val WORK_WAKE_LOCK_MS = 12 * 60_000L
        private const val FIRST_HEAL_DELAY_MS = 15_000L
        private const val MAX_HEAL_ATTEMPTS = 5
        private val HEAL_RETRY_DELAYS_MS = longArrayOf(60_000L, 180_000L, 300_000L, 600_000L)

        internal const val PATH_HEAL = "/controller/heal"
        internal const val PATH_STATE = "/controller/state"

        /**
         * Start the service for one item. From the background this is allowed only for an app
         * that is exempt from battery optimisation or acting on an exact alarm, so when the direct
         * start is refused the item is handed to an alarm that fires at once.
         */
        internal fun start(context: Context, path: String, payload: String?) {
            val intent = Intent(context, ControllerService::class.java)
                .putExtra(ControllerHost.EXTRA_PATH, path).putExtra(ControllerHost.EXTRA_PAYLOAD, payload)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "direct service start refused (${e.javaClass.simpleName}); using an alarm")
                schedule(context, path, payload, delayMs = 250L)
            }
        }

        internal fun schedule(context: Context, path: String, payload: String?, delayMs: Long) {
            val pendingIntent = PendingIntent.getBroadcast(
                context, path.hashCode(),
                Intent(context, ControllerAlarmReceiver::class.java)
                    .putExtra(ControllerHost.EXTRA_PATH, path).putExtra(ControllerHost.EXTRA_PAYLOAD, payload),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarms = context.getSystemService(AlarmManager::class.java)
            val at = SystemClock.elapsedRealtime() + delayMs
            if (alarms.canScheduleExactAlarms())
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent)
            else
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent)
        }
    }
}

/** An alarm set by [ControllerService.schedule]; an exact alarm may start a foreground service. */
class ControllerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra(ControllerHost.EXTRA_PATH) ?: return
        runCatching {
            context.startForegroundService(
                Intent(context, ControllerService::class.java)
                    .putExtra(ControllerHost.EXTRA_PATH, path)
                    .putExtra(ControllerHost.EXTRA_PAYLOAD, intent.getStringExtra(ControllerHost.EXTRA_PAYLOAD))
            )
        }
    }
}

/** Messages from the phone, handed over by the relay in the AAPS watch app. */
class ControllerInboundReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.MANUAL_TARGET) return
        val path = intent.getStringExtra(ControllerHost.EXTRA_PATH) ?: return
        ControllerService.start(context, path, intent.getStringExtra(ControllerHost.EXTRA_PAYLOAD))
    }
}

/**
 * Drives the controller from adb, standing in for the phone, so that it can be tested against the
 * pump on its own. Guarded by the DUMP permission, which the shell holds and ordinary apps do not,
 * compiled to do nothing outside debug builds, and limited to the off-body test pump: with any
 * other pump paired only STATE works, which touches nothing.
 *
 *   am broadcast -n <pkg>/app.aaps.combobench.controller.ControllerDebugReceiver --es cmd STATUS
 *   ... --es cmd TBR --ei percent 0 --ei minutes 30 [--es tbrKind EMULATED_STOP]
 *   ... --es cmd CANCEL [--ez force true]
 *   ... --es cmd BOLUS --ei tenths 1 [--es bolusKind SMB]
 *   ... --es cmd STATE
 */
class ControllerDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || !BuildConfig.MANUAL_TARGET) return
        val host = ControllerHost.get(context)
        val now = System.currentTimeMillis()
        val kind = when (intent.getStringExtra("cmd")) {
            "STATUS" -> CommandKind.STATUS
            "TBR"    -> CommandKind.SET_TBR
            "CANCEL" -> CommandKind.CANCEL_TBR
            "BOLUS"  -> CommandKind.DELIVER_BOLUS
            "STATE"  -> { ControllerService.start(context, ControllerService.PATH_STATE, null); return }
            else     -> return
        }
        // Everything below stands in for the phone, which is allowed on the bench's test pump only.
        val lease = try {
            host.grantDebugLease(validForMs = 10 * 60_000L)
        } catch (e: IllegalStateException) {
            Log.w("ComboController", "debug command ignored: ${e.message}")
            return
        }
        val command = ComboCommand(
            id = intent.getStringExtra("id") ?: UUID.randomUUID().toString(),
            leaseGeneration = lease.generation,
            kind = kind,
            issuedAtEpochMs = now,
            expiresAtEpochMs = now + 4 * 60_000L,
            percentage = if (kind == CommandKind.SET_TBR) intent.getIntExtra("percent", -1) else null,
            durationMinutes = if (kind == CommandKind.SET_TBR) intent.getIntExtra("minutes", -1) else null,
            tbrKind = if (kind == CommandKind.SET_TBR) TbrKind.valueOf(intent.getStringExtra("tbrKind") ?: "NORMAL") else null,
            force100Percent = if (kind == CommandKind.CANCEL_TBR) intent.getBooleanExtra("force", false) else null,
            bolusTenthsIU = if (kind == CommandKind.DELIVER_BOLUS) intent.getIntExtra("tenths", -1) else null,
            bolusKind = if (kind == CommandKind.DELIVER_BOLUS) BolusKind.valueOf(intent.getStringExtra("bolusKind") ?: "SMB") else null
        )
        ControllerService.start(context, ComboWatchProtocol.PATH_COMMAND, command.toJson().toString())
    }
}
