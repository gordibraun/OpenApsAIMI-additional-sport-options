package app.aaps.plugins.aps.openAPSAIMI.food

import app.aaps.core.interfaces.aps.CarbSplit
import org.json.JSONArray
import org.json.JSONObject

/**
 * One photographed plate on this phone: the photo, what the server said about it, and what the
 * owner decided. A photo is not a meal until the owner says so.
 */
class FoodPhotoEntry(
    val id: String,
    val atEpochMs: Long,
    val photoPath: String,
    var caption: String = "",
    var status: Status = Status.QUEUED,
    /** The server's record id once it has answered; null means the photo has not been uploaded. */
    var serverId: String? = null,
    var estimate: FoodEstimate? = null,
    /** The owner's own number, when they corrected the estimate or typed the grams themselves. */
    var finalGrams: Double? = null,
    /** The owner's own split by type, when they corrected the parts; its total is [finalGrams]. */
    var finalSplit: CarbSplit? = null,
    /** The owner's decision (eaten / declined / corrected) still has to be told to the server. */
    var decisionPending: Boolean = false,
    var decidedAtEpochMs: Long? = null,
    var error: String? = null
) {

    enum class Status {

        /** Waiting for the network or the server. */
        QUEUED,
        /** The server has answered; the owner has not decided yet. */
        ESTIMATED,
        /** The owner ate it: the grams went to the bolus wizard. */
        EATEN,
        /** The owner did not eat it: never a meal. */
        DECLINED,
        /** The server refused for good; the owner can retry or delete. */
        ERROR
    }

    val shortId: String get() = id.takeLast(6)

    val estimatedGrams: Double? get() = estimate?.carbsG

    /** The grams the owner would enter: their own number first, else the estimate. */
    val gramsToEnter: Double? get() = finalGrams ?: estimatedGrams

    /** The meal by type as it would go into the wizard: the owner's split, the estimate's split scaled to the owner's number, or the estimate's split. */
    val splitToEnter: CarbSplit?
        get() {
            finalSplit?.takeIf { !it.isEmpty }?.let { return it }
            val own = finalGrams?.let { Math.round(it).toInt() }
            val estimated = estimate?.split()
            return when {
                estimated != null && own != null -> estimated.scaledTo(own)
                estimated != null -> estimated
                own != null -> CarbSplit(balanced = own)
                else -> null
            }
        }

    /** The server has not seen this photo yet and should: nothing to upload for a refused or declined one. */
    val needsUpload: Boolean get() = serverId == null && status != Status.ERROR && status != Status.DECLINED

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("at", atEpochMs).put("photo", photoPath).put("caption", caption).put("status", status.name)
        .put("serverId", serverId ?: JSONObject.NULL).put("estimate", estimate?.toJson() ?: JSONObject.NULL)
        .put("finalGrams", finalGrams ?: JSONObject.NULL).put("decisionPending", decisionPending)
        .put("finalSplit", finalSplit?.let { JSONObject().put("fast", it.fast).put("balanced", it.balanced).put("slow", it.slow) } ?: JSONObject.NULL)
        .put("decidedAt", decidedAtEpochMs ?: JSONObject.NULL).put("error", error ?: JSONObject.NULL)

    companion object {

        fun fromJson(j: JSONObject) = FoodPhotoEntry(
            id = j.getString("id"), atEpochMs = j.getLong("at"), photoPath = j.getString("photo"), caption = j.optString("caption"),
            status = runCatching { Status.valueOf(j.optString("status")) }.getOrDefault(Status.QUEUED),
            serverId = if (j.isNull("serverId")) null else j.optString("serverId").takeIf { it.isNotEmpty() },
            estimate = FoodEstimate.fromJson(j.optJSONObject("estimate")),
            finalGrams = if (j.isNull("finalGrams")) null else j.optDouble("finalGrams").takeIf { it.isFinite() },
            finalSplit = j.optJSONObject("finalSplit")?.let { CarbSplit(it.optInt("fast"), it.optInt("balanced"), it.optInt("slow")) }?.takeIf { !it.isEmpty },
            decisionPending = j.optBoolean("decisionPending"),
            decidedAtEpochMs = if (j.isNull("decidedAt")) null else j.optLong("decidedAt"),
            error = if (j.isNull("error")) null else j.optString("error").takeIf { it.isNotEmpty() }
        )

        fun listToJson(entries: List<FoodPhotoEntry>): JSONObject = JSONObject().put("entries", JSONArray().apply { entries.forEach { put(it.toJson()) } })

        fun listFromJson(j: JSONObject): List<FoodPhotoEntry> {
            val array = j.optJSONArray("entries") ?: return emptyList()
            return List(array.length()) { fromJson(array.getJSONObject(it)) }
        }
    }
}
