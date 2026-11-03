package app.aaps.combobench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.aaps.combobench.controller.ControllerHost

/** DUMP permission restricts the automation entry point to shell/system callers. */
class BenchCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra("command") ?: return
        Log.i(TAG, "command $command, extras ${intent.extras?.keySet()?.joinToString()}")
        // The owner's answers to a sensed activity, from adb: the notice's own receiver is not exported.
        when (command) {
            "activity-answer" -> {
                val start = startExtra(intent)
                ControllerHost.get(context).answerSensedActivity(intent.getStringExtra("answer") ?: return, start)
                Log.i(TAG, "activity-answer for $start handled")
                return
            }
            "activity-cancel" -> {
                val start = startExtra(intent)
                val done = runCatching { ControllerHost.get(context).cancelActivity(start, intent.getStringExtra("why") ?: "снята с adb", tellPhone = true) }
                    .onFailure { Log.e(TAG, "activity-cancel for $start failed: ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrDefault(false)
                Log.i(TAG, "activity-cancel for $start -> $done")
                return
            }
        }
        if (command !in setOf("discover", "refresh", "transfer", "probe", "control-session", "pairing-start", "pairing-stop", "manual-pairing-stop", "prepare-repairing", "peer-server", "peer-client")) return
        val pending = goAsync()
        BenchRuntime.get(context).run(command) { pending.finish() }
    }

    /** The start as a long extra, or as a string (`--es start …`), whichever the caller used. */
    private fun startExtra(intent: Intent): Long =
        intent.getLongExtra("start", 0L).takeIf { it > 0L } ?: intent.getStringExtra("start")?.trim()?.toLongOrNull() ?: 0L

    companion object {

        const val TAG = "BenchCommand"
    }
}
