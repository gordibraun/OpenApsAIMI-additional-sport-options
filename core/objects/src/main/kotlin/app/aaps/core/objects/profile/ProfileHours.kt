package app.aaps.core.objects.profile

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/**
 * One line of a local profile - basal, carb ratio or target - with some of its hours changed, in the shape the
 * profile editor stores it.
 *
 * The editor keeps a line as blocks («from 03:00: 1.28»); the pump holds 24 hourly rates, and the owner is given
 * numbers by the hour. So the blocks are spread out to hours, the named hours are replaced, and equal neighbours
 * are joined into blocks again.
 */
object ProfileHours {

    /** The value in each hour from midnight, from the editor's blocks; null when the blocks cannot be read. */
    fun hourly(blocks: JSONArray): List<Double>? = runCatching {
        val parsed = (0 until blocks.length()).map { blocks.getJSONObject(it) }
            .map { it.getInt("timeAsSeconds") to it.getDouble("value") }
            .sortedBy { it.first }
        check(parsed.isNotEmpty() && parsed.first().first == 0) { "the line does not start at midnight" }
        check(parsed.all { it.first % 3600 == 0 && it.first in 0 until 86_400 }) { "a block does not start on the hour" }
        List(24) { hour -> parsed.last { it.first <= hour * 3600 }.second }
    }.getOrNull()

    /** The editor's blocks for 24 hourly values. */
    fun blocks(hourly: List<Double>): JSONArray {
        require(hourly.size == 24)
        val array = JSONArray()
        hourly.forEachIndexed { hour, value ->
            if (hour == 0 || value != hourly[hour - 1])
                array.put(JSONObject().put("time", "%02d:00".format(Locale.US, hour)).put("timeAsSeconds", hour * 3600).put("value", value))
        }
        return array
    }

    /**
     * «1=1.05,2=1.05,4=1.0» or «6-10=9.0»: hour from midnight (or hours from-to, both included) = value.
     * Null when anything in it is not that.
     */
    fun parse(text: String): Map<Int, Double>? = runCatching {
        val out = LinkedHashMap<Int, Double>()
        text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { pair ->
            val (hours, value) = pair.split('=').map { it.trim() }.also { check(it.size == 2) { "not hour=value: $pair" } }
            val number = value.toDouble().also { check(it.isFinite() && it >= 0.0) { "value $it" } }
            val span = hours.split('-').map { it.trim().toInt() }.also { check(it.size in 1..2) { "hours $hours" } }
            val from = span.first()
            val to = span.last()
            check(from in 0..23 && to in 0..23 && from <= to) { "hours $hours" }
            for (hour in from..to) out[hour] = number
        }
        check(out.isNotEmpty())
        out
    }.getOrNull()

    fun apply(hourly: List<Double>, changes: Map<Int, Double>): List<Double> = hourly.mapIndexed { hour, value -> changes[hour] ?: value }

    /** The hours that differ, as «05:00 0,95 → 0,90», equal neighbours joined («01:00–03:00 1,23/1,26/1,28 → 1,05»). */
    fun describe(before: List<Double>, after: List<Double>, decimals: Int = 2): List<String> {
        val out = mutableListOf<String>()
        var hour = 0
        while (hour < 24) {
            if (before[hour] == after[hour]) { hour++; continue }
            var end = hour
            while (end + 1 < 24 && before[end + 1] != after[end + 1] && after[end + 1] == after[hour] && before[end + 1] == before[hour]) end++
            val span = if (end == hour) "%02d:00".format(Locale.US, hour) else "%02d:00–%02d:00".format(Locale.US, hour, (end + 1) % 24)
            out += "$span ${number(before[hour], decimals)} → ${number(after[hour], decimals)}"
            hour = end + 1
        }
        return out
    }

    private fun number(value: Double, decimals: Int) = "%.${decimals}f".format(Locale.US, value).replace('.', ',')

    /**
     * What may be asked of a profile line in one go. Not the pump's limits and not medicine: a guard against a
     * slip of the hand in a command or a fault in whatever computed the numbers.
     */
    object Limits {

        /** Null when the basal hours asked for may be written; else what is wrong, for the answer. */
        fun basal(before: List<Double>, changes: Map<Int, Double>, onPumpGrid: (Double) -> Boolean): String? {
            changes.forEach { (hour, rate) ->
                if (!onPumpGrid(rate)) return "hour $hour: $rate U/h is not a rate the pump holds"
                if (rate < MIN_BASAL_UPH) return "hour $hour: $rate U/h is below what the pump holds"
                if (abs(rate - before[hour]) > before[hour] * MAX_RELATIVE_CHANGE + EPS) return "hour $hour: ${before[hour]} -> $rate is a change of more than a third"
            }
            return null
        }

        /** Carb ratio, grams per unit. */
        fun carbRatio(before: List<Double>, changes: Map<Int, Double>): String? {
            changes.forEach { (hour, ratio) ->
                if (ratio !in MIN_IC..MAX_IC) return "hour $hour: a carb ratio of $ratio g/U is outside $MIN_IC..$MAX_IC"
                if (abs(ratio - before[hour]) > before[hour] * MAX_RELATIVE_CHANGE + EPS) return "hour $hour: ${before[hour]} -> $ratio is a change of more than a third"
            }
            return null
        }

        /** The target, in the profile's own units; low and high are moved together and must stay in order. */
        fun target(lowBefore: List<Double>, highBefore: List<Double>, changes: Map<Int, Double>, mgdl: Boolean): String? {
            val range = if (mgdl) MIN_TARGET_MGDL..MAX_TARGET_MGDL else MIN_TARGET_MMOL..MAX_TARGET_MMOL
            val maxStep = if (mgdl) MAX_TARGET_STEP_MGDL else MAX_TARGET_STEP_MMOL
            changes.forEach { (hour, target) ->
                if (target !in range) return "hour $hour: a target of $target is outside ${range.start}..${range.endInclusive} ${if (mgdl) "mg/dl" else "mmol/l"}"
                if (abs(target - lowBefore[hour]) > maxStep + EPS || abs(target - highBefore[hour]) > maxStep + EPS)
                    return "hour $hour: ${lowBefore[hour]}..${highBefore[hour]} -> $target is a step of more than $maxStep"
            }
            return null
        }

        const val MIN_BASAL_UPH = 0.05
        const val MAX_RELATIVE_CHANGE = 1.0 / 3.0
        const val MIN_IC = 2.0
        const val MAX_IC = 50.0
        const val MIN_TARGET_MMOL = 4.5
        const val MAX_TARGET_MMOL = 10.0
        const val MIN_TARGET_MGDL = 81.0
        const val MAX_TARGET_MGDL = 180.0
        const val MAX_TARGET_STEP_MMOL = 1.5
        const val MAX_TARGET_STEP_MGDL = 27.0
        private const val EPS = 1e-9
    }
}
