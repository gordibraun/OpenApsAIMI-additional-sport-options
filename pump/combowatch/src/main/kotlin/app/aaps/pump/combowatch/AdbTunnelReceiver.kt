package app.aaps.pump.combowatch

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import dagger.android.DaggerBroadcastReceiver
import javax.inject.Inject
import kotlin.concurrent.thread

/**
 * Opens and closes the adb tunnel to the watch ([AdbTunnelServer]) from the adb shell; see
 * tools/watch-tunnel.sh. Debuggable builds only, and only for a sender holding DUMP (the shell).
 *
 *   am broadcast -n <pkg>/app.aaps.pump.combowatch.AdbTunnelReceiver --es cmd start [--ei listen 4444] [--ei port 5555] [--ei idle 120]
 *   am broadcast -n <pkg>/app.aaps.pump.combowatch.AdbTunnelReceiver --es cmd stop
 *   am broadcast -n <pkg>/app.aaps.pump.combowatch.AdbTunnelReceiver --es cmd status
 *
 * The answer lands in files/combowatch-adb-tunnel.json.
 */
class AdbTunnelReceiver : DaggerBroadcastReceiver() {

    @Inject lateinit var server: AdbTunnelServer
    @Inject lateinit var aapsLogger: AAPSLogger

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val cmd = intent.getStringExtra("cmd") ?: "status"
        val listen = intent.getIntExtra("listen", AdbTunnelServer.DEFAULT_LISTEN_PORT)
        val port = intent.getIntExtra("port", AdbTunnelServer.DEFAULT_WATCH_PORT)
        val idleMs = intent.getIntExtra("idle", 120).coerceAtLeast(0) * 60_000L
        // Sockets are not touched on the main thread; the answer file is written when done.
        thread(name = "adb-tunnel-cmd", isDaemon = true) {
            val answer = runCatching {
                when (cmd) {
                    "start" -> server.start(listen, port, idleMs)
                    "stop"  -> server.stop()
                    else    -> server.status()
                }
            }.getOrElse { t -> server.status().put("lastError", "${t.javaClass.simpleName}: ${t.message}") }
            aapsLogger.info(LTag.PUMP, "combowatch adb tunnel $cmd: $answer")
        }
    }
}
