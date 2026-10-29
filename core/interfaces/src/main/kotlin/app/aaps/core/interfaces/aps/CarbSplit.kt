package app.aaps.core.interfaces.aps

import kotlin.math.roundToInt

/**
 * The carbohydrates of one meal by how fast they come: fast (sugar, juice, white bread), balanced
 * (ordinary food) and slow (fat or protein heavy). The algorithm absorbs each part on its own curve,
 * so a meal entered this way becomes one bolus and one carb entry per part, each entry carrying
 * its type in the note (`AIMI_CARB_TYPE type=…`) the way the wizard always wrote it.
 */
data class CarbSplit(val fast: Int = 0, val balanced: Int = 0, val slow: Int = 0) {

    val total: Int get() = fast + balanced + slow
    val isEmpty: Boolean get() = total <= 0

    fun grams(type: String): Int = when (normalize(type)) {
        FAST -> fast
        SLOW -> slow
        else -> balanced
    }

    /** Non-zero parts, the dominant one first, then in the order fast, balanced, slow. */
    fun parts(): List<Pair<String, Int>> {
        val all = listOf(FAST to fast, BALANCED to balanced, SLOW to slow).filter { it.second > 0 }
        val dominant = dominantType
        return all.sortedBy { if (it.first == dominant) 0 else 1 }
    }

    /**
     * The type that sets the meal's tone for the dose: the part holding at least [DOMINANT_SHARE]
     * of the grams, else balanced. The same rule the AIMI meal assistant applies to active carbs.
     */
    val dominantType: String
        get() {
            if (isEmpty) return BALANCED
            val (type, grams) = listOf(FAST to fast, BALANCED to balanced, SLOW to slow).maxByOrNull { it.second }!!
            return if (grams.toDouble() / total >= DOMINANT_SHARE) type else BALANCED
        }

    /**
     * Spreads the grams the dose already covers over the parts, the fast ones first: when only
     * protective carbs are covered, it is the quick sugar that does the protecting.
     */
    fun allocateHandled(handledGrams: Int): Map<String, Int> {
        var left = handledGrams.coerceIn(0, total)
        val out = LinkedHashMap<String, Int>()
        for ((type, grams) in listOf(FAST to fast, BALANCED to balanced, SLOW to slow)) {
            if (grams <= 0) continue
            val take = minOf(grams, left)
            out[type] = take
            left -= take
        }
        return out
    }

    /** Scales every part so that the total becomes [newTotal], keeping the proportions. */
    fun scaledTo(newTotal: Int): CarbSplit {
        if (isEmpty || newTotal <= 0) return CarbSplit(balanced = newTotal.coerceAtLeast(0))
        val factor = newTotal.toDouble() / total
        var f = (fast * factor).roundToInt()
        var b = (balanced * factor).roundToInt()
        var s = (slow * factor).roundToInt()
        val drift = newTotal - (f + b + s)
        when {
            b > 0 || drift > 0 && f == 0 && s == 0 -> b += drift
            f >= s -> f += drift
            else -> s += drift
        }
        return CarbSplit(f.coerceAtLeast(0), b.coerceAtLeast(0), s.coerceAtLeast(0))
    }

    /** «12 быстрых, 58 обычных» */
    fun describe(): String = listOfNotNull(
        fast.takeIf { it > 0 }?.let { "$it быстрых" },
        balanced.takeIf { it > 0 }?.let { "$it обычных" },
        slow.takeIf { it > 0 }?.let { "$it медленных" }
    ).joinToString(", ")

    companion object {

        const val FAST = "fast"
        const val BALANCED = "balanced"
        const val SLOW = "slow"
        const val DOMINANT_SHARE = 0.75
        val TYPES = listOf(FAST, BALANCED, SLOW)

        fun single(type: String, grams: Int): CarbSplit = when (normalize(type)) {
            FAST -> CarbSplit(fast = grams)
            SLOW -> CarbSplit(slow = grams)
            else -> CarbSplit(balanced = grams)
        }

        fun normalize(type: String?): String = when (type?.lowercase()) {
            FAST -> FAST
            SLOW -> SLOW
            else -> BALANCED
        }

        fun label(type: String): String = when (normalize(type)) {
            FAST -> "быстрые"
            SLOW -> "медленные"
            else -> "обычные"
        }
    }
}
