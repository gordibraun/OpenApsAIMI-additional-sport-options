package app.aaps.combobench

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice as SystemDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import info.nightscout.comboctl.android.AndroidBluetoothDevice
import info.nightscout.comboctl.base.BasicProgressStage
import info.nightscout.comboctl.base.BluetoothAddress
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.base.LoggerBackend
import info.nightscout.comboctl.base.ProgressStage
import info.nightscout.comboctl.base.BluetoothDevice
import info.nightscout.comboctl.base.ComboIOException
import info.nightscout.comboctl.base.CurrentTbrState
import info.nightscout.comboctl.base.Tbr
import info.nightscout.comboctl.base.toBluetoothAddress
import info.nightscout.comboctl.main.BasalProfile
import info.nightscout.comboctl.main.Pump
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * One bounded session of the real AAPS driver ([Pump]) against the off-body test Combo:
 * connect (the driver's own checks run: history delta, status, basal profile, clock, TBR
 * reconciliation), optionally one TBR command, a status re-read, then the driver's own
 * disconnect. Nothing else is issued by the bench.
 */
@OptIn(ExperimentalTime::class)
internal class TherapySessionRunner(private val context: Context, private val files: BenchFiles) {
    class Outcome(val result: JSONObject, val success: Boolean, val settled: Boolean)

    /**
     * The fork's AndroidBluetoothDevice watchdog and disconnect() make reads return empty lists and
     * writes succeed silently, which leaves ComboCtl spinning instead of failing. Turn that into
     * [ComboIOException] so the driver's own error paths run and every bench timeout can fire.
     */
    private class FailingWhenClosedTransport(private val inner: AndroidBluetoothDevice) : BluetoothDevice(Dispatchers.IO) {
        @Volatile var closed = false
            private set
        override val address = inner.address
        override fun connect() { closed = false; inner.connect() }
        override fun disconnect() { closed = true; inner.disconnect() }
        override fun unpair() = error("Unpairing is not part of a bench session")
        override fun blockingSend(dataToSend: List<Byte>) {
            if (closed) throw ComboIOException("Bench transport closed")
            inner.blockingSend(dataToSend)
        }
        override fun blockingReceive(): List<Byte> {
            val data = inner.blockingReceive()
            if (data.isEmpty()) throw ComboIOException("Bench transport closed or idle watchdog fired")
            return data
        }
    }

    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    suspend fun run(id: String, request: TherapySessionPolicy.Request): Outcome {
        check(BuildConfig.MANUAL_TARGET)
        val start = SystemClock.elapsedRealtime()
        val pumpId = TherapySessionPolicy.PUMP
        val address = TherapySessionPolicy.ADDRESS
        val result = JSONObject().put("id", id).put("at", System.currentTimeMillis())
            .put("pump", pumpId).put("address", address).put("kind", request.kind.name)
            .put("requestedPercentage", request.percentage ?: JSONObject.NULL)
            .put("requestedDurationMinutes", request.durationMinutes)
            .put("transport", "aaps-Pump").put("mode", "aaps-driver-session")
            .put("apkVersionCode", BuildConfig.VERSION_CODE).put("apkVersionName", BuildConfig.VERSION_NAME)
            .put("complete", false).put("therapyCommandsSent", 0).put("reconciliationCancels", 0)
        val events = JSONArray()
        var lastEvent = ""
        // Every mutation of `result` goes through the same lock that serialises it.
        fun record(block: JSONObject.() -> Unit) = synchronized(result) { result.block() }
        fun phase(name: String, detail: JSONObject? = null) = synchronized(result) {
            if (name == lastEvent && detail == null) return@synchronized
            lastEvent = name
            result.put("phase", name)
            val event = JSONObject().put("name", name).put("elapsedMs", SystemClock.elapsedRealtime() - start)
            if (detail != null) event.put("detail", detail)
            events.put(event)
            files.write("therapy-session.json", result.put("events", events))
        }
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        check(adapter != null && adapter.isEnabled)
        val device = adapter.getRemoteDevice(address)
        val guard = ProbeBondGuard(device.bondState == SystemDevice.BOND_BONDED)
        guard.requireExistingBond()
        val btAddress = address.toBluetoothAddress()
        val store = TherapySessionStore(BenchPairingStore(context, btAddress, pumpId), files)
        check(store.hasPumpState(btAddress) && store.getInvariantPumpData(btAddress).pumpID == pumpId)
        record { put("tbrStateBefore", tbrStateJson(store, btAddress)) }
        val power = context.getSystemService(PowerManager::class.java)
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ComboBench:therapy-session")
        val aclConnected = AtomicBoolean(false)
        val aclDisconnected = AtomicBoolean(false)
        val reconciliationCancels = AtomicInteger(0)
        val transport = FailingWhenClosedTransport(AndroidBluetoothDevice(context, adapter, btAddress,
            watchdogTimeoutMs = TherapySessionPolicy.BLUETOOTH_WATCHDOG_MS))
        val flows = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val driverLog = DriverLogCapture()
        val receiver = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(context: Context, intent: Intent) {
                val remote = intent.getParcelableExtra<SystemDevice>(SystemDevice.EXTRA_DEVICE) ?: return
                if (remote.address != address) return
                if (intent.action == SystemDevice.ACTION_ACL_CONNECTED) { aclConnected.set(true); aclDisconnected.set(false); phase("ACL_CONNECTED") }
                if (intent.action == SystemDevice.ACTION_ACL_DISCONNECTED) { aclDisconnected.set(true); phase("ACL_DISCONNECTED") }
                val state = intent.getIntExtra(SystemDevice.EXTRA_BOND_STATE, remote.bondState)
                val previous = intent.getIntExtra(SystemDevice.EXTRA_PREVIOUS_BOND_STATE, state)
                guard.observe(state == SystemDevice.BOND_BONDED,
                    pairingRequested = intent.action == SystemDevice.ACTION_PAIRING_REQUEST,
                    bondChanged = intent.action == SystemDevice.ACTION_BOND_STATE_CHANGED && state != previous)
                if (guard.stopReason != null) {
                    phase("BOND_GUARD_${guard.stopReason}")
                    work.cancel()
                    runCatching { transport.disconnect() }
                }
            }
        }
        val pump = Pump(transport, store, loadBasalProfile()) { event ->
            // The driver cancels a TBR it cannot reconcile (100 % TBR, W6); that is a therapy action too.
            if (event is Pump.Event.UnknownTbrDetected) reconciliationCancels.incrementAndGet()
            phase("PUMP_EVENT_${event::class.simpleName}", eventJson(event))
        }
        var registered = false
        var connected = false
        var commandAttempted = false
        var commandOutcomeKnown = true
        var tbrVerified = false
        var disconnectCompleted = false
        var cleanupFailed = false
        var success = false
        var settled = false
        var pumpWork: Deferred<Unit>? = null
        try {
            wake.acquire(TherapySessionPolicy.TOTAL_TIMEOUT_MS + 120_000L)
            val filter = IntentFilter().apply {
                addAction(SystemDevice.ACTION_BOND_STATE_CHANGED)
                addAction(SystemDevice.ACTION_PAIRING_REQUEST)
                addAction(SystemDevice.ACTION_ACL_CONNECTED)
                addAction(SystemDevice.ACTION_ACL_DISCONNECTED)
            }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter)
            registered = true
            flows.launch { pump.stateFlow.collect {
                if (it is Pump.State.Error) phase("STATE_Error", errorJson(it.throwable)) else phase("STATE_${it::class.simpleName}")
            } }
            flows.launch { pump.connectProgressFlow.collect { phase("CONNECT_${it.stage.id}", stageError(it.stage)) } }
            flows.launch { pump.getBasalProfileFlow.collect { phase("BASAL_${it.stage.id}", stageError(it.stage)) } }
            flows.launch { pump.setTbrProgressFlow.collect { phase("TBR_${it.stage.id}", stageError(it.stage)) } }
            flows.launch { pump.setDateTimeProgressFlow.collect { phase("DATETIME_${it.stage.id}", stageError(it.stage)) } }
            driverLog.install()
            // Screen transitions (type + parsed content) tell which pump screen a stalled step is looking at.
            flows.launch {
                var last = ""
                pump.parsedDisplayFrameFlow.collect { frame ->
                    val text = frame?.parsedScreen?.toString()?.take(160) ?: return@collect
                    if (text != last) { last = text; driverLog.log("Screen", LogLevel.DEBUG, null, text) }
                }
            }
            // The driver has no internal timeouts and a dead link can park it inside NonCancellable
            // sections, so the work runs in its own job and only the wait is bounded.
            val job = work.async {
                val before = BluetoothLinkObservation.read(context, device)
                record { put("linkBefore", before) }
                check(before.has("connected") && !before.getBoolean("connected")) { "Previous connection not confirmed closed" }
                ProbePreparation.stopDiscovery({ adapter.isDiscovering }, { adapter.cancelDiscovery() },
                    SystemClock::elapsedRealtime, Thread::sleep)
                check(guard.stopReason == null)
                phase("CONNECTING")
                withTimeout(TherapySessionPolicy.CONNECT_TIMEOUT_MS) { pump.connect(maxNumAttempts = 3) }
                connected = true
                val state = pump.stateFlow.value
                val status = pump.statusFlow.value
                val profile = pump.currentBasalProfile
                record {
                    put("connectedAt", System.currentTimeMillis()).put("connectDurationMs", SystemClock.elapsedRealtime() - start)
                    put("stateAfterConnect", state::class.simpleName)
                    if (status != null) put("statusAfterConnect", statusJson(status))
                    if (profile != null) put("basalProfileFactors", JSONArray(profile.factors))
                    put("currentTbrAfterConnect", tbrJson(pump.currentTbrFlow.value))
                }
                if (profile != null) saveBasalProfile(profile)
                phase("CONNECTED")
                if (request.kind != TherapySessionPolicy.Kind.STATUS) {
                    check(state == Pump.State.ReadyForCommands) { "Pump is not ready for commands" }
                    val percentage = checkNotNull(request.percentage)
                    commandAttempted = true
                    commandOutcomeKnown = false
                    phase("TBR_COMMAND_STARTED", JSONObject().put("percentage", percentage).put("durationMinutes", request.durationMinutes))
                    // The driver programs the pump before it records the TBR. Record the intent first so a
                    // failure in between cannot make the next connect cancel this TBR as "unknown".
                    if (request.kind == TherapySessionPolicy.Kind.STOP) store.setCurrentTbrState(btAddress, CurrentTbrState.TbrStarted(
                        Tbr(Clock.System.now(), percentage, request.durationMinutes, Tbr.Type.EMULATED_COMBO_STOP)))
                    val outcome = withTimeout(TherapySessionPolicy.COMMAND_TIMEOUT_MS) {
                        when (request.kind) {
                            TherapySessionPolicy.Kind.STOP -> pump.setTbr(percentage, request.durationMinutes, Tbr.Type.EMULATED_COMBO_STOP)
                            TherapySessionPolicy.Kind.RESUME -> pump.setTbr(percentage, 0, Tbr.Type.NORMAL, force100Percent = true)
                            TherapySessionPolicy.Kind.STATUS -> error("unreachable")
                        }
                    }
                    commandOutcomeKnown = true
                    record { put("tbrOutcome", outcome.name) }
                    // setTbr verifies the main screen itself but leaves statusFlow untouched; re-read it.
                    phase("STATUS_REFRESH")
                    withTimeout(TherapySessionPolicy.STATUS_TIMEOUT_MS) { pump.updateStatus() }
                    val after = pump.statusFlow.value
                    tbrVerified = when (request.kind) {
                        TherapySessionPolicy.Kind.STOP -> after != null && after.tbrOngoing && after.tbrPercentage == percentage
                        TherapySessionPolicy.Kind.RESUME -> after != null && !after.tbrOngoing
                        TherapySessionPolicy.Kind.STATUS -> false
                    }
                    record {
                        if (after != null) put("statusAfterCommand", statusJson(after))
                        put("currentTbrAfterCommand", tbrJson(pump.currentTbrFlow.value)).put("tbrVerified", tbrVerified)
                    }
                    phase("TBR_COMMAND_${outcome.name}")
                }
                success = true
            }
            pumpWork = job
            withTimeout(TherapySessionPolicy.TOTAL_TIMEOUT_MS) { job.await() }
        } catch (e: Exception) {
            record { put("errorClass", e.javaClass.simpleName).put("errorCause", e.cause?.javaClass?.simpleName ?: JSONObject.NULL).put("error", errorJson(e)) }
            phase("FAILED")
        } finally {
            withContext(NonCancellable) {
                work.cancel()
                driverLog.uninstall()
                record { put("driverLog", driverLog.snapshot()) }
                // Keep whatever the driver already read, even if connect() did not finish.
                pump.currentBasalProfile?.let { profile ->
                    saveBasalProfile(profile)
                    record { put("basalProfileFactors", JSONArray(profile.factors)) }
                }
                // Let the driver's own cancellation handling finish before the bench disconnects.
                pumpWork?.let { withTimeoutOrNull(15_000L) { it.join() } }
                try {
                    withTimeout(60_000L) { pump.disconnect() }
                    disconnectCompleted = true
                    phase("DISCONNECTED")
                } catch (e: Exception) {
                    phase("DISCONNECT_FAILED_${e.javaClass.simpleName}")
                }
                runCatching { transport.disconnect() }
                flows.cancel()
                try {
                    if (registered) {
                        val until = SystemClock.elapsedRealtime() + TherapySessionPolicy.LINK_CLOSE_WAIT_MS
                        while (!aclDisconnected.get() && SystemClock.elapsedRealtime() < until) delay(50)
                    }
                } finally {
                    if (registered) runCatching { context.unregisterReceiver(receiver) }.onFailure { cleanupFailed = true }
                    runCatching { if (wake.isHeld) wake.release() }.onFailure { cleanupFailed = true }
                }
                guard.observe(device.bondState == SystemDevice.BOND_BONDED)
                val after = BluetoothLinkObservation.read(context, device)
                val linkCleanupKnown = aclDisconnected.get() ||
                    (!aclConnected.get() && store.advances == 0 && after.has("connected") && !after.getBoolean("connected"))
                settled = TherapySessionPolicy.settled(disconnectCompleted, guard.stopReason == null,
                    linkCleanupKnown, cleanupFailed, commandOutcomeKnown)
                success = success && settled && (!commandAttempted || tbrVerified)
                val cancels = reconciliationCancels.get()
                record {
                    put("complete", true).put("connected", connected).put("commandAttempted", commandAttempted)
                        .put("therapyCommandsSent", (if (commandAttempted) 1 else 0) + cancels).put("reconciliationCancels", cancels)
                        .put("commandOutcomeKnown", commandOutcomeKnown).put("disconnectCompleted", disconnectCompleted)
                        .put("success", success).put("settled", settled).put("ownershipLocked", !settled)
                        .put("nonceAdvances", store.advances).put("nonceRecoveryJumps", store.recoveryJumps)
                        .put("bondState", device.bondState).put("bondStopReason", guard.stopReason ?: JSONObject.NULL)
                        .put("linkAfter", after).put("aclConnectedObserved", aclConnected.get())
                        .put("aclDisconnectedObserved", aclDisconnected.get()).put("linkCleanupKnown", linkCleanupKnown)
                        .put("cleanupFailed", cleanupFailed).put("tbrStateAfter", tbrStateJson(store, btAddress))
                        .put("durationMs", SystemClock.elapsedRealtime() - start)
                }
                phase(if (success) "THERAPY_SESSION_COMPLETED" else "THERAPY_SESSION_FAILED")
            }
        }
        return Outcome(synchronized(result) { JSONObject(result.toString()) }, success, settled)
    }

    private fun stageError(stage: ProgressStage): JSONObject? =
        if (stage is BasicProgressStage.Error) errorJson(stage.cause) else null

    /** Class chain always; the message only for navigation/parsing errors, whose text names screens, not secrets. */
    private fun errorJson(throwable: Throwable?): JSONObject {
        val json = JSONObject()
        var current = throwable
        val chain = JSONArray()
        var depth = 0
        while (current != null && depth < 6) {
            val entry = JSONObject().put("class", current.javaClass.name)
            // Navigation/parsing messages name screens; these two protocol messages name commands. No payloads.
            if (current.javaClass.name.startsWith("info.nightscout.comboctl.main.") || current.javaClass.name.startsWith("info.nightscout.comboctl.parser.") ||
                current.javaClass.simpleName == "IncorrectPacketException" || current.javaClass.simpleName == "ErrorResponseException")
                entry.put("message", current.message?.take(300) ?: JSONObject.NULL)
            chain.put(entry)
            current = current.cause
            depth++
        }
        return json.put("chain", chain)
    }

    /**
     * Bounded capture of the driver's WARN/ERROR lines for the navigation/parsing tags only.
     * Transport and pairing tags are excluded because their dumps can contain key material.
     */
    private class DriverLogCapture : LoggerBackend {
        private val safeTags = setOf("Pump", "RTNavigation", "ParsedDisplayFrameStream", "Parser", "PumpIO", "Screen")
        private val lines = ArrayDeque<JSONObject>()
        private var previous: LoggerBackend? = null
        private var previousThreshold: LogLevel? = null
        private val started = SystemClock.elapsedRealtime()
        @Synchronized override fun log(tag: String, level: LogLevel, throwable: Throwable?, message: String?) {
            // Lower numericLevel is more severe (ERROR < WARN < INFO ...). Navigation tags are kept at
            // DEBUG too: their text names screens and quantities, which is what a stalled step needs.
            val keepLevel = if (tag == "Pump" || tag == "RTNavigation" || tag == "Screen" || tag == "PumpIO") LogLevel.DEBUG else LogLevel.WARN
            if (tag !in safeTags || level.numericLevel > keepLevel.numericLevel) return
            if (lines.size >= 400) lines.removeFirst()
            lines.addLast(JSONObject().put("t", SystemClock.elapsedRealtime() - started).put("tag", tag).put("level", level.str)
                .put("throwable", throwable?.javaClass?.simpleName ?: JSONObject.NULL)
                .put("message", if (tag == "PumpIO" && message?.contains("button", ignoreCase = true) != true) JSONObject.NULL
                    else message?.take(300) ?: JSONObject.NULL))
        }
        @Synchronized fun install() { previous = Logger.backend; previousThreshold = Logger.threshold; Logger.backend = this; Logger.threshold = LogLevel.DEBUG }
        @Synchronized fun uninstall() { previous?.let { Logger.backend = it }; previousThreshold?.let { Logger.threshold = it } }
        @Synchronized fun snapshot() = JSONArray(lines.toList())
    }

    private fun loadBasalProfile(): BasalProfile? = try {
        if (!files.exists("manual-basal-profile.json")) null
        else files.read("manual-basal-profile.json").let { saved ->
            check(saved.getString("pump") == TherapySessionPolicy.PUMP)
            val array = saved.getJSONArray("factors")
            BasalProfile((0 until array.length()).map { array.getInt(it) })
        }
    } catch (_: Exception) { null }

    private fun saveBasalProfile(profile: BasalProfile) = runCatching {
        files.write("manual-basal-profile.json", JSONObject().put("pump", TherapySessionPolicy.PUMP)
            .put("savedAt", System.currentTimeMillis()).put("factors", JSONArray(profile.factors)))
    }

    private fun tbrStateJson(store: TherapySessionStore, address: BluetoothAddress) = try {
        when (val state = store.getCurrentTbrState(address)) {
            CurrentTbrState.NoTbrOngoing -> JSONObject().put("started", false)
            is CurrentTbrState.TbrStarted -> tbrJson(state.tbr).put("started", true)
        }
    } catch (e: Exception) { JSONObject().put("errorClass", e.javaClass.simpleName) }

    private fun tbrJson(tbr: Tbr?): JSONObject = if (tbr == null) JSONObject().put("present", false) else JSONObject()
        .put("present", true).put("timestampMs", tbr.timestamp.toEpochMilliseconds()).put("percentage", tbr.percentage)
        .put("durationMinutes", tbr.durationInMinutes).put("type", tbr.type.stringId)

    private fun statusJson(status: Pump.Status) = JSONObject()
        .put("availableUnitsInReservoir", status.availableUnitsInReservoir)
        .put("activeBasalProfileNumber", status.activeBasalProfileNumber)
        .put("currentBasalRateFactor", status.currentBasalRateFactor)
        .put("tbrOngoing", status.tbrOngoing).put("remainingTbrDurationInMinutes", status.remainingTbrDurationInMinutes)
        .put("tbrPercentage", status.tbrPercentage).put("reservoirState", status.reservoirState.name)
        .put("batteryState", status.batteryState.name)

    private fun eventJson(event: Pump.Event): JSONObject = when (event) {
        is Pump.Event.TbrStarted -> tbrJson(event.tbr)
        is Pump.Event.TbrEnded -> tbrJson(event.tbr).put("endedAtMs", event.timestampWhenTbrEnded.toEpochMilliseconds())
        is Pump.Event.UnknownTbrDetected -> JSONObject().put("percentage", event.tbrPercentage)
            .put("remainingMinutes", event.remainingTbrDurationInMinutes)
        else -> JSONObject().put("type", event::class.simpleName)
    }
}
