package app.aaps.plugins.aps.openAPSAIMI.learning

import app.aaps.core.data.model.GV
import kotlin.math.pow
import kotlin.math.sqrt

/** Observed time only: a reading never fills a sensor gap beyond one five-minute interval. */
internal object GlycemicStats {
    private const val SAMPLE_MS = 5 * 60_000L

    fun calculate(readings: List<GV>, start: Long, end: Long, minSamples: Int): UnifiedReactivityLearner.GlycemicPerformance? {
        if (end <= start || minSamples < 1) return null
        val samples = readings.asSequence()
            .filter { it.isValid && it.referenceId == null && it.timestamp in start..end }
            .filter { it.value.isFinite() && it.value > 10.0 && it.value != 38.0 }
            .filter { it.noise == null || (it.noise!!.isFinite() && it.noise!! < 3.0) }
            .groupBy { it.timestamp }
            .values.map { duplicates -> duplicates.maxWith(compareBy<GV> { it.dateCreated }.thenBy { it.id }) }
            .sortedBy { it.timestamp }
        if (samples.size < minSamples) return null
        val durations = samples.mapIndexed { index, gv ->
            ((samples.getOrNull(index + 1)?.timestamp ?: end) - gv.timestamp).coerceIn(0, SAMPLE_MS).toDouble()
        }
        val observed = durations.sum()
        // Preserve the previous 60/30-minute minimum, but require elapsed time, not duplicate rows.
        if (observed <= 0.0 || observed < (minSamples - 1) * SAMPLE_MS) return null
        fun percent(predicate: (Double) -> Boolean) =
            samples.indices.sumOf { if (predicate(samples[it].value)) durations[it] else 0.0 } / observed * 100.0

        val mean = samples.indices.sumOf { samples[it].value * durations[it] } / observed
        val variance = samples.indices.sumOf { (samples[it].value - mean).pow(2) * durations[it] } / observed
        var hypos = 0
        var crossings = 0
        samples.forEachIndexed { index, sample ->
            val previous = samples.getOrNull(index - 1)
            val connected = previous != null && sample.timestamp - previous.timestamp <= 2 * SAMPLE_MS
            if (sample.value < 70.0 && (!connected || previous!!.value >= 70.0)) hypos++
            if (connected && ((previous!!.value < 120.0 && sample.value > 120.0) ||
                    (previous.value > 120.0 && sample.value < 120.0))) crossings++
        }
        return UnifiedReactivityLearner.GlycemicPerformance(
            tir70_180 = percent { it in 70.0..180.0 },
            tir70_140 = percent { it in 70.0..140.0 },
            tir140_180 = percent { it > 140.0 && it <= 180.0 },
            tir180_250 = percent { it > 180.0 && it <= 250.0 },
            tir_above_250 = percent { it > 250.0 },
            tir_above_180 = percent { it > 180.0 },
            hypo_count = hypos,
            cv_percent = sqrt(variance) / mean * 100.0,
            crossing_count = crossings,
            mean_bg = mean,
            total_readings = samples.size,
            observedMinutes = observed / 60_000.0,
            coveragePercent = observed / (end - start) * 100.0
        )
    }
}
