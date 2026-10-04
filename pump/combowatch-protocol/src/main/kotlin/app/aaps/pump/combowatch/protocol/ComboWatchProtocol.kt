package app.aaps.pump.combowatch.protocol

import org.json.JSONArray
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
    const val PATH_EVENTS = "/combowatch/events"
    const val PATH_EVENTS_ACK = "/combowatch/events-ack"

    /** Capability the watch advertises once its executor is installed and paired to a pump. */
    const val CAPABILITY_EXECUTOR = "combowatch_executor"

    const val PROTOCOL_VERSION = 1
}

/**
 * What the phone is allowed to ask for. Deliberately not the whole [app.aaps.core.interfaces.pump.Pump]
 * surface: anything absent here cannot be expressed on the wire at all.
 */
enum class CommandKind {
    /** Read pump state. Never changes delivery, so it is the one command a locked watch may still run. */
    STATUS,

    /** Set a temporary basal rate, [ComboCommand.percentage] % for [ComboCommand.durationMinutes]. */
    SET_TBR,

    /**
     * End the running temporary basal the way AAPS does it. With [ComboCommand.force100Percent]
     * the TBR is really cancelled (the pump raises its W6 warning); without it the driver sets a
     * 15-minute 90 % / 110 % TBR instead, which is what AAPS normally asks for.
     */
    CANCEL_TBR,

    /** Deliver a standard bolus of [ComboCommand.bolusTenthsIU] tenths of a unit. */
    DELIVER_BOLUS
}

/** Why a temporary basal is being set, so the watch's driver records the same type the phone would. */
enum class TbrKind { NORMAL, SUPERBOLUS, EMULATED_STOP }

/** What a bolus is for. SMB is the loop's own microbolus. */
enum class BolusKind { NORMAL, SMB, PRIMING }

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
    val controllerIsWatch: Boolean,
    /**
     * The largest single bolus the phone may ask for under this lease, in tenths of a unit: the
     * owner's own "max bolus" from the phone's safety settings, which every bolus is held to
     * before it is asked for. The watch lets nothing larger through whatever a message says; a
     * lease without it leaves the watch's own, smaller limit in force.
     */
    val maxBolusTenthsIU: Int? = null
) {
    fun liveAt(nowEpochMs: Long): Boolean = controllerIsWatch && nowEpochMs < expiresAtEpochMs

    fun toJson(): JSONObject = JSONObject()
        .put("version", ComboWatchProtocol.PROTOCOL_VERSION)
        .put("generation", generation)
        .put("issuedAt", issuedAtEpochMs)
        .put("expiresAt", expiresAtEpochMs)
        .put("pumpSerial", pumpSerial)
        .put("controllerIsWatch", controllerIsWatch)
        .apply { maxBolusTenthsIU?.let { put("maxBolusTenthsIU", it) } }

    companion object {

        fun fromJson(json: JSONObject) = ControlLease(
            generation = json.getLong("generation"),
            issuedAtEpochMs = json.getLong("issuedAt"),
            expiresAtEpochMs = json.getLong("expiresAt"),
            pumpSerial = json.getString("pumpSerial"),
            controllerIsWatch = json.getBoolean("controllerIsWatch"),
            maxBolusTenthsIU = json.optIntOrNull("maxBolusTenthsIU")
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
    val durationMinutes: Int? = null,
    val tbrKind: TbrKind? = null,
    val force100Percent: Boolean? = null,
    /** Bolus amount in tenths of a unit, the Combo's own resolution: 3 means 0.3 U. */
    val bolusTenthsIU: Int? = null,
    val bolusKind: BolusKind? = null
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
            tbrKind?.let { put("tbrKind", it.name) }
            force100Percent?.let { put("force100Percent", it) }
            bolusTenthsIU?.let { put("bolusTenthsIU", it) }
            bolusKind?.let { put("bolusKind", it.name) }
        }

    companion object {

        fun fromJson(json: JSONObject) = ComboCommand(
            id = json.getString("id"),
            leaseGeneration = json.getLong("leaseGeneration"),
            kind = CommandKind.valueOf(json.getString("kind")),
            issuedAtEpochMs = json.getLong("issuedAt"),
            expiresAtEpochMs = json.getLong("expiresAt"),
            percentage = json.optIntOrNull("percentage"),
            durationMinutes = json.optIntOrNull("durationMinutes"),
            tbrKind = if (json.has("tbrKind")) TbrKind.valueOf(json.getString("tbrKind")) else null,
            force100Percent = if (json.has("force100Percent")) json.getBoolean("force100Percent") else null,
            bolusTenthsIU = json.optIntOrNull("bolusTenthsIU"),
            bolusKind = if (json.has("bolusKind")) BolusKind.valueOf(json.getString("bolusKind")) else null
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
    val pumpSerial: String?,
    /**
     * The 24 hourly basal factors programmed on the pump, in 0.001 U/h, when the watch has read
     * them. The phone compares them with its own profile, since every TBR percentage is relative
     * to what the pump actually delivers as 100 %.
     */
    val basalProfileFactors: List<Int>? = null
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
            basalProfileFactors?.let { factors -> put("basalProfileFactors", JSONArray().apply { factors.forEach { put(it) } }) }
        }

    companion object {

        fun fromJson(json: JSONObject) = PumpSnapshot(
            readAtEpochMs = json.getLong("readAt"),
            tbrRunning = json.getBoolean("tbrRunning"),
            tbrPercentage = json.optIntOrNull("tbrPercentage"),
            tbrRemainingMinutes = json.optIntOrNull("tbrRemainingMinutes"),
            reservoirUnits = json.optIntOrNull("reservoirUnits"),
            batteryState = if (json.has("batteryState")) json.getString("batteryState") else null,
            pumpSerial = if (json.has("pumpSerial")) json.getString("pumpSerial") else null,
            basalProfileFactors = json.optJSONArray("basalProfileFactors")?.let { array -> List(array.length()) { array.getInt(it) } }
        )
    }
}

/** The answer to exactly one [ComboCommand], identified by the same [id]. */
data class ComboResult(
    val id: String,
    val outcome: Outcome,
    val completedAtEpochMs: Long,
    val snapshot: PumpSnapshot? = null,
    val reason: String? = null,
    /**
     * What the driver actually did for a TBR command, in its own words (SET_NORMAL_TBR,
     * SET_EMULATED_100_TBR, LETTING_EMULATED_100_TBR_FINISH, IGNORED_REDUNDANT_100_TBR). The phone
     * needs it because "done" covers both a TBR that was set and one that was deliberately left.
     */
    val tbrOutcome: String? = null,
    /** The TBR now active on the pump after a TBR command, as the driver set it. */
    val tbrPercentage: Int? = null,
    val tbrDurationMinutes: Int? = null,
    /**
     * The bolus the pump's own history recorded for a DELIVER_BOLUS, in tenths of a unit. This is
     * the amount that counts: it can be less than what was asked for if delivery was cut short.
     */
    val bolus: BolusReceipt? = null
) {

    fun toJson(): JSONObject = JSONObject()
        .put("version", ComboWatchProtocol.PROTOCOL_VERSION)
        .put("id", id)
        .put("outcome", outcome.name)
        .put("completedAt", completedAtEpochMs)
        .apply {
            snapshot?.let { put("snapshot", it.toJson()) }
            reason?.let { put("reason", it) }
            tbrOutcome?.let { put("tbrOutcome", it) }
            tbrPercentage?.let { put("tbrPercentage", it) }
            tbrDurationMinutes?.let { put("tbrDurationMinutes", it) }
            bolus?.let { put("bolus", it.toJson()) }
        }

    companion object {

        fun fromJson(json: JSONObject) = ComboResult(
            id = json.getString("id"),
            outcome = Outcome.valueOf(json.getString("outcome")),
            completedAtEpochMs = json.getLong("completedAt"),
            snapshot = if (json.has("snapshot")) PumpSnapshot.fromJson(json.getJSONObject("snapshot")) else null,
            reason = if (json.has("reason")) json.getString("reason") else null,
            tbrOutcome = if (json.has("tbrOutcome")) json.getString("tbrOutcome") else null,
            tbrPercentage = json.optIntOrNull("tbrPercentage"),
            tbrDurationMinutes = json.optIntOrNull("tbrDurationMinutes"),
            bolus = if (json.has("bolus")) BolusReceipt.fromJson(json.getJSONObject("bolus")) else null
        )
    }
}

/** A bolus as the pump's history recorded it. [bolusId] is the pump's own id and makes it unique. */
data class BolusReceipt(
    val bolusId: Long,
    val timestampEpochMs: Long,
    val tenthsIU: Int
) {

    fun toJson(): JSONObject = JSONObject()
        .put("bolusId", bolusId)
        .put("timestamp", timestampEpochMs)
        .put("tenthsIU", tenthsIU)

    companion object {

        fun fromJson(json: JSONObject) = BolusReceipt(
            bolusId = json.getLong("bolusId"),
            timestampEpochMs = json.getLong("timestamp"),
            tenthsIU = json.getInt("tenthsIU")
        )
    }
}

/**
 * Something the watch's driver observed on the pump, forwarded so the phone can keep its
 * treatment records right.
 *
 * These are the driver's own events, not interpretations: the phone applies the same mapping to
 * them that its direct driver applies, and that mapping is keyed on the pump's ids, so receiving
 * one twice changes nothing. That is what lets delivery be at-least-once - the watch keeps an
 * event until the phone acknowledges its [seq].
 */
data class PumpEvent(
    val seq: Long,
    val type: Type,
    val timestampEpochMs: Long,
    val bolusId: Long? = null,
    val bolusTenthsIU: Int? = null,
    val bolusKind: BolusKind? = null,
    val tbrPercentage: Int? = null,
    val tbrDurationMinutes: Int? = null,
    val tbrType: String? = null,
    /**
     * The pump this was observed on, exactly as the driver names it ("PUMP_" plus the serial).
     * The phone files its treatment records under this, and AAPS discards a record whose serial
     * is not the one it has registered - silently, as far as insulin on board is concerned. So
     * the serial travels with the event instead of being guessed by the receiver.
     */
    val pumpSerial: String? = null,
    /** The text of a [Type.WATCH_NOTE]; for [Type.CARBS], what kind of food; for [Type.ACTIVITY], the carbohydrates wanted if any (fast/balanced). */
    val note: String? = null,
    /** Grams of a [Type.CARBS] entry. */
    val carbsGrams: Int? = null,
    /** [Type.ACTIVITY]: WALK or SPORT, how long, and how many minutes after the entry it starts; the event's timestamp is the start. */
    val activityMode: String? = null,
    val activityDurationMinutes: Int? = null,
    val activityStartOffsetMinutes: Int? = null
) {

    enum class Type {
        /** A bolus the pump finished delivering. [bolusKind] is null for one given on the pump itself. */
        BOLUS_INFUSED,
        TBR_STARTED,
        TBR_ENDED,

        /** The driver found a TBR it did not set and cancelled it. */
        UNKNOWN_TBR_DETECTED,
        BATTERY_LOW,
        RESERVOIR_LOW,

        /**
         * Not something the pump did: a line the watch wrote about a decision it took (or, while
         * only observing, would have taken) by itself with the phone away. It travels with the
         * pump events so that it reaches the phone in order with them and is never lost; the
         * text is in [note].
         */
        WATCH_NOTE,

        /**
         * Not something the pump did either: carbohydrates the owner entered on the watch, kept
         * by the watch for its own forecast and handed to the phone's records whenever it is in
         * touch. Grams in [carbsGrams], the kind of food in [note].
         */
        CARBS,

        /**
         * A walk or a sport session the owner entered on the watch - the phone's "Activity v2".
         * The watch counts it in its own forecast and basal at once; the phone records it the
         * same way it records one entered through it, as soon as it is in touch.
         */
        ACTIVITY,

        /** A type a newer watch sends and this build does not know. Passed over, never an error. */
        UNKNOWN
    }

    fun toJson(): JSONObject = JSONObject()
        .put("seq", seq)
        .put("type", type.name)
        .put("timestamp", timestampEpochMs)
        .apply {
            bolusId?.let { put("bolusId", it) }
            bolusTenthsIU?.let { put("bolusTenthsIU", it) }
            bolusKind?.let { put("bolusKind", it.name) }
            tbrPercentage?.let { put("tbrPercentage", it) }
            tbrDurationMinutes?.let { put("tbrDurationMinutes", it) }
            tbrType?.let { put("tbrType", it) }
            pumpSerial?.let { put("pumpSerial", it) }
            note?.let { put("note", it) }
            carbsGrams?.let { put("carbsGrams", it) }
            activityMode?.let { put("activityMode", it) }
            activityDurationMinutes?.let { put("activityDurationMinutes", it) }
            activityStartOffsetMinutes?.let { put("activityStartOffsetMinutes", it) }
        }

    companion object {

        fun fromJson(json: JSONObject) = PumpEvent(
            seq = json.getLong("seq"),
            type = runCatching { Type.valueOf(json.getString("type")) }.getOrDefault(Type.UNKNOWN),
            timestampEpochMs = json.getLong("timestamp"),
            bolusId = if (json.has("bolusId")) json.getLong("bolusId") else null,
            bolusTenthsIU = json.optIntOrNull("bolusTenthsIU"),
            bolusKind = if (json.has("bolusKind")) BolusKind.valueOf(json.getString("bolusKind")) else null,
            tbrPercentage = json.optIntOrNull("tbrPercentage"),
            tbrDurationMinutes = json.optIntOrNull("tbrDurationMinutes"),
            tbrType = if (json.has("tbrType")) json.getString("tbrType") else null,
            pumpSerial = if (json.has("pumpSerial")) json.getString("pumpSerial") else null,
            note = if (json.has("note")) json.getString("note") else null,
            carbsGrams = json.optIntOrNull("carbsGrams"),
            activityMode = if (json.has("activityMode")) json.getString("activityMode") else null,
            activityDurationMinutes = json.optIntOrNull("activityDurationMinutes"),
            activityStartOffsetMinutes = json.optIntOrNull("activityStartOffsetMinutes")
        )

        fun listToJson(events: List<PumpEvent>): JSONObject =
            JSONObject().put("events", JSONArray().apply { events.forEach { put(it.toJson()) } })

        fun listFromJson(json: JSONObject): List<PumpEvent> =
            json.getJSONArray("events").let { array -> List(array.length()) { fromJson(array.getJSONObject(it)) } }
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
    val snapshot: PumpSnapshot? = null,
    /**
     * The pump the watch is paired with right now, as the driver names it, or null when it holds
     * none. Known without a pump session, so the phone learns of an unpairing or of a different
     * pump at once and stops trusting a [snapshot] it was given for the previous one.
     */
    val heldPump: String? = null
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
            heldPump?.let { put("heldPump", it) }
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
            snapshot = if (json.has("snapshot")) PumpSnapshot.fromJson(json.getJSONObject("snapshot")) else null,
            heldPump = if (json.has("heldPump")) json.getString("heldPump") else null
        )
    }
}

/**
 * What the phone leaves with the watch at every lease renewal, so that the watch can keep basal
 * safe by itself once the phone has gone silent.
 *
 * Nothing in it is computed for the watch specially: it is what the phone's own loop run already
 * worked out - how the insulin already given will act, what is left of the carbohydrates, the
 * sensitivity and the thresholds the algorithm used. The watch adds only what it measures itself
 * (glucose) and what it did itself (pump commands), and never keeps a model of its own: when the
 * phone returns, a new snapshot replaces this one.
 *
 * It is also the standing permission. Without a snapshot naming the pump the watch holds, and
 * past [validUntilEpochMs], the watch does nothing on its own.
 */
data class RegulationSnapshot(
    /** When the loop run this is taken from was made. The insulin curve below starts here. */
    val madeAtEpochMs: Long,
    /** The pump these numbers belong to, as in the lease. */
    val pumpSerial: String,
    /** The watch may act on this snapshot until then, and not a minute longer. */
    val validUntilEpochMs: Long,
    /** The glucose target of the loop run, mg/dL. */
    val targetMgdl: Double,
    /** The algorithm's own low-glucose threshold, mg/dL. */
    val hypoThresholdMgdl: Double,
    /** The sensitivity the algorithm forecast with, mg/dL per unit. */
    val sensitivityMgdlPerU: Double,
    /** Grams of carbohydrate per unit, from the profile. */
    val carbRatioGPerU: Double,
    /** Carbohydrates not yet absorbed at [madeAtEpochMs], grams. */
    val cobG: Double,
    /** Insulin on board at [madeAtEpochMs], units; for the journal and the simple safeguards. */
    val iobU: Double,
    /**
     * How the insulin given up to [madeAtEpochMs] will act: one point every five minutes from
     * [madeAtEpochMs], activity in units per minute, net of scheduled basal. It already includes
     * the rest of the temporary basal described by [assumedTbr], and nothing decided after it.
     */
    val insulinActivity: List<Double>,
    /**
     * The insulin's action curve: the fraction of a unit dose still to act, sampled every two and
     * a half minutes from the moment of the dose. With it the watch works out the effect of what
     * the pump delivered after the snapshot was made, by the same curve the phone uses.
     */
    val insulinRemaining: List<Double>,
    /** The temporary basal the phone's records showed running at [madeAtEpochMs], if any. */
    val assumedTbr: AssumedTbr? = null,
    /** The phone's own forecast from this run, mg/dL every five minutes; kept for comparison. */
    val phoneForecast: List<Int>? = null
) {

    /** A temporary basal as an absolute rate, and when it was due to end. */
    data class AssumedTbr(val rateUph: Double, val endsAtEpochMs: Long)

    /**
     * Whether every number in it is a number. One that is not cannot be written to a message, and
     * nothing should be decided from it either.
     */
    val isWellFormed: Boolean
        get() = listOf(targetMgdl, hypoThresholdMgdl, sensitivityMgdlPerU, carbRatioGPerU, cobG, iobU).all { it.isFinite() } &&
            insulinActivity.all { it.isFinite() } && insulinRemaining.all { it.isFinite() } &&
            (assumedTbr?.rateUph?.isFinite() ?: true)

    fun toJson(): JSONObject = JSONObject()
        .put("madeAt", madeAtEpochMs)
        .put("pumpSerial", pumpSerial)
        .put("validUntil", validUntilEpochMs)
        .put("target", targetMgdl)
        .put("hypoThreshold", hypoThresholdMgdl)
        .put("sensitivity", sensitivityMgdlPerU)
        .put("carbRatio", carbRatioGPerU)
        .put("cob", cobG)
        .put("iob", iobU)
        .put("insulinActivity", JSONArray().apply { insulinActivity.forEach { put(it) } })
        .put("insulinRemaining", JSONArray().apply { insulinRemaining.forEach { put(it) } })
        .apply {
            assumedTbr?.let { put("assumedTbr", JSONObject().put("rate", it.rateUph).put("endsAt", it.endsAtEpochMs)) }
            phoneForecast?.let { forecast -> put("phoneForecast", JSONArray().apply { forecast.forEach { put(it) } }) }
        }

    companion object {

        /** The key under which a snapshot rides inside the lease message. */
        const val KEY_IN_LEASE = "regulation"

        fun fromJson(json: JSONObject) = RegulationSnapshot(
            madeAtEpochMs = json.getLong("madeAt"),
            pumpSerial = json.getString("pumpSerial"),
            validUntilEpochMs = json.getLong("validUntil"),
            targetMgdl = json.getDouble("target"),
            hypoThresholdMgdl = json.getDouble("hypoThreshold"),
            sensitivityMgdlPerU = json.getDouble("sensitivity"),
            carbRatioGPerU = json.getDouble("carbRatio"),
            cobG = json.getDouble("cob"),
            iobU = json.getDouble("iob"),
            insulinActivity = json.getJSONArray("insulinActivity").let { array -> List(array.length()) { array.getDouble(it) } },
            insulinRemaining = json.getJSONArray("insulinRemaining").let { array -> List(array.length()) { array.getDouble(it) } },
            assumedTbr = json.optJSONObject("assumedTbr")?.let { AssumedTbr(it.getDouble("rate"), it.getLong("endsAt")) },
            phoneForecast = json.optJSONArray("phoneForecast")?.let { array -> List(array.length()) { array.getInt(it) } }
        )
    }
}

private fun JSONObject.optIntOrNull(name: String): Int? = if (has(name)) getInt(name) else null
