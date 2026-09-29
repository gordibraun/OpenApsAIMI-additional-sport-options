package app.aaps.plugins.aps.openAPSAIMI.pkpd

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

internal data class ForecastCarbImpact(
    val observedMgdlPer5m: Double,
    val remainingPeakMgdlPer5m: Double,
    val absorptionHours: Double
) {
    companion object {
        fun calculate(
            minDelta: Double,
            insulinActivity: Double,
            decisionIsf: Double,
            csf: Double,
            cobG: Double,
            sensitivityRatio: Double,
            remainingCarbsCap: Int
        ): ForecastCarbImpact {
            val ratio = sensitivityRatio.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
            val hours = 2.0 / ratio
            if (!csf.isFinite() || csf <= 0.0 || !minDelta.isFinite() ||
                !insulinActivity.isFinite() || !decisionIsf.isFinite() || decisionIsf <= 0.0 || !cobG.isFinite()
            ) return ForecastCarbImpact(0.0, 0.0, hours)

            // Both sides are glucose change per five minutes, never grams per unit (IC).
            val insulinImpact = round(-insulinActivity * decisionIsf * 5.0 * 100.0) / 100.0
            val maxImpact = round(30.0 * csf * 5.0 / 60.0 * 10.0) / 10.0
            val observed = min(round((minDelta - insulinImpact) * 10.0) / 10.0, maxImpact).coerceAtLeast(0.0)
            val observedCarbs = observed / 5.0 * 60.0 * hours / 2.0 / csf
            val remaining = max(0.0, cobG - observedCarbs).coerceAtMost(remainingCarbsCap.coerceIn(0, 90).toDouble())
            return ForecastCarbImpact(observed, remaining * csf * 5.0 / 60.0 / (hours / 2.0), hours)
        }
    }
}
