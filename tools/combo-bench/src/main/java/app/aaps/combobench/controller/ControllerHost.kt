package app.aaps.combobench.controller

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import app.aaps.combobench.BenchFiles
import app.aaps.combobench.BuildConfig
import app.aaps.combobench.ManualPumpRuntime
import app.aaps.combobench.TherapySessionPolicy
import app.aaps.pump.combowatch.executor.ComboExecutor
import app.aaps.pump.combowatch.executor.CommandGate
import app.aaps.pump.combowatch.executor.CommandJournal
import app.aaps.pump.combowatch.executor.EventOutbox
import app.aaps.pump.combowatch.executor.SimpleCommandJournal
import app.aaps.pump.combowatch.protocol.ComboCommand
import app.aaps.pump.combowatch.protocol.ComboResult
import app.aaps.pump.combowatch.protocol.ComboWatchProtocol
import app.aaps.pump.combowatch.protocol.CommandKind
import app.aaps.pump.combowatch.protocol.ControlLease
import app.aaps.pump.combowatch.protocol.Outcome
import app.aaps.pump.combowatch.protocol.PumpEvent
import app.aaps.pump.combowatch.protocol.PumpSnapshot
import app.aaps.pump.combowatch.protocol.WatchHeartbeat
import org.json.JSONArray
import org.json.JSONObject

/**
 * The watch-side controller: holds the lease, the journal and the event outbox, and runs commands
 * from the phone against the pump one at a time.
 *
 * It talks to the phone only through the relay in the AAPS watch app, because the Wear data layer
 * connects apps of the same package and the phone's counterpart is AAPS. Everything that matters
 * for safety - who may control the pump, what already ran, what the pump did - lives here, next to
 * the pump pairing, and survives restarts on disk.
 */
internal class ControllerHost private constructor(context: Context) {

    private val context = context.applicationContext
    private val files = BenchFiles(context)

    private val journal: CommandJournal = SimpleCommandJournal(initial = loadJournal(), persist = ::saveJournal)
    private val outbox = loadOutbox()

    @Volatile private var lease: ControlLease? = loadLease()
    @Volatile private var lastSnapshot: PumpSnapshot? = null
    @Volatile private var pumpReachable = false

    private val session = DriverPumpSession(
        context, files,
        onEvent = { outbox.append(it) },
        onPumpRead = { snapshot, boluses ->
            lastSnapshot = snapshot
            pumpReachable = true
            executor.reconcile(snapshot, boluses)
        }
    )

    private val executor: ComboExecutor = ComboExecutor(
        CommandGate({ System.currentTimeMillis() }, { maxBolusTenthsIU() }),
        journal, session
    ) { System.currentTimeMillis() }

    val isBusy: Boolean get() = executor.isBusy

    // ---- inbound, called on the controller's worker thread ---------------------------------------

    fun onLease(payload: String) {
        val incoming = ControlLease.fromJson(JSONObject(payload))
        val current = lease
        // An older generation must not replace a newer one: a renewal that was delayed on the way
        // would otherwise hand control back to a phone session that has since been replaced.
        if ((current != null) && (incoming.generation < current.generation)) return
        lease = incoming
        files.write(LEASE_FILE, incoming.toJson())
        sendHeartbeat()
    }

    fun onCommand(payload: String): ComboResult {
        val command = ComboCommand.fromJson(JSONObject(payload))
        // The bench's own manual sessions use the same pump and pairing; never overlap with them.
        if (ManualPumpRuntime.get(context).let { it.otherWorkActive() || it.therapy.isActive() })
            return ComboResult(command.id, Outcome.REFUSED, System.currentTimeMillis(), reason = "the bench is using the pump")
                .also { publish(it) }

        val result = executor.execute(command, lease)
        if (result.outcome == Outcome.FAILED && result.reason?.startsWith("pump not reached") == true) pumpReachable = false
        result.snapshot?.let { lastSnapshot = it }
        files.write(RESULT_FILE, result.toJson())
        publish(result)
        return result
    }

    fun onEventsAck(payload: String) {
        outbox.acknowledge(JSONObject(payload).getLong("upTo"))
    }

    /** Settle an unclear ending by reading the pump; see [ComboExecutor.reconcileNow]. */
    fun healIfNeeded(): Boolean {
        if (!executor.awaitingReconciliation) return true
        val healed = executor.reconcileNow()
        sendEvents()
        sendHeartbeat()
        return healed
    }

    val awaitingReconciliation: Boolean get() = executor.awaitingReconciliation

    // ---- outbound --------------------------------------------------------------------------------

    private fun publish(result: ComboResult) {
        toPhone(ComboWatchProtocol.PATH_RESULT, result.toJson())
        sendEvents()
        sendHeartbeat()
    }

    fun sendEvents() {
        val pending = outbox.pending()
        if (pending.isNotEmpty()) toPhone(ComboWatchProtocol.PATH_EVENTS, PumpEvent.listToJson(pending))
    }

    fun sendHeartbeat() {
        val now = System.currentTimeMillis()
        val current = lease
        val heartbeat = WatchHeartbeat(
            atEpochMs = now,
            leaseGeneration = current?.generation ?: 0L,
            leaseLive = current?.liveAt(now) == true,
            executorBusy = executor.isBusy,
            awaitingReconciliation = executor.awaitingReconciliation,
            pumpReachable = pumpReachable,
            watchBatteryPercent = context.getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 },
            snapshot = lastSnapshot
        )
        files.write(HEARTBEAT_FILE, heartbeat.toJson())
        toPhone(ComboWatchProtocol.PATH_HEARTBEAT, heartbeat.toJson())
    }

    /** Hand a message to the relay in the AAPS watch app, which owns the link to the phone. */
    private fun toPhone(path: String, payload: JSONObject) {
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_TO_PHONE)
                    .setComponent(ComponentName(RELAY_PACKAGE, RELAY_RECEIVER))
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(EXTRA_PATH, path)
                    .putExtra(EXTRA_PAYLOAD, payload.toString()),
                PERMISSION_RELAY
            )
        }
    }

    // ---- debug, for exercising the controller from adb without a phone ---------------------------

    /**
     * Grant a short lease locally. Only for debuggable builds driven from adb: it stands in for
     * the phone so that the controller can be tested against the pump on its own.
     */
    fun grantDebugLease(validForMs: Long): ControlLease {
        check(BuildConfig.DEBUG) { "debug lease is only available in debug builds" }
        val now = System.currentTimeMillis()
        val granted = ControlLease(
            generation = maxOf(lease?.generation ?: 0L, DEBUG_GENERATION),
            issuedAtEpochMs = now, expiresAtEpochMs = now + validForMs,
            pumpSerial = TherapySessionPolicy.PUMP, controllerIsWatch = true
        )
        lease = granted
        files.write(LEASE_FILE, granted.toJson())
        return granted
    }

    fun stateJson(): JSONObject = JSONObject()
        .put("busy", executor.isBusy)
        .put("awaitingReconciliation", executor.awaitingReconciliation)
        .put("lease", lease?.toJson() ?: JSONObject.NULL)
        .put("leaseLive", lease?.liveAt(System.currentTimeMillis()) == true)
        .put("pendingEvents", outbox.pending().size)
        .put("droppedEvents", outbox.droppedCount)
        .put("journal", JSONArray().apply { journal.entries().takeLast(12).forEach { put(entryJson(it)) } })

    // ---- limits ----------------------------------------------------------------------------------

    private fun maxBolusTenthsIU(): Int = runCatching {
        if (files.exists(LIMITS_FILE)) files.read(LIMITS_FILE).getInt("maxBolusTenthsIU") else CommandGate.DEFAULT_MAX_BOLUS_TENTHS_IU
    }.getOrDefault(CommandGate.DEFAULT_MAX_BOLUS_TENTHS_IU)

    // ---- persistence -----------------------------------------------------------------------------

    private fun entryJson(entry: CommandJournal.Entry) = JSONObject()
        .put("id", entry.id).put("startedAt", entry.startedAtEpochMs)
        .put("outcome", entry.outcome?.name ?: JSONObject.NULL)
        .put("reason", entry.reason ?: JSONObject.NULL)
        .put("kind", entry.kind?.name ?: JSONObject.NULL)
        .put("bolusTenthsIU", entry.bolusTenthsIU ?: JSONObject.NULL)
        .put("tbrPercentage", entry.tbrPercentage ?: JSONObject.NULL)

    private fun saveJournal(entries: List<CommandJournal.Entry>) {
        files.write(JOURNAL_FILE, JSONObject().put("entries", JSONArray().apply { entries.forEach { put(entryJson(it)) } }))
    }

    private fun loadJournal(): List<CommandJournal.Entry> {
        if (!files.exists(JOURNAL_FILE)) return emptyList()
        // A journal that cannot be read must not be mistaken for an empty one: that would forget
        // an unresolved command. Let it throw; the controller then refuses to start.
        val array = files.read(JOURNAL_FILE).getJSONArray("entries")
        return List(array.length()) { index ->
            val json = array.getJSONObject(index)
            CommandJournal.Entry(
                id = json.getString("id"),
                startedAtEpochMs = json.getLong("startedAt"),
                outcome = if (json.isNull("outcome")) null else Outcome.valueOf(json.getString("outcome")),
                reason = if (json.isNull("reason")) null else json.getString("reason"),
                kind = if (json.isNull("kind")) null else CommandKind.valueOf(json.getString("kind")),
                bolusTenthsIU = if (json.isNull("bolusTenthsIU")) null else json.getInt("bolusTenthsIU"),
                tbrPercentage = if (json.isNull("tbrPercentage")) null else json.getInt("tbrPercentage")
            )
        }
    }

    private fun loadOutbox(): EventOutbox {
        val persist = { events: List<PumpEvent>, nextSeq: Long ->
            files.write(OUTBOX_FILE, PumpEvent.listToJson(events).put("nextSeq", nextSeq))
        }
        if (!files.exists(OUTBOX_FILE)) return EventOutbox(persist = persist)
        val saved = files.read(OUTBOX_FILE)
        return EventOutbox(PumpEvent.listFromJson(saved), saved.getLong("nextSeq"), persist = persist)
    }

    private fun loadLease(): ControlLease? =
        if (files.exists(LEASE_FILE)) runCatching { ControlLease.fromJson(files.read(LEASE_FILE)) }.getOrNull() else null

    companion object {

        const val ACTION_FROM_PHONE = "app.aaps.combo.action.FROM_PHONE"
        const val ACTION_TO_PHONE = "app.aaps.combo.action.TO_PHONE"
        const val PERMISSION_RELAY = "app.aaps.combo.permission.RELAY"
        const val EXTRA_PATH = "path"
        const val EXTRA_PAYLOAD = "payload"

        /** The relay lives in the AAPS watch app, the only app the phone's AAPS can reach. */
        private const val RELAY_PACKAGE = "info.nightscout.androidaps"
        private const val RELAY_RECEIVER = "app.aaps.wear.combo.ComboRelayReceiver"

        private const val JOURNAL_FILE = "controller-journal.json"
        private const val OUTBOX_FILE = "controller-outbox.json"
        private const val LEASE_FILE = "controller-lease.json"
        private const val LIMITS_FILE = "controller-limits.json"
        const val RESULT_FILE = "controller-result.json"
        const val HEARTBEAT_FILE = "controller-heartbeat.json"

        /** Far below any real generation, which is a wall-clock timestamp from the phone. */
        private const val DEBUG_GENERATION = 1L

        @Volatile private var instance: ControllerHost? = null

        fun get(context: Context): ControllerHost =
            instance ?: synchronized(this) { instance ?: ControllerHost(context).also { instance = it } }
    }
}
