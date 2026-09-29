package app.aaps.plugins.aps.openAPSAIMI.pkpd

internal object ForecastDecisionSearch {
    fun candidateBolus(proposed: Double, fraction: Double, pumpStep: Double): Double {
        if (!proposed.isFinite() || !fraction.isFinite() || !pumpStep.isFinite() || pumpStep <= 0.0) return 0.0
        val units = proposed.coerceAtLeast(0.0) * fraction.coerceIn(0.0, 1.0)
        return (kotlin.math.floor((units + 1e-9) / pumpStep) * pumpStep).coerceIn(0.0, proposed.coerceAtLeast(0.0))
    }


    // Every accepted candidate is evaluated with the same model, including pump rounding.
    // If removing all extra insulin is still unsafe, do not add any of it back.
    fun safeFraction(isSafe: (Double) -> Boolean): Double {
        if (isSafe(1.0)) return 1.0
        if (!isSafe(0.0)) return 0.0
        var safe = 0.0
        var unsafe = 1.0
        repeat(6) {
            val candidate = (safe + unsafe) / 2.0
            if (isSafe(candidate)) safe = candidate else unsafe = candidate
        }
        return safe
    }
}
