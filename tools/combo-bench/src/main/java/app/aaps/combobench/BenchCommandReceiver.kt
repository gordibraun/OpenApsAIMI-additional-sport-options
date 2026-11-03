package app.aaps.combobench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.aaps.combobench.controller.ControllerHost

/** DUMP permission restricts the automation entry point to shell/system callers. */
class BenchCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra("command") ?: return
        // The owner's answers to a sensed activity, from adb: the notice's own receiver is not exported.
        when (command) {
            "activity-answer" -> {
                ControllerHost.get(context).answerSensedActivity(intent.getStringExtra("answer") ?: return, intent.getLongExtra("start", 0L))
                return
            }
            "activity-cancel" -> {
                ControllerHost.get(context).cancelActivity(intent.getLongExtra("start", 0L), intent.getStringExtra("why") ?: "снята с adb", tellPhone = true)
                return
            }
        }
        if (command !in setOf("discover", "refresh", "transfer", "probe", "control-session", "pairing-start", "pairing-stop", "manual-pairing-stop", "prepare-repairing", "peer-server", "peer-client")) return
        val pending = goAsync()
        BenchRuntime.get(context).run(command) { pending.finish() }
    }
}
