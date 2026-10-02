package app.aaps.pump.combowatch

import android.content.Context
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

    /** When the watch last said anything at all, which is what "pump reachable" is judged by. */
    @Volatile
    var lastContactEpochMs: Long = 0L
        private set

    @Volatile
    private var lease: ControlLease? = null

    @Volatile
    private var lastLeaseSentEpochMs = 0L

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
    suspend fun renewLease(pumpSerial: String, validForMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val next = ControlLease(
            generation = lease?.generation ?: now,
            issuedAtEpochMs = now,
            expiresAtEpochMs = now + validForMs,
            pumpSerial = pumpSerial,
            controllerIsWatch = true
        )
        lease = next
        return send(ComboWatchProtocol.PATH_LEASE, next.toJson()).also { if (it) lastLeaseSentEpochMs = now }
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
        bolusKind: BolusKind? = null
    ): ComboResult {
        // A command is refused without a live lease, so make sure the watch holds a current one.
        if (System.currentTimeMillis() - lastLeaseSentEpochMs > LEASE_REFRESH_BEFORE_COMMAND_MS)
            renewLease(pumpSerial, leaseValidForMs)

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
                return ComboResult(command.id, Outcome.REFUSED, System.currentTimeMillis(), reason = "watch unreachable")
            }
            return withTimeoutOrNull(timeoutMs) { waiter.await() }
                ?: ComboResult(command.id, Outcome.UNKNOWN, System.currentTimeMillis(), reason = "no answer from the watch")
        } finally {
            pending.remove(command.id)
        }
    }

    // ---- called by the listener service ------------------------------------------------------

    fun onResultMessage(json: JSONObject) {
        lastContactEpochMs = System.currentTimeMillis()
        // What the pump did is recorded before the command that caused it returns.
        if (json.has("events")) onEvents(PumpEvent.listFromJson(json))
        val result = ComboResult.fromJson(json)
        aapsLogger.debug(LTag.PUMP, "combowatch: result ${result.id} ${result.outcome} ${result.reason ?: ""}")
        pending.remove(result.id)?.complete(result)
    }

    fun onHeartbeat(heartbeat: WatchHeartbeat) {
        lastContactEpochMs = System.currentTimeMillis()
        lastHeartbeat = heartbeat
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
    }
}
