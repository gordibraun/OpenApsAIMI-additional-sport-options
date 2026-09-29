package app.aaps.combobench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** DUMP permission restricts the automation entry point to shell/system callers. */
class BenchCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra("command") ?: return
        if (command !in setOf("discover", "refresh", "transfer", "probe", "control-session", "pairing-start", "pairing-stop", "manual-pairing-stop", "prepare-repairing", "peer-server", "peer-client")) return
        val pending = goAsync()
        BenchRuntime.get(context).run(command) { pending.finish() }
    }
}
