package app.aaps.pump.combowatch

import android.content.Context
import android.content.Intent
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.combowatch.protocol.BolusKind
import app.aaps.pump.combowatch.protocol.ComboCommand
import app.aaps.pump.combowatch.protocol.ComboResult
import app.aaps.pump.combowatch.protocol.ComboWatchProtocol
import app.aaps.pump.combowatch.protocol.CommandKind
import app.aaps.pump.combowatch.protocol.ControlLease
import app.aaps.pump.combowatch.protocol.Outcome
import app.aaps.pump.combowatch.protocol.PumpEvent
import app.aaps.pump.combowatch.protocol.PumpSnapshot
import app.aaps.pump.combowatch.protocol.RegulationSnapshot
import app.aaps.pump.combowatch.protocol.TbrKind
import app.aaps.pump.combowatch.protocol.WatchHeartbeat
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's end of the link to the watch that holds the pump pairing.
 *
 * Nothing here talks to a pump. Its whole job is to grant the watch a [ControlLease] while the
 * watch-backed driver is the active one, to hand single commands over, and to keep the last
 * thing the watch said about the pump.
 */
@Singleton
class ComboWatchLink @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ComboResult>>()

    @Volatile
    var lastHeartbeat: WatchHeartbeat? = null
        private set

    /**
     * The newest reading the watch sent of the pump it holds now, whether in an answer or in a
     * heartbeat. Dropped as soon as the watch reports holding a different pump, or none.
     */
    @Volatile
    var lastSnapshot: PumpSnapshot? = null
        private set

    /** The pump the watch last said it holds, as the driver names it; null when it holds none. */
    @Volatile
    var watchPump: String? = null
        private set

    /** False until the watch has said anything about which pump it holds. */
    @Volatile
    var watchPumpKnown: Boolean = false
        private set

    /** When the watch last said anything at all, which is what "pump reachable" is judged by. */
    @Volatile
    var lastContactEpochMs: Long = 0L
        private set

    @Volatile
    private var lease: ControlLease? = null

    @Volatile
    private var lastLeaseSentEpochMs = 0L

    /** Whether the last lease got through; a line is written only when this changes. */
    @Volatile
    private var leaseReaching: Boolean? = null

    /** What the last heartbeat said about the pump; a line is written only when it changes. */
    @Volatile
    private var notedHeartbeat: Pair<Boolean, String?>? = null

    /** One line of the link's log: what went to the watch, what came back, what the pump did. */
    class Note(val atEpochMs: Long, val text: String)

    private val notes = ArrayDeque<Note>()

    /** The last lines of the link's log, oldest first. For the owner's screen; nothing reads them back. */
    fun recentNotes(): List<Note> = synchronized(notes) { notes.toList() }

    fun note(text: String) {
        synchronized(notes) {
            notes.addLast(Note(System.currentTimeMillis(), text))
            while (notes.size > MAX_NOTES) notes.removeFirst()
        }
    }

    /**
     * Set by the watch-backed driver while it is the active pump driver. Events are handed to it
     * and only then acknowledged; with no handler they are left with the watch, which keeps them.
     */
    @Volatile
    var eventHandler: ((PumpEvent) -> Unit)? = null

    /**
     * Grant or renew the lease naming the watch as controller.
     *
     * Letting it lapse is how the watch is told to stand down, so switching the phone back to
     * driving the pump directly does not depend on a message getting through - it only depends
     * on one no longer being sent.
     */
    suspend fun renewLease(pumpSerial: String, validForMs: Long, snapshot: RegulationSnapshot? = null, maxBolusTenthsIU: Int? = null): Boolean {
        val now = System.currentTimeMillis()
        val next = ControlLease(
            generation = lease?.generation ?: now,
            issuedAtEpochMs = now,
            expiresAtEpochMs = now + validForMs,
            pumpSerial = pumpSerial,
            controllerIsWatch = true,
            maxBolusTenthsIU = maxBolusTenthsIU
        )
        lease = next
        // What the watch needs if this turns out to be the last renewal for a while rides along.
        // The lease itself must go out whatever becomes of that: commands depend on it.
        val message = next.toJson().apply {
            snapshot?.let { runCatching { it.toJson() }.getOrNull() }?.let { put(RegulationSnapshot.KEY_IN_LEASE, it) }
        }
        return send(ComboWatchProtocol.PATH_LEASE, message).also { sent ->
            if (sent) lastLeaseSentEpochMs = now
            if (sent != leaseReaching) {
                leaseReaching = sent
                note(if (sent) "аренда доходит до часов, предел болюса ${maxBolusTenthsIU?.let { "%.1f ЕД".format(Locale.getDefault(), it / 10.0) } ?: "часов"}" else "аренда не доходит: часы не на связи")
            }
        }
    }

    /**
     * Keep the listener service alive between messages. Bound only for a message, it is created
     * and destroyed every few minutes, and each discarded instance is held a while longer by the
     * service library - which, in a debuggable build, the leak detector reports and dumps the heap
     * over, pausing the app each time. Started as well, one instance stays.
     */
    fun keepListenerAlive() {
        runCatching { context.startService(Intent(context, ComboWatchListenerService::class.java)) }
    }

    fun releaseListener() {
        runCatching { context.stopService(Intent(context, ComboWatchListenerService::class.java)) }
    }

    /** Revoke at once, so a watch in contact stands down now instead of when the lease runs out. */
    suspend fun revokeLease(pumpSerial: String) {
        val now = System.currentTimeMillis()
        val revoked = ControlLease(
            generation = lease?.generation ?: now,
            issuedAtEpochMs = now,
            expiresAtEpochMs = now,
            pumpSerial = pumpSerial,
            controllerIsWatch = false
        )
        lease = null
        lastLeaseSentEpochMs = 0L
        send(ComboWatchProtocol.PATH_LEASE, revoked.toJson())
    }

    /**
     * Send one command and wait for the watch's answer.
     *
     * A command that gets no answer within [timeoutMs] comes back as [Outcome.UNKNOWN] rather
     * than as a failure, because silence does not say whether the pump was changed. The caller
     * must treat that as "may have happened" - never as a reason to send it again.
     */
    suspend fun execute(
        kind: CommandKind,
        pumpSerial: String,
        leaseValidForMs: Long,
        validForMs: Long,
        timeoutMs: Long,
        percentage: Int? = null,
        durationMinutes: Int? = null,
        tbrKind: TbrKind? = null,
        force100Percent: Boolean? = null,
        bolusTenthsIU: Int? = null,
        bolusKind: BolusKind? = null,
        snapshot: RegulationSnapshot? = null,
        maxBolusTenthsIU: Int? = null
    ): ComboResult {
        // A command is refused without a live lease naming the pump it is meant for, so make
        // sure the watch holds a current one. A command that changes delivery comes straight
        // after a loop run, so it also brings the watch that run's snapshot.
        if ((lease?.pumpSerial != pumpSerial) || (snapshot != null && kind != CommandKind.STATUS) ||
            (System.currentTimeMillis() - lastLeaseSentEpochMs > LEASE_REFRESH_BEFORE_COMMAND_MS)
        ) renewLease(pumpSerial, leaseValidForMs, snapshot, maxBolusTenthsIU)

        val now = System.currentTimeMillis()
        val command = ComboCommand(
            id = UUID.randomUUID().toString(),
            leaseGeneration = lease?.generation ?: 0L,
            kind = kind,
            issuedAtEpochMs = now,
            expiresAtEpochMs = now + validForMs,
            percentage = percentage,
            durationMinutes = durationMinutes,
            tbrKind = tbrKind,
            force100Percent = force100Percent,
            bolusTenthsIU = bolusTenthsIU,
            bolusKind = bolusKind
        )
        val waiter = CompletableDeferred<ComboResult>()
        pending[command.id] = waiter
        try {
            if (!send(ComboWatchProtocol.PATH_COMMAND, command.toJson())) {
                // Nothing left the phone, so the pump was certainly not touched.
                note("→ ${describe(command)}: не отправлено, часы не на связи")
                return ComboResult(command.id, Outcome.REFUSED, System.currentTimeMillis(), reason = "watch unreachable")
            }
            note("→ ${describe(command)}")
            val result = withTimeoutOrNull(timeoutMs) { waiter.await() }
                ?: ComboResult(command.id, Outcome.UNKNOWN, System.currentTimeMillis(), reason = "no answer from the watch")
            note("← ${describe(result, System.currentTimeMillis() - now)}")
            return result
        } finally {
            pending.remove(command.id)
        }
    }

    private fun describe(command: ComboCommand): String = when (command.kind) {
        CommandKind.STATUS        -> "чтение помпы"
        CommandKind.CANCEL_TBR    -> "отмена временного базала"
        CommandKind.SET_TBR       -> "временный базал ${command.percentage} % на ${command.durationMinutes} мин"
        CommandKind.DELIVER_BOLUS -> "болюс %.1f ЕД".format(Locale.getDefault(), (command.bolusTenthsIU ?: 0) / 10.0)
    }

    private fun describe(result: ComboResult, elapsedMs: Long): String {
        val pump = result.snapshot?.let { ", помпа: ${describe(it)}" } ?: ""
        return when (result.outcome) {
            Outcome.DONE    -> "выполнено за ${elapsedMs / 1000} с$pump"
            Outcome.REFUSED -> "отказ: ${result.reason}"
            Outcome.FAILED  -> "не удалось: ${result.reason}$pump"
            Outcome.UNKNOWN -> "исход не выяснен: ${result.reason}"
        }
    }

    private fun describe(snapshot: PumpSnapshot): String = buildString {
        if (snapshot.tbrRunning && snapshot.tbrPercentage != null) append("временный базал ${snapshot.tbrPercentage} %, ещё ${snapshot.tbrRemainingMinutes ?: 0} мин")
        else append("базал профиля")
        snapshot.reservoirUnits?.let { append(", резервуар $it ЕД") }
    }

    // ---- called by the listener service ------------------------------------------------------

    fun onResultMessage(json: JSONObject) {
        lastContactEpochMs = System.currentTimeMillis()
        val result = ComboResult.fromJson(json)
        // First which pump this is about: the events below are filed by the pump they name.
        result.snapshot?.let { snapshot ->
            lastSnapshot = snapshot
            snapshot.pumpSerial?.let { watchPump = it; watchPumpKnown = true }
        }
        // What the pump did is recorded before the command that caused it returns.
        if (json.has("events")) onEvents(PumpEvent.listFromJson(json))
        aapsLogger.debug(LTag.PUMP, "combowatch: result ${result.id} ${result.outcome} ${result.reason ?: ""}")
        pending.remove(result.id)?.complete(result)
    }

    fun onHeartbeat(heartbeat: WatchHeartbeat) {
        lastContactEpochMs = System.currentTimeMillis()
        lastHeartbeat = heartbeat
        val state = heartbeat.pumpReachable to heartbeat.heldPump
        if (state != notedHeartbeat) {
            notedHeartbeat = state
            note(
                when {
                    heartbeat.heldPump == null -> "часы: помпа не привязана"
                    heartbeat.pumpReachable    -> "часы: помпа ${heartbeat.heldPump} отвечает"
                    else                       -> "часы: помпа ${heartbeat.heldPump} не отвечает"
                }
            )
        }
        watchPump = heartbeat.heldPump
        watchPumpKnown = true
        heartbeat.snapshot?.let { lastSnapshot = it }
        // A reading of a pump the watch no longer holds says nothing about the one it holds now.
        if (lastSnapshot?.pumpSerial != heartbeat.heldPump) lastSnapshot = null
    }

    fun onEvents(events: List<PumpEvent>) {
        lastContactEpochMs = System.currentTimeMillis()
        val handler = eventHandler ?: return
        var handledUpTo = 0L
        for (event in events.sortedBy { it.seq }) {
            try {
                handler(event)
                handledUpTo = event.seq
            } catch (t: Throwable) {
                // Stop at the first one that could not be recorded; the watch resends from there.
                aapsLogger.error(LTag.PUMP, "combowatch: could not record event ${event.seq}: ${t.message}")
                break
            }
        }
        if (handledUpTo > 0L)
            scope.launch { send(ComboWatchProtocol.PATH_EVENTS_ACK, JSONObject().put("upTo", handledUpTo)) }
    }

    private suspend fun send(path: String, payload: JSONObject): Boolean {
        val nodes = runCatching { Wearable.getNodeClient(context).connectedNodes.await() }.getOrElse {
            aapsLogger.debug(LTag.PUMP, "combowatch: cannot list watch nodes: ${it.message}")
            emptyList()
        }
        if (nodes.isEmpty()) {
            aapsLogger.debug(LTag.PUMP, "combowatch: no watch connected")
            return false
        }
        val bytes = payload.toString().toByteArray()
        var delivered = false
        for (node in nodes) {
            runCatching { Wearable.getMessageClient(context).sendMessage(node.id, path, bytes).await() }
                .onSuccess { delivered = true }
                .onFailure { aapsLogger.debug(LTag.PUMP, "combowatch: send to ${node.displayName} failed: ${it.message}") }
        }
        return delivered
    }

    companion object {

        /** A lease sent this recently is taken to be current; older than this, it is renewed first. */
        private const val LEASE_REFRESH_BEFORE_COMMAND_MS = 2 * 60_000L

        /** About two hours of a loop's traffic. */
        private const val MAX_NOTES = 150
    }
}
