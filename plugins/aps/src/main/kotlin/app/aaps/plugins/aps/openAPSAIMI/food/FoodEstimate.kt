package app.aaps.plugins.aps.openAPSAIMI.food

import org.json.JSONArray
import org.json.JSONObject

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

    data class Item(val name: String, val portion: String?, val carbsG: Double?)

    fun toJson(): JSONObject = JSONObject()
        .put("carbs_g", carbsG)
        .put("carbs_range", JSONArray().apply { rangeLowG?.let { put(it) }; rangeHighG?.let { put(it) } })
        .put("fat_g", fatG ?: JSONObject.NULL).put("protein_g", proteinG ?: JSONObject.NULL)
        .put("items", JSONArray().apply { items.forEach { put(JSONObject().put("name", it.name).put("portion", it.portion ?: JSONObject.NULL).put("carbs_g", it.carbsG ?: JSONObject.NULL)) } })
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
                    Item(it.optString("name").ifEmpty { "?" }, it.optString("portion").takeIf { s -> s.isNotEmpty() && !it.isNull("portion") }, it.optDouble("carbs_g").takeIf { d -> d.isFinite() })
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
