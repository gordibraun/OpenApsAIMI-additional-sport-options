package app.aaps.plugins.aps.openAPSAIMI.food

import app.aaps.core.interfaces.aps.CarbSplit
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** What the server said about one photo, in plain fields; [fromJson] reads the server's answer. */
data class FoodEstimate(
    val carbsG: Double,
    val rangeLowG: Double? = null,
    val rangeHighG: Double? = null,
    val fatG: Double? = null,
    val proteinG: Double? = null,
    val items: List<Item> = emptyList(),
    /** fast, balanced or slow. */
    val absorption: String? = null,
    /** low, medium or high. */
    val confidence: String? = null,
    val advice: String? = null
) {

    data class Item(val name: String, val portion: String?, val carbsG: Double?, /** fast, balanced or slow; null means the plate's own. */ val absorption: String? = null)

    /**
     * The plate by type: each dish's grams under its own absorption, the plate's absorption for
     * dishes without one, scaled so the parts add up to [carbsG] when the dishes do not.
     */
    fun split(): CarbSplit {
        val plateType = CarbSplit.normalize(absorption)
        val typed = items.filter { (it.carbsG ?: 0.0) > 0 }
        if (typed.isEmpty()) return CarbSplit.single(plateType, carbsG.roundToInt())
        var fast = 0.0; var balanced = 0.0; var slow = 0.0
        typed.forEach { item ->
            val grams = item.carbsG ?: 0.0
            when (CarbSplit.normalize(item.absorption ?: absorption)) {
                CarbSplit.FAST -> fast += grams
                CarbSplit.SLOW -> slow += grams
                else -> balanced += grams
            }
        }
        val raw = CarbSplit(fast.roundToInt(), balanced.roundToInt(), slow.roundToInt())
        val total = carbsG.roundToInt()
        return if (raw.isEmpty) CarbSplit.single(plateType, total) else if (kotlin.math.abs(raw.total - total) <= 2) raw else raw.scaledTo(total)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("carbs_g", carbsG)
        .put("carbs_range", JSONArray().apply { rangeLowG?.let { put(it) }; rangeHighG?.let { put(it) } })
        .put("fat_g", fatG ?: JSONObject.NULL).put("protein_g", proteinG ?: JSONObject.NULL)
        .put("items", JSONArray().apply { items.forEach { put(JSONObject().put("name", it.name).put("portion", it.portion ?: JSONObject.NULL).put("carbs_g", it.carbsG ?: JSONObject.NULL).put("absorption", it.absorption ?: JSONObject.NULL)) } })
        .put("absorption", absorption ?: JSONObject.NULL).put("confidence", confidence ?: JSONObject.NULL).put("advice", advice ?: JSONObject.NULL)

    companion object {

        /** Null when the answer carries no carbohydrate number. */
        fun fromJson(j: JSONObject?): FoodEstimate? {
            j ?: return null
            val carbs = j.optDouble("carbs_g").takeIf { it.isFinite() } ?: return null
            val range = j.optJSONArray("carbs_range")
            val items = j.optJSONArray("items")?.let { a ->
                List(a.length()) { i ->
                    val it = a.optJSONObject(i) ?: return@List null
                    Item(
                        it.optString("name").ifEmpty { "?" }, it.optString("portion").takeIf { s -> s.isNotEmpty() && !it.isNull("portion") },
                        it.optDouble("carbs_g").takeIf { d -> d.isFinite() }, it.optString("absorption").takeIf { s -> s.isNotEmpty() && !it.isNull("absorption") }
                    )
                }.filterNotNull()
            }.orEmpty()
            return FoodEstimate(
                carbsG = carbs,
                rangeLowG = range?.optDouble(0)?.takeIf { it.isFinite() },
                rangeHighG = range?.optDouble(1)?.takeIf { it.isFinite() },
                fatG = j.optDouble("fat_g").takeIf { it.isFinite() },
                proteinG = j.optDouble("protein_g").takeIf { it.isFinite() },
                items = items,
                absorption = j.optString("absorption").takeIf { it.isNotEmpty() && !j.isNull("absorption") },
                confidence = j.optString("confidence").takeIf { it.isNotEmpty() && !j.isNull("confidence") },
                advice = j.optString("advice").takeIf { it.isNotEmpty() && !j.isNull("advice") }
            )
        }
    }
}
