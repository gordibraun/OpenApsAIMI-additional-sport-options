package app.aaps.pump.combowatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.combowatch.protocol.BolusKind
import app.aaps.pump.combowatch.protocol.CommandKind
import app.aaps.pump.combowatch.protocol.PumpEvent
import app.aaps.pump.combowatch.protocol.TbrKind
import dagger.android.DaggerBroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * It can only ever move the bench's off-body test pump: the lease it sends names that pump, and
 * the watch changes delivery on no pump but the one the lease names. While the watch-backed
 * driver is the active one it does nothing at all, because then AAPS itself is in charge.
 *
 * Only in debuggable builds, and only for a sender holding DUMP, which is the adb shell.
 *
 *   am broadcast -n <pkg>/app.aaps.pump.combowatch.ComboWatchDebugReceiver --es cmd STATUS
 *   ... --es cmd TBR --ei percent 0 --ei minutes 30 [--es tbrKind EMULATED_STOP]
 *   ... --es cmd CANCEL [--ez force true]
 *   ... --es cmd BOLUS --ei tenths 1
 *   ... --es cmd REVOKE
 *
 * One more thing it does, and this one with the active driver: `--es cmd EVENT --es json '{...}'`
 * puts a pump event (as the watch would send it) through the driver's own filing, for records
 * the phone missed - say, boluses the pump reported while AAPS was turning its history away.
 * Records are keyed on the pump's own ids, so one filed twice is filed once.
 *
 * The answer is written to files/combowatch-debug.json.
 */
class ComboWatchDebugReceiver : DaggerBroadcastReceiver() {

    @Inject lateinit var link: ComboWatchLink
    @Inject lateinit var plugin: ComboWatchPlugin
    @Inject lateinit var aapsLogger: AAPSLogger

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val cmd = intent.getStringExtra("cmd") ?: return
        val tag = intent.getStringExtra("tag") ?: System.currentTimeMillis().toString()
        val out = File(context.filesDir, OUTPUT_FILE)
        if (cmd == "EVENT") {
            val json = intent.getStringExtra("json") ?: return
            scope.launch {
                val answer = JSONObject().put("tag", tag).put("cmd", cmd)
                try {
                    plugin.handlePumpEvent(PumpEvent.fromJson(JSONObject(json)))
                    answer.put("filed", true)
                } catch (t: Throwable) {
                    answer.put("error", "${t.javaClass.simpleName}: ${t.message}")
                }
                runCatching { out.writeText(answer.toString()) }
                aapsLogger.info(LTag.PUMP, "combowatch debug: $answer")
            }
            return
        }
        // The broadcast is not held open: a command takes about a minute, far beyond what a
        // receiver may keep the system waiting, and this runs inside the app that drives the loop.
        // The work continues in the app's own process, which its foreground service keeps alive.
        scope.launch {
            val started = System.currentTimeMillis()
            val answer = JSONObject().put("tag", tag).put("cmd", cmd).put("startedAt", started)
            try {
                check(link.eventHandler == null) { "the watch-backed driver is active; commands go through AAPS" }
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
                        leaseValidForMs = 5 * 60_000L,
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
        }
    }

    private companion object {
        const val OUTPUT_FILE = "combowatch-debug.json"
        /** The bench's test pump, the only one this receiver's lease ever names. */
        const val DEBUG_SERIAL = "PUMP_10392647"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
