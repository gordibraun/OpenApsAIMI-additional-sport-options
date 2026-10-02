package app.aaps.pump.combowatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.combowatch.protocol.BolusKind
import app.aaps.pump.combowatch.protocol.CommandKind
import app.aaps.pump.combowatch.protocol.TbrKind
import dagger.android.DaggerBroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/**
 * Sends a single command to the watch from adb, for testing the whole chain
 * phone -> watch relay -> controller -> pump and back.
 *
 * It goes around AAPS on purpose: nothing passes through the command queue, no treatment record
 * is written, and the active pump driver is not involved - so the chain can be exercised against
 * a test pump on the watch while the loop keeps driving the real pump directly. The watch's pump
 * events are left unacknowledged, i.e. they stay on the watch.
 *
 * Only in debuggable builds, and only for a sender holding DUMP, which is the adb shell.
 *
 *   am broadcast -n <pkg>/app.aaps.pump.combowatch.ComboWatchDebugReceiver --es cmd STATUS
 *   ... --es cmd TBR --ei percent 0 --ei minutes 30 [--es tbrKind EMULATED_STOP]
 *   ... --es cmd CANCEL [--ez force true]
 *   ... --es cmd BOLUS --ei tenths 1
 *   ... --es cmd REVOKE
 *
 * The answer is written to files/combowatch-debug.json.
 */
class ComboWatchDebugReceiver : DaggerBroadcastReceiver() {

    @Inject lateinit var link: ComboWatchLink
    @Inject lateinit var aapsLogger: AAPSLogger

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val cmd = intent.getStringExtra("cmd") ?: return
        val tag = intent.getStringExtra("tag") ?: System.currentTimeMillis().toString()
        val out = File(context.filesDir, OUTPUT_FILE)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            val started = System.currentTimeMillis()
            val answer = JSONObject().put("tag", tag).put("cmd", cmd).put("startedAt", started)
            try {
                if (cmd == "REVOKE") {
                    link.revokeLease(DEBUG_SERIAL)
                    answer.put("revoked", true)
                } else {
                    val kind = when (cmd) {
                        "STATUS" -> CommandKind.STATUS
                        "TBR"    -> CommandKind.SET_TBR
                        "CANCEL" -> CommandKind.CANCEL_TBR
                        "BOLUS"  -> CommandKind.DELIVER_BOLUS
                        else     -> error("unknown cmd $cmd")
                    }
                    val result = link.execute(
                        kind = kind,
                        pumpSerial = DEBUG_SERIAL,
                        leaseValidForMs = 10 * 60_000L,
                        validForMs = 4 * 60_000L,
                        timeoutMs = 8 * 60_000L,
                        percentage = if (kind == CommandKind.SET_TBR) intent.getIntExtra("percent", -1) else null,
                        durationMinutes = if (kind == CommandKind.SET_TBR) intent.getIntExtra("minutes", -1) else null,
                        tbrKind = if (kind == CommandKind.SET_TBR) TbrKind.valueOf(intent.getStringExtra("tbrKind") ?: "NORMAL") else null,
                        force100Percent = if (kind == CommandKind.CANCEL_TBR) intent.getBooleanExtra("force", false) else null,
                        bolusTenthsIU = if (kind == CommandKind.DELIVER_BOLUS) intent.getIntExtra("tenths", -1) else null,
                        bolusKind = if (kind == CommandKind.DELIVER_BOLUS) BolusKind.SMB else null
                    )
                    answer.put("result", result.toJson())
                    link.lastHeartbeat?.let { answer.put("heartbeat", it.toJson()) }
                }
            } catch (t: Throwable) {
                answer.put("error", "${t.javaClass.simpleName}: ${t.message}")
            }
            answer.put("tookMs", System.currentTimeMillis() - started)
            runCatching { out.writeText(answer.toString()) }
            aapsLogger.debug(LTag.PUMP, "combowatch debug: $answer")
            pending.finish()
        }
    }

    private companion object {
        const val OUTPUT_FILE = "combowatch-debug.json"
        const val DEBUG_SERIAL = "debug"
    }
}
