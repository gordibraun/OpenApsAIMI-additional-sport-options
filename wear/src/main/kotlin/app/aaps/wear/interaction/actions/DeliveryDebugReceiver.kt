package app.aaps.wear.interaction.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * Puts the dose's status line and screens through their states from adb, so that they can be looked at without
 * a dose: on the charging dock the watch's screen cannot be read back, and nothing may be delivered to see a
 * text. Only what the screens SHOW is set here - nothing goes to the phone or the pump.
 *
 * Debuggable builds only, and only for a sender holding DUMP, which is the adb shell.
 *
 *   am broadcast -n <pkg>/app.aaps.wear.interaction.actions.DeliveryDebugReceiver --es cmd SENT [--es text 'Подать инсулин: 2.0 Е'] [--ei ageSeconds 0]
 *   ... --es cmd DONE   [--es text 'Подтверждено инсулина: 2.0 Е']
 *   ... --es cmd FAILED [--es text 'Помпа не ответила.']
 *   ... --es cmd CLEAR
 *   ... --es cmd SHOW          (writes what the status says now to the log)
 *
 * What the screens then show is written to the log under the tag WatchDelivery.
 */
class DeliveryDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val text = intent.getStringExtra("text")
        when (intent.getStringExtra("cmd")) {
            "SENT"   -> DeliveryStatus.replaceForDisplayTest(
                context,
                listOf(DeliveryStatus.Entry(System.currentTimeMillis() - intent.getIntExtra("ageSeconds", 0) * 1000L, text ?: "Подать инсулин: 2.0 Е", DeliveryStatus.State.SENT))
            )
            "DONE"   -> DeliveryStatus.answered(context, "Готово", text ?: "Подтверждено инсулина: 2.0 Е.")
            "FAILED" -> DeliveryStatus.answered(context, "Проверьте телефон", text ?: "Помпа не ответила.")
            "CLEAR"  -> DeliveryStatus.replaceForDisplayTest(context, emptyList())
            "SHOW"   -> Unit
            else     -> return
        }
        val now = System.currentTimeMillis()
        val progress = DeliveryStatus.progress(context, now)
        Log.i(DeliveryStatus.LOG_TAG, "debug ${intent.getStringExtra("cmd")}: " + (progress?.let { "${it.phase} | ${DeliveryStatus.headline(it)} | ${DeliveryStatus.detail(it, now)} | line: ${DeliveryStatus.lineOf(it, now)}" } ?: "nothing to tell"))
    }
}
