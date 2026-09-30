package app.aaps.pump.combowatch

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.combowatch.protocol.ComboCommand
import app.aaps.pump.combowatch.protocol.ComboResult
import app.aaps.pump.combowatch.protocol.ComboWatchProtocol
import app.aaps.pump.combowatch.protocol.CommandKind
import app.aaps.pump.combowatch.protocol.ControlLease
import app.aaps.pump.combowatch.protocol.Outcome
import app.aaps.pump.combowatch.protocol.WatchHeartbeat
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
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
 * Nothing here talks to a pump. Its whole job is to grant the watch a [ControlLease] while this
 * plugin is the active pump driver, to hand single commands over, and to keep the last thing the
 * watch said about the pump.
 */
@Singleton
class ComboWatchLink @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {

    private val messageClient: MessageClient by lazy { Wearable.getMessageClient(context) }
    private val capabilityClient: CapabilityClient by lazy { Wearable.getCapabilityClient(context) }

    private val pending = ConcurrentHashMap<String, CompletableDeferred<ComboResult>>()

    @Volatile
    var lastHeartbeat: WatchHeartbeat? = null
        private set

    @Volatile
    private var lease: ControlLease? = null

    val leaseGeneration: Long get() = lease?.generation ?: 0L

    /**
     * Grant or renew the lease naming the watch as controller.
     *
     * The lease is short on purpose. Letting it lapse is how the watch is told to stand down, so
     * switching the phone back to driving the pump directly does not depend on a message getting
     * through — it only depends on one no longer being sent.
     */
    suspend fun renewLease(pumpSerial: String, validForMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val current = lease
        val next = ControlLease(
            generation = current?.generation ?: now,
            issuedAtEpochMs = now,
            expiresAtEpochMs = now + validForMs,
            pumpSerial = pumpSerial,
            controllerIsWatch = true
        )
        lease = next
        return send(ComboWatchProtocol.PATH_LEASE, next.toJson())
    }

    /**
     * Revoke the lease explicitly, so a watch that is currently in contact stands down at once
     * instead of after the lease runs out.
     */
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
        send(ComboWatchProtocol.PATH_LEASE, revoked.toJson())
    }

    /**
     * Send one command and wait for the watch's answer.
     *
     * A command that gets no answer within [timeoutMs] comes back as [Outcome.UNKNOWN] rather
     * than as a failure, because silence does not say whether the pump was changed. The caller
     * must treat that as "may have happened" and read the pump back — never as a reason to resend.
     */
    suspend fun execute(
        kind: CommandKind,
        percentage: Int? = null,
        durationMinutes: Int? = null,
        validForMs: Long,
        timeoutMs: Long
    ): ComboResult {
        val now = System.currentTimeMillis()
        val command = ComboCommand(
            id = UUID.randomUUID().toString(),
            leaseGeneration = leaseGeneration,
            kind = kind,
            issuedAtEpochMs = now,
            expiresAtEpochMs = now + validForMs,
            percentage = percentage,
            durationMinutes = durationMinutes
        )
        val waiter = CompletableDeferred<ComboResult>()
        pending[command.id] = waiter
        try {
            if (!send(ComboWatchProtocol.PATH_COMMAND, command.toJson())) {
                // Nothing left the phone, so the pump was certainly not touched.
                return ComboResult(command.id, Outcome.REFUSED, System.currentTimeMillis(), reason = "watch unreachable")
            }
            return withTimeoutOrNull(timeoutMs) { waiter.await() }
                ?: ComboResult(command.id, Outcome.UNKNOWN, System.currentTimeMillis(), reason = "no answer from watch")
        } finally {
            pending.remove(command.id)
        }
    }

    /** Called by the listener service when the watch answers. */
    fun onResult(result: ComboResult) {
        aapsLogger.debug(LTag.PUMP, "combowatch: result ${result.id} ${result.outcome}")
        pending.remove(result.id)?.complete(result)
    }

    /** Called by the listener service on every heartbeat. */
    fun onHeartbeat(heartbeat: WatchHeartbeat) {
        lastHeartbeat = heartbeat
    }

    /** True when the watch said something recently enough to be believed. */
    fun watchFresh(withinMs: Long): Boolean =
        lastHeartbeat?.let { System.currentTimeMillis() - it.atEpochMs < withinMs } == true

    private suspend fun send(path: String, payload: JSONObject): Boolean {
        val nodes = runCatching {
            capabilityClient
                .getCapability(ComboWatchProtocol.CAPABILITY_EXECUTOR, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
        }.getOrElse {
            aapsLogger.debug(LTag.PUMP, "combowatch: cannot look up executor nodes: ${it.message}")
            emptySet()
        }
        if (nodes.isEmpty()) {
            aapsLogger.debug(LTag.PUMP, "combowatch: no watch advertising the executor capability")
            return false
        }
        val bytes = payload.toString().toByteArray()
        var delivered = false
        for (node in nodes) {
            runCatching { messageClient.sendMessage(node.id, path, bytes).await() }
                .onSuccess { delivered = true }
                .onFailure { aapsLogger.debug(LTag.PUMP, "combowatch: send to ${node.id} failed: ${it.message}") }
        }
        return delivered
    }
}
