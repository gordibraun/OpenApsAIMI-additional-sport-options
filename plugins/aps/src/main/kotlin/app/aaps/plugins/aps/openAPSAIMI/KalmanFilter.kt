package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.keys.DoubleKey
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.ISF.KalmanIsfState
import kotlin.math.abs
import kotlin.math.ln

/**
 * Classe de filtre de Kalman simple permettant de lisser les mesures d'ISF.
 */
class KalmanFilter(
    var stateEstimate: Double,
    var estimationError: Double,
    var processVariance: Double,
    var measurementVariance: Double
) {
    /**
     * Mise à jour du filtre avec une nouvelle mesure.
     */
    fun update(measurement: Double): Double {
        // Étape de prédiction
        val prediction = stateEstimate
        val predictionError = estimationError + processVariance

        // Calcul du gain de Kalman
        val kalmanGain = predictionError / (predictionError + measurementVariance)

        // Mise à jour de l'estimation de l'état
        stateEstimate = prediction + kalmanGain * (measurement - prediction)
        estimationError = (1 - kalmanGain) * predictionError

        return stateEstimate
    }
}

/**
 * Calculateur de l’ISF utilisant une approche par filtre de Kalman.
 *
 * L’estimation de l’ISF se base sur une mesure brute obtenue avec une formule classique,
 * puis le filtre de Kalman lisse cette valeur pour obtenir une estimation plus stable et réactive.
 */
class KalmanISFCalculator(
    private val tddCalculator: TddCalculator,
    private val preferences: Preferences,
    private val logger: AAPSLogger
) {
    companion object {
        private const val MIN_ISF = 5.0
        private const val MAX_ISF = 300.0
        private const val BASE_CONSTANT = 75.0
        private const val SCALING_FACTOR = 1800.0
    }

    private var kalmanFilter: KalmanFilter? = null

    fun snapshot(): KalmanIsfState? = kalmanFilter?.let { KalmanIsfState(it.stateEstimate, it.estimationError) }

    fun restore(state: KalmanIsfState?) {
        kalmanFilter = state?.takeIf { it.isValid() }?.let {
            // Measurement variance is recomputed from current deltas on the next update.
            KalmanFilter(it.estimate, it.error, 0.5, 2.0)
        }
    }

    private fun computeEffectiveTDD(): Double {
        val tdd7P = preferences.get(DoubleKey.OApsAIMITDD7)
        val tdd7D = tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = false))?.data?.totalAmount ?: tdd7P
        val tdd2Days = tddCalculator.averageTDD(tddCalculator.calculate(2, allowMissingDays = false))?.data?.totalAmount ?: tdd7P
        val tddDaily = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = false))?.data?.totalAmount ?: tdd7P
        return (0.2 * tdd7D) + (0.4 * tdd2Days) + (0.4 * tddDaily)
    }

    private fun computeRawISF(glucose: Double): Double {
        val effectiveTDD = computeEffectiveTDD()
        val safeTDD = if (effectiveTDD < 1.0) 1.0 else effectiveTDD

        // Glucose is already part of the logarithmic dynISF formula. Applying another
        // stepwise BG multiplier here counted the same hyperglycaemia twice and could
        // collapse ISF five-fold at 180 mg/dL.
        val rawISF = SCALING_FACTOR / (safeTDD * ln(glucose / BASE_CONSTANT + 1))
        return rawISF.coerceIn(MIN_ISF, MAX_ISF)
    }

    fun calculateISF(glucose: Double, currentDelta: Double?, predictedDelta: Double?): Double {
        val rawISF = computeRawISF(glucose)
        logger.debug(LTag.APS, "Raw ISF calculé : $rawISF pour BG = $glucose")

        // Calculate the combined influence of current and predicted deltas
        var deltaInfluence = 0.0

        if (currentDelta != null) {
            deltaInfluence += abs(currentDelta)
        }

        if (predictedDelta != null) {
            deltaInfluence += abs(predictedDelta)
        }

        // A rapid move is a less reliable moment for estimating insulin sensitivity.
        // Lower Kalman trust instead of chasing the transient glucose slope.
        val newMeasurementVariance = when {
            deltaInfluence > 8 -> 8.0
            deltaInfluence > 4 -> 4.0
            else -> 2.0
        }

        val filter = kalmanFilter ?: KalmanFilter(
            stateEstimate = rawISF,
            estimationError = 4.0,
            processVariance = 0.5,
            measurementVariance = newMeasurementVariance
        ).also { kalmanFilter = it }
        filter.measurementVariance = newMeasurementVariance

        val filteredISF = filter.update(rawISF).coerceIn(MIN_ISF, MAX_ISF)
        logger.debug(LTag.APS, "ISF filtré par Kalman : $filteredISF (variance de mesure = ${filter.measurementVariance})")
        return filteredISF
    }

}
