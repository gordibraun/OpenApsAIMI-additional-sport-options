package app.aaps.plugins.aps.openAPSAIMI.ISF

internal object TddLearningInput {
    /** A pause between loop runs longer than this is a hole in the day's history. */
    private const val GAP_MS = 30 * 60_000L
    /** One hole longer than this hides too much of the day's dosing to learn from it. */
    private const val MAX_HOLE_MS = 2 * 60 * 60_000L
    /** All holes together may not hide more of the day than this. */
    private const val MAX_HOLES_MS = 3 * 60 * 60_000L

    /**
     * A necessary continuity check, not proof that all pump doses were captured. Holes of up to two
     * hours, three hours in all, are allowed: on 01.11.2026 a single hour without glucose at night
     * froze the daily dose at a stale 56 U for the whole day while the real days were near 40 U,
     * which made every correction a third too large.
     */
    fun continuous(timestamps: List<Long>, start: Long, end: Long): Boolean {
        if (end <= start || end - start < 24 * 60 * 60_000L) return false
        val times = timestamps.filter { it in start..end }.distinct().sorted()
        if (times.isEmpty()) return false
        val holes = (listOf(times.first() - start) + times.zipWithNext { a, b -> b - a } + listOf(end - times.last())).filter { it > GAP_MS }
        return holes.none { it > MAX_HOLE_MS } && holes.sum() <= MAX_HOLES_MS
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
