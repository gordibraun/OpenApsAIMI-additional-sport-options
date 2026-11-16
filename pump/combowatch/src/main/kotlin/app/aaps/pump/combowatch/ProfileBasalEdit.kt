package app.aaps.pump.combowatch

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * A local profile's basal with some of its hours changed, in the shape the profile editor stores it.
 *
 * The editor keeps basal as blocks («from 03:00: 1.28»); the pump holds 24 hourly rates. Hours are what the
 * owner is given numbers for, so the blocks are spread out to hours, the named hours are replaced, and equal
 * neighbours are joined into blocks again.
 */
internal object ProfileBasalEdit {

    /** The rate in each hour from midnight, from the editor's blocks; null when the blocks cannot be read. */
    fun hourly(blocks: JSONArray): List<Double>? = runCatching {
        val parsed = (0 until blocks.length()).map { blocks.getJSONObject(it) }
            .map { it.getInt("timeAsSeconds") to it.getDouble("value") }
            .sortedBy { it.first }
        check(parsed.isNotEmpty() && parsed.first().first == 0) { "basal does not start at midnight" }
        check(parsed.all { it.first % 3600 == 0 && it.first in 0 until 86_400 }) { "a basal block does not start on the hour" }
        List(24) { hour -> parsed.last { it.first <= hour * 3600 }.second }
    }.getOrNull()

    /** The editor's blocks for 24 hourly rates. */
    fun blocks(hourly: List<Double>): JSONArray {
        require(hourly.size == 24)
        val array = JSONArray()
        hourly.forEachIndexed { hour, rate ->
            if (hour == 0 || rate != hourly[hour - 1])
                array.put(JSONObject().put("time", "%02d:00".format(Locale.US, hour)).put("timeAsSeconds", hour * 3600).put("value", rate))
        }
        return array
    }

    /** «1=1.05,2=1.05,4=1.0»: hour from midnight = U/h. Null when anything in it is not that. */
    fun parse(rates: String): Map<Int, Double>? = runCatching {
        rates.split(',').map { it.trim() }.filter { it.isNotEmpty() }.associate { pair ->
            val (hour, rate) = pair.split('=').map { it.trim() }
            hour.toInt().also { check(it in 0..23) { "hour $it" } } to rate.toDouble().also { check(it.isFinite() && it >= 0.0) { "rate $it" } }
        }.also { check(it.isNotEmpty()) }
    }.getOrNull()

    fun apply(hourly: List<Double>, changes: Map<Int, Double>): List<Double> = hourly.mapIndexed { hour, rate -> changes[hour] ?: rate }
}
