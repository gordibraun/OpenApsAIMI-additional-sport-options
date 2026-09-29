package app.aaps.combobench

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class BackgroundProbeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val timeout = Runnable { ManualPumpRuntime.get(this).background.cancelWaiting() }
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ManualPumpRuntime.get(context).background.noteScreen(intent.action == Intent.ACTION_SCREEN_ON)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("background-probe", "Проверка связи в фоне", NotificationManager.IMPORTANCE_LOW))
        startForeground(26, Notification.Builder(this, "background-probe")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("Combo: стенд")
            .setContentText("Один отложенный сеанс с тестовой Combo через 5 минут")
            .setOngoing(true).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        })
        running = true
        // No wake lock while waiting; only the bounded control session acquires one.
        main.postDelayed(timeout, BackgroundProbePolicy.DELAY_MS + BackgroundProbePolicy.LATENESS_MS + 1_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try { ManualPumpRuntime.get(this).background.installAlarm() }
        catch (_: Exception) { ManualPumpRuntime.get(this).background.serviceLost(); stopSelf() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(timeout)
        unregisterReceiver(screen)
        ManualPumpRuntime.get(this).background.serviceLost()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object { @Volatile var running = false; private set }
}

class BackgroundProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.MANUAL_TARGET) return
        ManualPumpRuntime.get(context).background.trigger(intent.getStringExtra("id").orEmpty())
    }
}
