package app.aaps.plugins.aps.openAPSAIMI.ISF

import kotlinx.serialization.Serializable
import java.util.TreeMap

@Serializable
data class IsfSample(val timestamp: Long, val glucose: Double, val isf: Double)

@Serializable
data class StoredIsfHistory(val schema: Int = 1, val samples: List<IsfSample>)

/** The same decision samples feed the food average both live and after database restoration. */
class IsfHistory {
    private val samples = TreeMap<Long, IsfSample>()

    @Synchronized
    fun add(sample: IsfSample, now: Long) {
        if (!recentIsfState(sample.timestamp, now) || !sample.isf.isFinite() || sample.isf <= 0 ||
            !sample.glucose.isFinite() || sample.glucose <= 0) return
        // Retain the existing half-hour/BG sampling, not a UI-poll-frequency weighted average.
        val bucket = sample.timestamp - sample.timestamp % (30 * 60 * 1000L) + sample.glucose.toLong()
        if ((samples[bucket]?.timestamp ?: 0) <= sample.timestamp) samples[bucket] = sample
        samples.entries.removeAll { !recentIsfState(it.value.timestamp, now) }
    }

    @Synchronized
    fun restore(saved: StoredIsfHistory?, now: Long) {
        if (saved?.schema == 1) saved.samples.forEach { add(it, now) }
    }

    @Synchronized
    fun snapshot(now: Long): StoredIsfHistory =
        StoredIsfHistory(samples = samples.values.filter { recentIsfState(it.timestamp, now) })

    @Synchronized
    fun average(timestamp: Long): Double? =
        samples.values.filter { recentIsfState(it.timestamp, timestamp) }.map { it.isf }
            .takeIf { it.isNotEmpty() }?.average()
}
