package app.aaps.combobench.controller

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
import app.aaps.combobench.BenchFiles
import app.aaps.combobench.BenchPairingStore
import app.aaps.combobench.BluetoothLinkObservation
import app.aaps.combobench.BuildConfig
import app.aaps.combobench.ProbeBondGuard
import app.aaps.combobench.ProbePreparation
import app.aaps.combobench.TherapySessionPolicy
import app.aaps.pump.combowatch.executor.PumpSession
import app.aaps.pump.combowatch.protocol.BolusKind
import app.aaps.pump.combowatch.protocol.BolusReceipt
import app.aaps.pump.combowatch.protocol.ComboCommand
import app.aaps.pump.combowatch.protocol.CommandKind
import app.aaps.pump.combowatch.protocol.PumpEvent
import app.aaps.pump.combowatch.protocol.PumpSnapshot
import app.aaps.pump.combowatch.protocol.TbrKind
import info.nightscout.comboctl.android.AndroidBluetoothDevice
import info.nightscout.comboctl.base.BluetoothAddress
import info.nightscout.comboctl.base.BluetoothDevice
import info.nightscout.comboctl.base.ComboIOException
import info.nightscout.comboctl.base.CurrentTbrState
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.DriverProfile
import info.nightscout.comboctl.base.Tbr
import info.nightscout.comboctl.base.toBluetoothAddress
import info.nightscout.comboctl.main.BasalProfile
import info.nightscout.comboctl.main.Pump
import info.nightscout.comboctl.main.QuantityNotChangingException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

/**
 * Runs one command from the phone against the pump with the real AAPS driver.
 *
 * Every connection is bounded and self-contained: connect (the driver's own checks run), do the
 * one thing that was asked, disconnect. The classification of how it ended is the important part,
 * because the executor above acts on it:
 *
 * - **Failed** is only reported when it is known what the pump did - the pump was never reached,
 *   the driver gave up before confirming an edit (so the pump discarded it), or the pump's own
 *   read-back showed something else.
 * - **Unknown** is everything else that went wrong after the command started. It is never guessed
 *   at; the executor then holds further therapy until the pump has been read back.
 *
 * After each connection the driver's persisted TBR record is made to match what the pump shows.
 * The driver cancels a TBR it does not recognise the next time it connects - a therapy action
 * nobody asked for - so it must never be left guessing.
 */
@OptIn(ExperimentalTime::class)
internal class DriverPumpSession(
    context: Context,
    private val files: BenchFiles,
    /** Receives everything the driver observed on the pump; the caller numbers and stores it. */
    private val onEvent: (PumpEvent) -> Unit,
    /** Called once per connection with what the pump showed and the boluses found in its history. */
    private val onPumpRead: (PumpSnapshot, List<BolusReceipt>) -> Unit
) : PumpSession {

    private val context = context.applicationContext

    /**
     * The fork's AndroidBluetoothDevice watchdog and disconnect() make reads return empty lists and
     * writes succeed silently, which leaves ComboCtl spinning instead of failing. Turn that into
     * [ComboIOException] so the driver's own error paths run and every timeout here can fire.
     */
    private class FailingWhenClosedTransport(private val inner: AndroidBluetoothDevice) : BluetoothDevice(Dispatchers.IO) {
        @Volatile var closed = false
            private set
        override val address = inner.address
        override fun connect() { closed = false; inner.connect() }
        override fun disconnect() { closed = true; inner.disconnect() }
        override fun unpair() = error("Unpairing is not part of a controller session")
        override fun blockingSend(dataToSend: List<Byte>) {
            if (closed) throw ComboIOException("Controller transport closed")
            inner.blockingSend(dataToSend)
        }
        override fun blockingReceive(): List<Byte> {
            val data = inner.blockingReceive()
            if (data.isEmpty()) throw ComboIOException("Controller transport closed or idle watchdog fired")
            return data
        }
    }

    private class Connected(
        val pump: Pump,
        val store: ControllerPumpStore,
        val address: BluetoothAddress,
        val statusAtConnect: Pump.Status
    )

    private sealed interface Attempt<out T> {
        data class Reached<T>(val value: T) : Attempt<T>
        data class NotReached(val reason: String) : Attempt<Nothing>
    }

    /** One step of a temporary-basal command, on its own connection. */
    private sealed interface Stage {
        data class Done(val snapshot: PumpSnapshot, val outcome: String, val percentage: Int, val duration: Int, val final: Boolean) : Stage
        data class Failed(val reason: String, val snapshot: PumpSnapshot?) : Stage
        data class Unknown(val reason: String) : Stage
    }

    override fun run(command: ComboCommand): PumpSession.SessionResult = runBlocking(Dispatchers.Default) {
        // Which RT pacing the driver uses can be chosen in the limits file, so that the two can be
        // compared on the pump instead of argued about.
        DriverProfile.confirmedStepPacing = runCatching {
            !files.exists(LIMITS_FILE) || files.read(LIMITS_FILE).optBoolean("confirmedStepPacing", true)
        }.getOrDefault(true)
        val record = SessionRecord(files, command)
        record.phase(if (DriverProfile.confirmedStepPacing) "PACING_CONFIRMED_STEPS" else "PACING_STANDARD")
        val result = try {
            when (command.kind) {
                CommandKind.STATUS        -> status(record)
                CommandKind.DELIVER_BOLUS -> bolus(command, record)
                CommandKind.SET_TBR,
                CommandKind.CANCEL_TBR    -> tbr(command, record)
            }
        } catch (e: Exception) {
            // Anything that escaped the per-command handling ended at a point this code cannot name.
            PumpSession.SessionResult.Unknown("${e.javaClass.simpleName}: ${e.message?.take(120) ?: ""}")
        }
        record.finish(result)
        result
    }

    // ---- STATUS ---------------------------------------------------------------------------------

    private suspend fun status(record: SessionRecord): PumpSession.SessionResult =
        when (val attempt = withPump(record) { snapshot(it.pump, it) }) {
            is Attempt.NotReached -> PumpSession.SessionResult.Failed("pump not reached: ${attempt.reason}")
            is Attempt.Reached    -> PumpSession.SessionResult.Done(attempt.value)
        }

    // ---- BOLUS ----------------------------------------------------------------------------------

    private suspend fun bolus(command: ComboCommand, record: SessionRecord): PumpSession.SessionResult {
        val amount = checkNotNull(command.bolusTenthsIU)
        val reason = when (checkNotNull(command.bolusKind)) {
            BolusKind.NORMAL  -> Pump.StandardBolusReason.NORMAL
            BolusKind.SMB     -> Pump.StandardBolusReason.SUPERBOLUS
            BolusKind.PRIMING -> Pump.StandardBolusReason.PRIMING_INFUSION_SET
        }
        val attempt = withPump(record) { connected ->
            val pump = connected.pump
            if (pump.stateFlow.value != Pump.State.ReadyForCommands)
                return@withPump PumpSession.SessionResult.Failed(
                    "pump is ${pump.stateFlow.value::class.simpleName}, not ready for a bolus", snapshot(pump, connected)
                )
            var delivered: Pump.LastBolus? = null
            record.phase("BOLUS_STARTED", JSONObject().put("tenthsIU", amount))
            try {
                withTimeout(BOLUS_TIMEOUT_MS) {
                    pump.deliverBolus(amount, reason, onStandardBolusRecorded = { delivered = it })
                }
            } catch (e: Pump.BolusNotDeliveredException) {
                return@withPump PumpSession.SessionResult.Failed("the pump did not start the bolus", snapshotOrNull(pump, connected))
            } catch (e: Pump.InsufficientInsulinAvailableException) {
                return@withPump PumpSession.SessionResult.Failed(
                    "not enough insulin in the reservoir (${e.availableUnitsInReservoir} U)", snapshotOrNull(pump, connected)
                )
            } catch (e: Pump.BolusCancelledByUserException) {
                // The pump's history is what counts for the amount; this exception carries the
                // same figure and is reported so the phone sees it without waiting for the event.
                return@withPump PumpSession.SessionResult.Failed(
                    "bolus stopped on the pump after ${e.deliveredImmediateAmount} of ${e.totalAmount} tenths",
                    snapshotOrNull(pump, connected),
                    delivered?.toReceipt()
                )
            } catch (e: Pump.BolusAbortedDueToErrorException) {
                return@withPump PumpSession.SessionResult.Unknown("bolus aborted by a pump error; amount delivered not established")
            }
            // Anything else propagates and is reported as unknown by the caller: the bolus command
            // had been sent, and only the pump's history can say what it did with it.
            val receipt = delivered?.toReceipt()
                ?: return@withPump PumpSession.SessionResult.Unknown("bolus sent but the pump's history showed no record of it")
            record.phase("BOLUS_RECORDED", JSONObject().put("tenthsIU", receipt.tenthsIU).put("bolusId", receipt.bolusId))
            runCatching { withTimeout(TherapySessionPolicy.STATUS_TIMEOUT_MS) { pump.updateStatus() } }
            PumpSession.SessionResult.Done(snapshotOrNull(pump, connected), bolus = receipt)
        }
        return when (attempt) {
            is Attempt.NotReached -> PumpSession.SessionResult.Failed("pump not reached: ${attempt.reason}")
            is Attempt.Reached    -> attempt.value
        }
    }

    // ---- TEMPORARY BASAL ------------------------------------------------------------------------

    private class StagePlan(val percentage: Int, val duration: Int, val type: Tbr.Type, val force100: Boolean, val final: Boolean)

    /**
     * Decide what this connection should set, given what the pump shows now.
     *
     * Normally that is simply what was asked for. The staging below is a fallback for a walk
     * longer than [maxStepsPerConnection]: it is then covered in several connections, each one a
     * real, confirmed TBR on the pump and each one closer to the target. It was written when the
     * controller could not complete a long walk in one connection, which turned out to be caused
     * by the controller's own overhead (see DriverProfile in the driver) rather than by the pump;
     * with that fixed a 20-step walk takes one connection, and the default limit is high enough
     * that staging does not occur. It stays as a tested way to degrade if a walk ever does fail.
     */
    private fun plan(command: ComboCommand, status: Pump.Status): StagePlan {
        val current = if (status.tbrOngoing) status.tbrPercentage else 100
        val cancel = command.kind == CommandKind.CANCEL_TBR
        val force100 = cancel && (command.force100Percent == true)
        // Where the driver will end up: a real cancel is 100, an emulated one is the 90 / 110 the
        // driver itself picks, and a SET_TBR is what was asked.
        val target = when {
            !cancel               -> checkNotNull(command.percentage)
            force100              -> 100
            current in 90..110    -> current // the driver lets a short one finish or replaces it in place
            current < 100         -> 110
            else                  -> 90
        }
        val duration = if (cancel) 15 else checkNotNull(command.durationMinutes)
        val type = when (command.tbrKind) {
            TbrKind.SUPERBOLUS    -> Tbr.Type.SUPERBOLUS
            TbrKind.EMULATED_STOP -> Tbr.Type.EMULATED_COMBO_STOP
            else                  -> Tbr.Type.NORMAL
        }
        val steps = (target - current).absoluteValue / 10
        if (cancel) {
            if (steps <= maxStepsPerConnection())
                return StagePlan(100, 0, Tbr.Type.NORMAL, force100, final = true)
        } else if ((target == 0) || (steps <= maxStepsPerConnection()))
            return StagePlan(target, duration, type, force100 = false, final = true)

        val stages = ceil(steps / maxStepsPerConnection().toDouble()).toInt()
        val stepsNow = ceil(steps / stages.toDouble()).toInt()
        val direction = if (target > current) 1 else -1
        var intermediate = current + direction * stepsNow * 10
        // 100 is not a TBR the pump can hold, so an intermediate stop never lands on it.
        if (intermediate == 100) intermediate += direction * 10
        return StagePlan(intermediate, duration, Tbr.Type.NORMAL, force100 = false, final = false)
    }

    /** From the controller's limits file when present, so the stage size can be tuned on the watch. */
    private fun maxStepsPerConnection(): Int = runCatching {
        if (files.exists(LIMITS_FILE)) files.read(LIMITS_FILE).optInt("maxStepsPerConnection", MAX_STEPS_PER_CONNECTION)
        else MAX_STEPS_PER_CONNECTION
    }.getOrDefault(MAX_STEPS_PER_CONNECTION).coerceIn(1, 50)

    private suspend fun tbr(command: ComboCommand, record: SessionRecord): PumpSession.SessionResult {
        var lastSnapshot: PumpSnapshot? = null
        repeat(MAX_STAGES) { index ->
            val attempt = withPump(record) { connected -> tbrStage(command, connected, record) }
            when (attempt) {
                is Attempt.NotReached ->
                    return PumpSession.SessionResult.Failed(
                        if (index == 0) "pump not reached: ${attempt.reason}"
                        else "stage ${index + 1}: pump not reached (${attempt.reason}); the pump holds the previous stage's TBR",
                        lastSnapshot
                    )

                is Attempt.Reached    -> when (val stage = attempt.value) {
                    is Stage.Unknown -> return PumpSession.SessionResult.Unknown(stage.reason)
                    is Stage.Failed  -> return PumpSession.SessionResult.Failed(stage.reason, stage.snapshot ?: lastSnapshot)
                    is Stage.Done    -> {
                        lastSnapshot = stage.snapshot
                        if (stage.final)
                            return PumpSession.SessionResult.Done(stage.snapshot, stage.outcome, stage.percentage, stage.duration)
                        record.phase("STAGE_${index + 1}_DONE", JSONObject().put("percentage", stage.percentage))
                    }
                }
            }
        }
        return PumpSession.SessionResult.Failed("gave up after $MAX_STAGES stages; the pump holds the last stage's TBR", lastSnapshot)
    }

    private suspend fun tbrStage(command: ComboCommand, connected: Connected, record: SessionRecord): Stage {
        val pump = connected.pump
        if (pump.stateFlow.value != Pump.State.ReadyForCommands)
            return Stage.Failed("pump is ${pump.stateFlow.value::class.simpleName}, not ready for commands", snapshot(pump, connected))

        val plan = plan(command, connected.statusAtConnect)
        record.phase(
            "TBR_STAGE",
            JSONObject().put("percentage", plan.percentage).put("duration", plan.duration).put("final", plan.final)
                .put("from", if (connected.statusAtConnect.tbrOngoing) connected.statusAtConnect.tbrPercentage else 100)
        )

        // The driver programs the pump before it records the TBR. Record the intent first, so that
        // a failure in between cannot make the next connection cancel this TBR as one it does not know.
        val previous = connected.store.getCurrentTbrState(connected.address)
        if (plan.percentage != 100)
            connected.store.setCurrentTbrState(
                connected.address,
                CurrentTbrState.TbrStarted(Tbr(Clock.System.now(), plan.percentage, plan.duration, plan.type))
            )

        val outcome = try {
            withTimeout(TherapySessionPolicy.COMMAND_TIMEOUT_MS) {
                pump.setTbr(plan.percentage, plan.duration, plan.type, plan.force100)
            }
        } catch (e: QuantityNotChangingException) {
            // Raised while walking the value, before anything was confirmed: the pump dropped the
            // edit, so it holds exactly what it held before.
            connected.store.setCurrentTbrState(connected.address, previous)
            return Stage.Failed(
                "the pump stopped accepting presses at ${e.hitLimitAt} % on the way to ${e.targetQuantity} %; its TBR is unchanged",
                snapshot(pump, connected, connected.statusAtConnect)
            )
        } catch (e: Pump.UnexpectedTbrStateException) {
            // The driver read the main screen back and it is not what was asked for. That reading
            // is the truth about the pump, so every record is made to match it.
            val actualPercentage = e.actualTbrPercentage ?: return Stage.Unknown("TBR read-back was inconclusive")
            val actualDuration = e.actualTbrDuration ?: 0
            recordActualTbr(connected, previous, actualPercentage, actualDuration)
            return Stage.Failed(
                "the pump shows $actualPercentage % for $actualDuration min instead of ${e.expectedTbrPercentage} %",
                snapshot(pump, connected, connected.statusAtConnect, actualPercentage, actualDuration)
            )
        }
        // Any other exception propagates: the command was under way and nothing here can say
        // whether the confirming press landed. The intent stays recorded for the reconciliation.

        withTimeout(TherapySessionPolicy.STATUS_TIMEOUT_MS) { pump.updateStatus() }
        val after = snapshot(pump, connected)
        return Stage.Done(after, outcome.name, after.tbrPercentage ?: 100, after.tbrRemainingMinutes ?: 0, plan.final)
    }

    /**
     * Make the driver's persisted TBR record say what the pump was just seen to be running, and
     * tell the phone about a TBR nobody asked for so its own records account for it.
     */
    private fun recordActualTbr(connected: Connected, previous: CurrentTbrState, actualPercentage: Int, actualDuration: Int) {
        val now = Clock.System.now()
        if (actualPercentage == 100) {
            // No TBR on the pump. Leaving the previous record lets the driver report that one as
            // ended on its next connection, which is exactly what happened to it.
            connected.store.setCurrentTbrState(connected.address, previous)
            return
        }
        val previousTbr = (previous as? CurrentTbrState.TbrStarted)?.tbr
        if ((previousTbr != null) && (previousTbr.percentage == actualPercentage)) {
            connected.store.setCurrentTbrState(connected.address, previous)
            return
        }
        // Recorded with a start time that makes its remaining duration what the pump shows.
        val roundedDuration = ((actualDuration + 14) / 15).coerceAtLeast(1) * 15
        val startedAt = now - (roundedDuration - actualDuration).minutes
        connected.store.setCurrentTbrState(
            connected.address,
            CurrentTbrState.TbrStarted(Tbr(startedAt, actualPercentage, roundedDuration, Tbr.Type.NORMAL))
        )
        if (previousTbr != null)
            onEvent(PumpEvent(0, PumpEvent.Type.TBR_ENDED, now.toEpochMilliseconds()))
        onEvent(
            PumpEvent(
                0, PumpEvent.Type.TBR_STARTED, startedAt.toEpochMilliseconds(),
                tbrPercentage = actualPercentage, tbrDurationMinutes = roundedDuration, tbrType = Tbr.Type.NORMAL.stringId
            )
        )
    }

    // ---- one bounded connection -----------------------------------------------------------------

    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    private suspend fun <T> withPump(record: SessionRecord, block: suspend (Connected) -> T): Attempt<T> {
        val pumpId = TherapySessionPolicy.PUMP
        val address = TherapySessionPolicy.ADDRESS
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        if ((adapter == null) || !adapter.isEnabled) return Attempt.NotReached("Bluetooth is off")
        val device = adapter.getRemoteDevice(address)
        if (device.bondState != SystemDevice.BOND_BONDED) return Attempt.NotReached("the pump is not bonded to this watch")
        val guard = ProbeBondGuard(true)
        val btAddress = address.toBluetoothAddress()
        val store = ControllerPumpStore(BenchPairingStore(context, btAddress, pumpId), files)
        if (!store.hasPumpState(btAddress)) return Attempt.NotReached("no pairing stored for the pump")

        val power = context.getSystemService(PowerManager::class.java)
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ComboController:session")
        val aclConnected = AtomicBoolean(false)
        val aclDisconnected = AtomicBoolean(false)
        val transport = FailingWhenClosedTransport(
            AndroidBluetoothDevice(context, adapter, btAddress, watchdogTimeoutMs = TherapySessionPolicy.BLUETOOTH_WATCHDOG_MS)
        )
        val flows = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val driverLog = DriverLog()
        val boluses = mutableListOf<BolusReceipt>()

        val receiver = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(context: Context, intent: Intent) {
                val remote = intent.getParcelableExtra<SystemDevice>(SystemDevice.EXTRA_DEVICE) ?: return
                if (remote.address != address) return
                if (intent.action == SystemDevice.ACTION_ACL_CONNECTED) { aclConnected.set(true); aclDisconnected.set(false) }
                if (intent.action == SystemDevice.ACTION_ACL_DISCONNECTED) aclDisconnected.set(true)
                val state = intent.getIntExtra(SystemDevice.EXTRA_BOND_STATE, remote.bondState)
                val previous = intent.getIntExtra(SystemDevice.EXTRA_PREVIOUS_BOND_STATE, state)
                guard.observe(
                    state == SystemDevice.BOND_BONDED,
                    pairingRequested = intent.action == SystemDevice.ACTION_PAIRING_REQUEST,
                    bondChanged = intent.action == SystemDevice.ACTION_BOND_STATE_CHANGED && state != previous
                )
                if (guard.stopReason != null) {
                    record.phase("BOND_GUARD_${guard.stopReason}")
                    work.cancel()
                    runCatching { transport.disconnect() }
                }
            }
        }

        val pump = Pump(transport, store, loadBasalProfile()) { event ->
            record.phase("PUMP_EVENT_${event::class.simpleName}")
            forward(event, boluses)
        }

        var registered = false
        var connected = false
        var value: Result<T>? = null
        var notReached: String? = null
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
            flows.launch {
                pump.stateFlow.collect { record.phase("STATE_${it::class.simpleName}") }
            }
            driverLog.install()
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
                check(before.has("connected") && !before.getBoolean("connected")) { "previous connection not confirmed closed" }
                ProbePreparation.stopDiscovery(
                    { adapter.isDiscovering }, { adapter.cancelDiscovery() }, SystemClock::elapsedRealtime, Thread::sleep
                )
                check(guard.stopReason == null) { "bond changed" }
                record.phase("CONNECTING")
                withTimeout(TherapySessionPolicy.CONNECT_TIMEOUT_MS) { pump.connect(maxNumAttempts = 3) }
                val status = checkNotNull(pump.statusFlow.value) { "no pump status after connecting" }
                pump.currentBasalProfile?.let { saveBasalProfile(it) }
                connected = true
                record.phase("CONNECTED", statusJson(status))
                val session = Connected(pump, store, btAddress, status)
                // What the pump showed, and what its history added, before this command touched it.
                onPumpRead(snapshot(pump, session), boluses.toList())
                val sampler = if (BuildConfig.DEBUG && files.exists(PROFILE_SWITCH_FILE)) StackSampler().also { it.start() } else null
                try {
                    block(session)
                } finally {
                    sampler?.let { record.profile(it.stop()) }
                }
            }
            value = runCatching { withTimeout(TherapySessionPolicy.TOTAL_TIMEOUT_MS) { job.await() } }
            if (!connected && value?.isFailure == true) {
                val error = value!!.exceptionOrNull()
                notReached = "${error?.javaClass?.simpleName}: ${error?.message?.take(120) ?: ""}"
            }
        } catch (e: Exception) {
            if (!connected) notReached = "${e.javaClass.simpleName}: ${e.message?.take(120) ?: ""}"
            else value = Result.failure(e)
        } finally {
            withContext(NonCancellable) {
                work.cancel()
                driverLog.uninstall()
                record.driverLog(driverLog.snapshot())
                pump.currentBasalProfile?.let { saveBasalProfile(it) }
                try {
                    withTimeout(60_000L) { pump.disconnect() }
                    record.phase("DISCONNECTED")
                } catch (e: Exception) {
                    record.phase("DISCONNECT_FAILED_${e.javaClass.simpleName}")
                }
                runCatching { transport.disconnect() }
                flows.cancel()
                try {
                    if (registered) {
                        val until = SystemClock.elapsedRealtime() + TherapySessionPolicy.LINK_CLOSE_WAIT_MS
                        while (!aclDisconnected.get() && aclConnected.get() && SystemClock.elapsedRealtime() < until) delay(50)
                    }
                } finally {
                    if (registered) runCatching { context.unregisterReceiver(receiver) }
                    runCatching { if (wake.isHeld) wake.release() }
                }
                record.phase("CONNECTION_CLOSED", JSONObject().put("packetsSent", store.advances).put("nonceWrites", store.markWrites))
            }
        }
        notReached?.let { return Attempt.NotReached(it) }
        // A failure after the pump was reached is rethrown, so that the command's own handling
        // (or, failing that, the catch-all in run()) classifies it.
        return Attempt.Reached(checkNotNull(value).getOrThrow())
    }

    // ---- driver events -> protocol events -------------------------------------------------------

    private fun forward(event: Pump.Event, boluses: MutableList<BolusReceipt>) {
        when (event) {
            is Pump.Event.StandardBolusInfused -> {
                val at = event.timestamp.toEpochMilliseconds()
                synchronized(boluses) { boluses.add(BolusReceipt(event.bolusId, at, event.bolusAmount)) }
                onEvent(
                    PumpEvent(
                        0, PumpEvent.Type.BOLUS_INFUSED, at, bolusId = event.bolusId, bolusTenthsIU = event.bolusAmount,
                        bolusKind = when (event.standardBolusReason) {
                            Pump.StandardBolusReason.NORMAL               -> BolusKind.NORMAL
                            Pump.StandardBolusReason.SUPERBOLUS           -> BolusKind.SMB
                            Pump.StandardBolusReason.PRIMING_INFUSION_SET -> BolusKind.PRIMING
                        }
                    )
                )
            }

            is Pump.Event.QuickBolusInfused    -> {
                val at = event.timestamp.toEpochMilliseconds()
                synchronized(boluses) { boluses.add(BolusReceipt(event.bolusId, at, event.bolusAmount)) }
                onEvent(PumpEvent(0, PumpEvent.Type.BOLUS_INFUSED, at, bolusId = event.bolusId, bolusTenthsIU = event.bolusAmount, bolusKind = BolusKind.NORMAL))
            }

            is Pump.Event.TbrStarted           -> onEvent(
                PumpEvent(
                    0, PumpEvent.Type.TBR_STARTED, event.tbr.timestamp.toEpochMilliseconds(),
                    tbrPercentage = event.tbr.percentage, tbrDurationMinutes = event.tbr.durationInMinutes, tbrType = event.tbr.type.stringId
                )
            )

            is Pump.Event.TbrEnded             ->
                onEvent(PumpEvent(0, PumpEvent.Type.TBR_ENDED, event.timestampWhenTbrEnded.toEpochMilliseconds()))

            is Pump.Event.UnknownTbrDetected   -> onEvent(
                PumpEvent(
                    0, PumpEvent.Type.UNKNOWN_TBR_DETECTED, System.currentTimeMillis(),
                    tbrPercentage = event.tbrPercentage, tbrDurationMinutes = event.remainingTbrDurationInMinutes
                )
            )

            Pump.Event.BatteryLow              -> onEvent(PumpEvent(0, PumpEvent.Type.BATTERY_LOW, System.currentTimeMillis()))
            Pump.Event.ReservoirLow            -> onEvent(PumpEvent(0, PumpEvent.Type.RESERVOIR_LOW, System.currentTimeMillis()))
            else                               -> Unit // extended and multiwave boluses are not used here
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun Pump.LastBolus.toReceipt() = BolusReceipt(bolusId, timestamp.toEpochMilliseconds(), bolusAmount)

    private fun snapshot(
        pump: Pump,
        connected: Connected,
        status: Pump.Status = checkNotNull(pump.statusFlow.value),
        overrideTbrPercentage: Int? = null,
        overrideTbrDuration: Int? = null
    ): PumpSnapshot {
        val percentage = overrideTbrPercentage ?: status.tbrPercentage
        val running = if (overrideTbrPercentage != null) (overrideTbrPercentage != 100) else status.tbrOngoing
        return PumpSnapshot(
            readAtEpochMs = System.currentTimeMillis(),
            tbrRunning = running,
            tbrPercentage = if (running) percentage else null,
            tbrRemainingMinutes = if (running) (overrideTbrDuration ?: status.remainingTbrDurationInMinutes) else null,
            reservoirUnits = status.availableUnitsInReservoir,
            batteryState = status.batteryState.name,
            pumpSerial = TherapySessionPolicy.PUMP,
            basalProfileFactors = pump.currentBasalProfile?.let { profile -> List(profile.size) { profile[it] } }
        )
    }

    private fun snapshotOrNull(pump: Pump, connected: Connected): PumpSnapshot? =
        runCatching { snapshot(pump, connected) }.getOrNull()

    private fun statusJson(status: Pump.Status) = JSONObject()
        .put("reservoir", status.availableUnitsInReservoir)
        .put("tbrOngoing", status.tbrOngoing).put("tbrPercentage", status.tbrPercentage)
        .put("tbrRemaining", status.remainingTbrDurationInMinutes)
        .put("battery", status.batteryState.name)

    private fun loadBasalProfile(): BasalProfile? = try {
        if (!files.exists(BASAL_PROFILE_FILE)) null
        else files.read(BASAL_PROFILE_FILE).let { saved ->
            check(saved.getString("pump") == TherapySessionPolicy.PUMP)
            val array = saved.getJSONArray("factors")
            BasalProfile((0 until array.length()).map { array.getInt(it) })
        }
    } catch (_: Exception) { null }

    private fun saveBasalProfile(profile: BasalProfile) = runCatching {
        files.write(
            BASAL_PROFILE_FILE,
            JSONObject().put("pump", TherapySessionPolicy.PUMP).put("savedAt", System.currentTimeMillis())
                .put("factors", JSONArray(List(profile.size) { profile[it] }))
        )
    }

    companion object {

        /** The same file the bench's own sessions use, so the 24-screen profile read happens once. */
        private const val BASAL_PROFILE_FILE = "manual-basal-profile.json"
        private const val BOLUS_TIMEOUT_MS = 4 * 60_000L
        private const val LIMITS_FILE = "controller-limits.json"

        /** Create this file in the app's files directory to profile sessions in a debug build. */
        private const val PROFILE_SWITCH_FILE = "controller-profile-on.json"

        /**
         * How many steps one connection is asked to make before a walk is split; see [plan]. The
         * Combo's whole TBR range is 50 steps, so by default nothing is split.
         */
        private const val MAX_STEPS_PER_CONNECTION = 50
        private const val MAX_STAGES = 8
    }
}

/** Compact diagnostic record of one controller session: what was asked, what happened, driver log. */
internal class SessionRecord(private val files: BenchFiles, command: ComboCommand) {
    private val started = SystemClock.elapsedRealtime()
    private val json = JSONObject().put("id", command.id).put("at", System.currentTimeMillis())
        .put("kind", command.kind.name)
        .put("percentage", command.percentage ?: JSONObject.NULL)
        .put("durationMinutes", command.durationMinutes ?: JSONObject.NULL)
        .put("bolusTenthsIU", command.bolusTenthsIU ?: JSONObject.NULL)
        .put("complete", false)
    private val events = JSONArray()
    private val driverLogs = JSONArray()
    private var last = ""

    @Synchronized fun phase(name: String, detail: JSONObject? = null) {
        if (name == last && detail == null) return
        last = name
        val event = JSONObject().put("name", name).put("elapsedMs", SystemClock.elapsedRealtime() - started)
        if (detail != null) event.put("detail", detail)
        events.put(event)
        save()
    }

    /** One entry per connection, since a staged TBR opens several. */
    @Synchronized fun driverLog(log: JSONArray) { driverLogs.put(log) }

    @Synchronized fun profile(profile: JSONObject) { json.put("profile", profile) }

    @Synchronized fun finish(result: PumpSession.SessionResult) {
        json.put("complete", true).put("durationMs", SystemClock.elapsedRealtime() - started)
        when (result) {
            is PumpSession.SessionResult.Done    -> json.put("outcome", "DONE").put("tbrOutcome", result.tbrOutcome ?: JSONObject.NULL)
                .put("snapshot", result.snapshot?.toJson() ?: JSONObject.NULL).put("bolus", result.bolus?.toJson() ?: JSONObject.NULL)
            is PumpSession.SessionResult.Failed  -> json.put("outcome", "FAILED").put("reason", result.reason)
                .put("snapshot", result.snapshot?.toJson() ?: JSONObject.NULL).put("bolus", result.bolus?.toJson() ?: JSONObject.NULL)
            is PumpSession.SessionResult.Unknown -> json.put("outcome", "UNKNOWN").put("reason", result.reason)
        }
        save()
    }

    private fun save() = runCatching {
        val value = JSONObject(json.toString()).put("events", events).put("driverLogs", driverLogs)
        files.write("controller-session.json", value)
        if (json.optBoolean("complete")) files.write("controller-session-${json.getString("id")}.json", value)
    }
}
