package app.aaps.dev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.aaps.services.WearSnapshotService

/**
 * Debug receiver: принудительно запускает публикацию снапшота на часы.
 * Тригерится интентом: app.aaps.wear.ACTION_TEST_RESEND
 */
class ForceSnapshotReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != "app.aaps.wear.ACTION_TEST_RESEND") return

        val svc = Intent(ctx, WearSnapshotService::class.java)
            .setAction("FORCE_SNAPSHOT")   // сервис увидит это и отправит данные

        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc)
            else ctx.startService(svc)
        } catch (_: Throwable) {
            // на всякий случай второй вызов
            ctx.startService(svc)
        }
    }
}