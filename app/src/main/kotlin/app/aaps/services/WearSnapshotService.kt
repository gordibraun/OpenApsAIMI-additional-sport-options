package app.aaps.services

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import app.aaps.R
import app.aaps.utils.WearNodeResolver

class WearSnapshotService : Service() {

    companion object {
        private const val TAG = "WEAR_SNAPSHOT_SVC"
        private const val CH_ID = "WearSnapshotForeground"
        private const val NOTIF_ID = 42
        private const val ACTION_TICK = "app.aaps.action.WEAR_SNAPSHOT_TICK"
        private const val PERIOD_MS = 30_000L
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()

        // Иконка из системных ресурсов, чтобы не падать из-за отсутствия drawable в app-модуле
        val notif = NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("AAPS → Wear snapshot")
            .setContentText("Keeping watch data fresh")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // На API 28 нет типов FGS — обычный startForeground
        startForeground(NOTIF_ID, notif)

        // первый тик через 2 секунды
        scheduleNextTick(System.currentTimeMillis() + 2000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TICK) {
            tickOnce()
            scheduleNextTick(System.currentTimeMillis() + PERIOD_MS)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun tickOnce() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AAPS:WearSnapshotTick")
            wl.setReferenceCounted(false)
            wl.acquire(3000)

            // === Простейший слепок ===
            // Здесь можно подставить реальные значения BG/TS из твоих слоёв,
            // сейчас отправляем «heartbeat» и, если будут поля, — примитивы.
            val sgv: Int? = null
            val ts: Long? = null
            val statusStr = ""

            val req = PutDataMapRequest.create("/aaps/snapshot").apply {
                if (sgv != null && ts != null) {
                    dataMap.putInt("sgv", sgv)
                    dataMap.putLong("ts", ts)
                    dataMap.putInt("sgvLevel", 0)
                }
                if (statusStr.isNotEmpty()) dataMap.putString("status", statusStr)
                dataMap.putLong("heartbeat", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(this).putDataItem(req)

            WearNodeResolver.getActiveNodeId(this)?.let { nodeId ->
                val payload = if (sgv != null && ts != null) "$sgv|$ts" else "hb"
                Wearable.getMessageClient(this)
                    .sendMessage(nodeId, "/aaps/snapshot_msg", payload.toByteArray())
            }

            if (wl.isHeld) wl.release()
        } catch (t: Throwable) {
            Log.e(TAG, "tickOnce failed", t)
        }
    }

    private fun scheduleNextTick(whenMs: Long) {
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            this, 1001,
            Intent(this, WearSnapshotTickReceiver::class.java).setAction(ACTION_TICK),
            (PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )
        if (Build.VERSION.SDK_INT >= 23) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, whenMs, pi)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm.getNotificationChannel(CH_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CH_ID, "Wear snapshot", NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        setShowBadge(false)
                        enableLights(false)
                        enableVibration(false)
                        setSound(null, null)
                    }
                )
            }
        }
    }
}

/** Раз в 30 с будит сервис для тика */
class WearSnapshotTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "app.aaps.action.WEAR_SNAPSHOT_TICK") {
            context.startService(
                Intent(context, WearSnapshotService::class.java)
                    .setAction("app.aaps.action.WEAR_SNAPSHOT_TICK")
            )
        }
    }
}