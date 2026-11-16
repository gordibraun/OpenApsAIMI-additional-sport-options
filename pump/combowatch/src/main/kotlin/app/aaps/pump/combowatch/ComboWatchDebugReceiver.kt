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
 * And `--es cmd TRIAL_NOTE [--es text '...']` puts a test line through the path of the rules on trial (main
 * screen list, lease, watch journal and notice); it changes no dose and counts as neither rule acting.
 *
 * And `--es cmd PROFILE_BASAL --es rates '1=1.05,2=1.05,...' [--ez dry true]` changes the named hours (from
 * midnight, U/h) of the basal of the local profile that is running, saves it and activates it - the same two
 * steps as «Сохранить» and «Активировать профиль» on the profile screen, so the pump is then written the usual
 * way. With `dry` it only says what would change. It refuses when the profile shown in the editor is not the one
 * running, when a rate is not one the pump holds, or when an hour would move by more than a third.
 *
 * The answer is written to files/combowatch-debug.json.
 */
class ComboWatchDebugReceiver : DaggerBroadcastReceiver() {

    @Inject lateinit var link: ComboWatchLink
    @Inject lateinit var plugin: ComboWatchPlugin
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var activePlugin: app.aaps.core.interfaces.plugin.ActivePlugin
    @Inject lateinit var preferences: app.aaps.core.keys.interfaces.Preferences
    @Inject lateinit var profileFunction: app.aaps.core.interfaces.profile.ProfileFunction

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val cmd = intent.getStringExtra("cmd") ?: return
        val tag = intent.getStringExtra("tag") ?: System.currentTimeMillis().toString()
        val out = File(context.filesDir, OUTPUT_FILE)
        if (cmd == "LINK_RESET") {
            plugin.forceLinkReset(intent.getStringExtra("reason") ?: "проверка с adb")
            val answer = JSONObject().put("tag", tag).put("cmd", cmd).put("asked", true)
            runCatching { out.writeText(answer.toString()) }
            aapsLogger.info(LTag.PUMP, "combowatch debug: $answer")
            return
        }
        if (cmd == "TRIAL_NOTE") {
            // A line through the trial rules' own path - the main screen's list, the next lease, the watch's journal and
            // notice - to see that the path works without waiting for a rule to act. Filed under a key of its own, so it
            // is not counted as either rule acting.
            val text = intent.getStringExtra("text") ?: "Проверка: правила на пробе видны на телефоне и на часах (тестовая запись, доз не меняет)"
            runCatching { app.aaps.core.objects.trials.TrialRules.get(context).add("test", System.currentTimeMillis(), text) }
            val answer = JSONObject().put("tag", tag).put("cmd", cmd).put("noted", true)
            runCatching { out.writeText(answer.toString()) }
            aapsLogger.info(LTag.PUMP, "combowatch debug: $answer")
            return
        }
        if (cmd == "AUTOTUNE_AUTO_OFF") {
            // Autotune's «Automation Switch Profile»: with it on, any automation rule that runs Autotune rewrites the
            // running profile's basal, ISF, IC and DIA and switches to it. Found on, 16.11.2026; nothing runs it today.
            val answer = JSONObject().put("tag", tag).put("cmd", cmd)
            runCatching {
                answer.put("was", preferences.get(app.aaps.core.keys.BooleanKey.AutotuneAutoSwitchProfile))
                preferences.put(app.aaps.core.keys.BooleanKey.AutotuneAutoSwitchProfile, false)
                answer.put("now", preferences.get(app.aaps.core.keys.BooleanKey.AutotuneAutoSwitchProfile))
            }.onFailure { answer.put("error", "${it.javaClass.simpleName}: ${it.message}") }
            runCatching { out.writeText(answer.toString()) }
            aapsLogger.info(LTag.PUMP, "combowatch debug: $answer")
            return
        }
        if (cmd == "PROFILE_BASAL") {
            val answer = JSONObject().put("tag", tag).put("cmd", cmd)
            try {
                val changes = checkNotNull(ProfileBasalEdit.parse(intent.getStringExtra("rates") ?: "")) { "rates must be like 1=1.05,2=1.05" }
                val source = activePlugin.activeProfileSource
                val current = checkNotNull(source.currentProfile()) { "no local profile is open in the editor" }
                val running = profileFunction.getOriginalProfileName()
                check(current.name == running) { "the editor shows «${current.name}», the running profile is «$running»" }
                val before = checkNotNull(ProfileBasalEdit.hourly(current.basal)) { "the profile's basal blocks cannot be read: ${current.basal}" }
                val after = ProfileBasalEdit.apply(before, changes)
                // Only the hours that are being changed are held to the pump's own steps: the others stay as the owner
                // wrote them (1.23 is kept by the pump as 1.25, and AAPS has always been told so).
                changes.forEach { (hour, rate) ->
                    check(ComboWatchPlugin.comboBasalFactor(kotlin.math.round(rate * 1000.0).toInt()) == kotlin.math.round(rate * 1000.0).toInt()) { "hour $hour: $rate U/h is not a rate the pump holds" }
                    check(rate >= 0.05) { "hour $hour: $rate U/h is below what the pump holds" }
                    check(kotlin.math.abs(rate - before[hour]) <= before[hour] / 3.0 + 1e-9) { "hour $hour: ${before[hour]} -> $rate is a change of more than a third" }
                }
                answer.put("profile", current.name)
                    .put("watchWritesProfiles", link.lastHeartbeat?.capabilities?.contains(app.aaps.pump.combowatch.protocol.ComboWatchProtocol.CAN_SET_BASAL_PROFILE) == true)
                    .put("onPump", link.lastSnapshot?.basalProfileFactors?.let { org.json.JSONArray(it) } ?: JSONObject.NULL)
                    .put("before", org.json.JSONArray(before)).put("after", org.json.JSONArray(after))
                    .put("totalBefore", before.sum()).put("totalAfter", after.sum())
                if (intent.getBooleanExtra("dry", false)) answer.put("dry", true)
                else {
                    val now = System.currentTimeMillis()
                    current.basal = ProfileBasalEdit.blocks(after)
                    source.storeSettings(null, now)
                    val store = checkNotNull(source.profile) { "the profile store is empty after saving" }
                    val switched = profileFunction.createProfileSwitch(
                        profileStore = store, profileName = current.name, durationInMinutes = 0, percentage = 100, timeShiftInHours = 0, timestamp = now,
                        action = app.aaps.core.data.ue.Action.PROFILE_SWITCH, source = app.aaps.core.data.ue.Sources.Maintenance,
                        note = intent.getStringExtra("note") ?: "Базал по часам обновлён по слову владельца",
                        listValues = listOf(app.aaps.core.data.ue.ValueWithUnit.SimpleString(current.name), app.aaps.core.data.ue.ValueWithUnit.Percent(100))
                    )
                    answer.put("saved", true).put("switchCreated", switched)
                }
            } catch (t: Throwable) {
                answer.put("error", "${t.javaClass.simpleName}: ${t.message}")
            }
            runCatching { out.writeText(answer.toString()) }
            aapsLogger.info(LTag.PUMP, "combowatch debug: $answer")
            return
        }
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
