package app.aaps.pump.combowatch.protocol

import org.json.JSONObject

/**
 * The wire format between the phone (which decides) and the watch (which holds the pump
 * pairing and executes). Both sides depend on this file and on nothing else of each other.
 *
 * Two rules shape everything here:
 *
 * 1. The pump accepts one remote terminal. Two controllers that both believe they own the
 *    pump is the worst failure this design can have, so ownership is a *lease* the phone
 *    grants and the watch lets expire. A watch with no live lease refuses every therapy
 *    command. Silence therefore disables the watch instead of enabling it.
 *
 * 2. A command whose outcome is unclear must never be retried blindly. [Outcome.UNKNOWN]
 *    is a first-class answer, and the only way out of it is reading the pump back.
 */
object ComboWatchProtocol {

    /** Data layer paths. Phone and watch must agree on these exactly. */
    const val PATH_LEASE = "/combowatch/lease"
    const val PATH_COMMAND = "/combowatch/command"
    const val PATH_RESULT = "/combowatch/result"
    const val PATH_HEARTBEAT = "/combowatch/heartbeat"

    /** Capability the watch advertises once its executor is installed and paired to a pump. */
    const val CAPABILITY_EXECUTOR = "combowatch_executor"

    const val PROTOCOL_VERSION = 1
}

/**
 * What the phone is allowed to ask for. Deliberately not the whole [app.aaps.core.interfaces.pump.Pump]
 * surface: the first milestones carry reads and the stop/resume pair only, and anything absent
 * here cannot be expressed on the wire at all.
 */
enum class CommandKind {
    /** Read pump state. Never changes delivery, so it is the one command a locked watch may still run. */
    STATUS,

    /** Set a temporary basal rate, [ComboCommand.percentage] % for [ComboCommand.durationMinutes]. */
    SET_TBR,

    /** Cancel a running temporary basal, i.e. return to 100 %. */
    CANCEL_TBR
}

/**
 * A lease naming the watch as the pump's controller.
 *
 * The phone renews it while its active pump plugin is the watch-backed one. When the user
 * switches back to driving the pump directly, the phone stops renewing and the lease lapses
 * on its own — no message needs to arrive for the watch to stand down, which is what makes
 * the switch safe even if the two devices are out of contact at that moment.
 */
data class ControlLease(
    val generation: Long,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val pumpSerial: String,
    /** False revokes immediately rather than waiting for [expiresAtEpochMs]. */
    val controllerIsWatch: Boolean
) {
    fun liveAt(nowEpochMs: Long): Boolean = controllerIsWatch && nowEpochMs < expiresAtEpochMs

    fun toJson(): JSONObject = JSONObject()
        .put("version", ComboWatchProtocol.PROTOCOL_VERSION)
        .put("generation", generation)
        .put("issuedAt", issuedAtEpochMs)
        .put("expiresAt", expiresAtEpochMs)
        .put("pumpSerial", pumpSerial)
        .put("controllerIsWatch", controllerIsWatch)

    companion object {

        fun fromJson(json: JSONObject) = ControlLease(
            generation = json.getLong("generation"),
            issuedAtEpochMs = json.getLong("issuedAt"),
            expiresAtEpochMs = json.getLong("expiresAt"),
            pumpSerial = json.getString("pumpSerial"),
            controllerIsWatch = json.getBoolean("controllerIsWatch")
        )
    }
}

/**
 * One request. [id] is the idempotency key: the watch records it before touching the pump and
 * never executes the same id twice, however many times the phone resends it.
 *
 * [expiresAtEpochMs] exists because the watch's alarms were measured arriving 7 to 224 s late.
 * A stop that lands minutes after it was decided is a dosing error, not a slow success, so the
 * watch drops an expired command and says so rather than running it.
 */
data class ComboCommand(
    val id: String,
    val leaseGeneration: Long,
    val kind: CommandKind,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val percentage: Int? = null,
    val durationMinutes: Int? = null
) {

    fun toJson(): JSONObject = JSONObject()
        .put("version", ComboWatchProtocol.PROTOCOL_VERSION)
        .put("id", id)
        .put("leaseGeneration", leaseGeneration)
        .put("kind", kind.name)
        .put("issuedAt", issuedAtEpochMs)
        .put("expiresAt", expiresAtEpochMs)
        .apply {
            percentage?.let { put("percentage", it) }
            durationMinutes?.let { put("durationMinutes", it) }
        }

    companion object {

        fun fromJson(json: JSONObject) = ComboCommand(
            id = json.getString("id"),
            leaseGeneration = json.getLong("leaseGeneration"),
            kind = CommandKind.valueOf(json.getString("kind")),
            issuedAtEpochMs = json.getLong("issuedAt"),
            expiresAtEpochMs = json.getLong("expiresAt"),
            percentage = if (json.has("percentage")) json.getInt("percentage") else null,
            durationMinutes = if (json.has("durationMinutes")) json.getInt("durationMinutes") else null
        )
    }
}

/**
 * How a command ended.
 *
 * [UNKNOWN] is the important one and is not a synonym for failure: the link died at a moment
 * when the pump may or may not have applied the change. The phone must not resend, and must
 * not assume either outcome; the watch clears it by reading the pump back.
 */
enum class Outcome {
    /** The pump was changed, and the change was read back from the pump to confirm it. */
    DONE,

    /** Nothing was sent: no lease, command expired, executor busy, or the kind is not allowed. */
    REFUSED,

    /** Reached the pump but the command did not take effect. Delivery is unchanged. */
    FAILED,

    /** Outcome not established. Delivery may or may not have changed. */
    UNKNOWN
}

/** Everything the phone needs to keep its own records right after the watch touched the pump. */
data class PumpSnapshot(
    val readAtEpochMs: Long,
    val tbrRunning: Boolean,
    val tbrPercentage: Int?,
    val tbrRemainingMinutes: Int?,
    val reservoirUnits: Int?,
    val batteryState: String?,
    val pumpSerial: String?
) {

    fun toJson(): JSONObject = JSONObject()
        .put("readAt", readAtEpochMs)
        .put("tbrRunning", tbrRunning)
        .apply {
            tbrPercentage?.let { put("tbrPercentage", it) }
            tbrRemainingMinutes?.let { put("tbrRemainingMinutes", it) }
            reservoirUnits?.let { put("reservoirUnits", it) }
            batteryState?.let { put("batteryState", it) }
            pumpSerial?.let { put("pumpSerial", it) }
        }

    companion object {

        fun fromJson(json: JSONObject) = PumpSnapshot(
            readAtEpochMs = json.getLong("readAt"),
            tbrRunning = json.getBoolean("tbrRunning"),
            tbrPercentage = json.optIntOrNull("tbrPercentage"),
            tbrRemainingMinutes = json.optIntOrNull("tbrRemainingMinutes"),
            reservoirUnits = json.optIntOrNull("reservoirUnits"),
            batteryState = if (json.has("batteryState")) json.getString("batteryState") else null,
            pumpSerial = if (json.has("pumpSerial")) json.getString("pumpSerial") else null
        )
    }
}

/** The answer to exactly one [ComboCommand], identified by the same [id]. */
data class ComboResult(
    val id: String,
    val outcome: Outcome,
    val completedAtEpochMs: Long,
    val snapshot: PumpSnapshot? = null,
    val reason: String? = null
) {

    fun toJson(): JSONObject = JSONObject()
        .put("version", ComboWatchProtocol.PROTOCOL_VERSION)
        .put("id", id)
        .put("outcome", outcome.name)
        .put("completedAt", completedAtEpochMs)
        .apply {
            snapshot?.let { put("snapshot", it.toJson()) }
            reason?.let { put("reason", it) }
        }

    companion object {

        fun fromJson(json: JSONObject) = ComboResult(
            id = json.getString("id"),
            outcome = Outcome.valueOf(json.getString("outcome")),
            completedAtEpochMs = json.getLong("completedAt"),
            snapshot = if (json.has("snapshot")) PumpSnapshot.fromJson(json.getJSONObject("snapshot")) else null,
            reason = if (json.has("reason")) json.getString("reason") else null
        )
    }
}

/**
 * Sent by the watch on its own schedule. The phone treats a stale heartbeat as "pump
 * unreachable": both the watch being away and the watch failing to reach the pump show up
 * here, and [pumpReachable] is what tells those two apart.
 */
data class WatchHeartbeat(
    val atEpochMs: Long,
    val leaseGeneration: Long,
    val leaseLive: Boolean,
    val executorBusy: Boolean,
    /** Set when a previous command ended [Outcome.UNKNOWN] and the pump has not been read back yet. */
    val awaitingReconciliation: Boolean,
    val pumpReachable: Boolean,
    val watchBatteryPercent: Int?,
    val snapshot: PumpSnapshot? = null
) {

    fun toJson(): JSONObject = JSONObject()
        .put("version", ComboWatchProtocol.PROTOCOL_VERSION)
        .put("at", atEpochMs)
        .put("leaseGeneration", leaseGeneration)
        .put("leaseLive", leaseLive)
        .put("executorBusy", executorBusy)
        .put("awaitingReconciliation", awaitingReconciliation)
        .put("pumpReachable", pumpReachable)
        .apply {
            watchBatteryPercent?.let { put("watchBattery", it) }
            snapshot?.let { put("snapshot", it.toJson()) }
        }

    companion object {

        fun fromJson(json: JSONObject) = WatchHeartbeat(
            atEpochMs = json.getLong("at"),
            leaseGeneration = json.getLong("leaseGeneration"),
            leaseLive = json.getBoolean("leaseLive"),
            executorBusy = json.getBoolean("executorBusy"),
            awaitingReconciliation = json.getBoolean("awaitingReconciliation"),
            pumpReachable = json.getBoolean("pumpReachable"),
            watchBatteryPercent = json.optIntOrNull("watchBattery"),
            snapshot = if (json.has("snapshot")) PumpSnapshot.fromJson(json.getJSONObject("snapshot")) else null
        )
    }
}

private fun JSONObject.optIntOrNull(name: String): Int? = if (has(name)) getInt(name) else null
