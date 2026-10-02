package app.aaps.combobench

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

class PairingForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("pairing", "Сопряжение Combo", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, ManualPairingActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(6, Notification.Builder(this, "pairing").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Combo: стенд").setContentText(if (BuildConfig.MANUAL_TARGET)
                "Сеанс с Combo ${ManualPumpRuntime.get(this).target()?.serial.orEmpty()}" else "Служебная связь. Подача инсулина недоступна")
            .setContentIntent(open).setOngoing(true).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "ComboBench:ManualPairing").apply { acquire(9 * 60_000L) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
