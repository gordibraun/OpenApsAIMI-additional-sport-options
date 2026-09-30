package app.aaps.plugins.aps.openAPSAIMI.ISF

internal object TddLearningInput {
    private const val MAX_GAP_MS = 30 * 60_000L

    // A necessary continuity check, not proof that all pump doses were captured.
    fun continuous(timestamps: List<Long>, start: Long, end: Long): Boolean {
        if (end <= start || end - start < 24 * 60 * 60_000L) return false
        val times = timestamps.filter { it in start..end }.distinct().sorted()
        if (times.isEmpty() || times.first() - start > MAX_GAP_MS || end - times.last() > MAX_GAP_MS) return false
        return times.zipWithNext().all { (a, b) -> b - a <= MAX_GAP_MS }
    }

    data class Selection(val units: Double, val learn: Boolean, val source: String)

    fun select(observedUnits: Double?, previousUnits: Double?, configuredUnits: Double, continuous: Boolean): Selection {
        fun Double?.usable(): Boolean = this != null && isFinite() && this > 0.1
        val rejection = if (!continuous) "incomplete history" else "invalid daily dose"
        return when {
            continuous && observedUnits.usable() -> Selection(observedUnits!!, true, "complete history")
            previousUnits.usable() -> Selection(previousUnits!!, false, "held previous TDD: $rejection")
            configuredUnits.usable() -> Selection(configuredUnits, false, "configured TDD: $rejection")
            else -> Selection(0.0, false, "TDD unavailable")
        }
    }

    fun updatedEma(input: Selection, previousUnits: Double?, alpha: Double): Double? {
        val previous = previousUnits?.takeIf { it.isFinite() && it > 0.1 }
        if (!input.units.isFinite() || input.units <= 0.1) return previous
        if (previous == null) return input.units
        if (!input.learn || !alpha.isFinite()) return previous
        return previous + alpha.coerceIn(0.0, 1.0) * (input.units - previous)
    }
}
