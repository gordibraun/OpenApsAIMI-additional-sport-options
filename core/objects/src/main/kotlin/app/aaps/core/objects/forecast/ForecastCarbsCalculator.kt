package app.aaps.core.objects.forecast

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.time.T
import kotlin.math.roundToInt

object ForecastCarbsCalculator {

    data class Result(
        val carbs: Int,
        val minBgMgdl: Double,
        val minMinutes: Int,
        val targetMgdl: Double
    )

    fun fromFinalForecast(
        predictions: List<GV>,
        now: Long,
        targetMgdl: Double,
        isfMgdl: Double,
        ic: Double,
        minMinutes: Int = 20,
        maxMinutes: Int = 120
    ): Result? {
        if (!targetMgdl.isFinite() || targetMgdl <= 0.0) return null
        if (!isfMgdl.isFinite() || isfMgdl <= 0.0) return null
        if (!ic.isFinite() || ic <= 0.0) return null

        val minTime = now + T.mins(minMinutes.toLong()).msecs()
        val maxTime = now + T.mins(maxMinutes.toLong()).msecs()
        val minPoint = predictions
            .asSequence()
            .filter { it.sourceSensor.isFinalAimiLineSource() }
            .filter { it.timestamp in minTime..maxTime }
            .filter { it.value.isFinite() && it.value > 0.0 }
            .minByOrNull { it.value }
            ?: return null

        val deficitMgdl = (targetMgdl - minPoint.value).coerceAtLeast(0.0)
        val carbSensitivityMgdlPerGram = isfMgdl / ic
        val carbs = if (carbSensitivityMgdlPerGram > 0.0) {
            (deficitMgdl / carbSensitivityMgdlPerGram).roundToInt().coerceAtLeast(0)
        } else {
            0
        }

        return Result(
            carbs = carbs,
            minBgMgdl = minPoint.value,
            minMinutes = ((minPoint.timestamp - now) / T.mins(1).msecs()).toInt(),
            targetMgdl = targetMgdl
        )
    }

    private fun SourceSensor.isFinalAimiLineSource(): Boolean =
        this == SourceSensor.AIMI_FINAL_PREDICTION ||
            this == SourceSensor.AIMI_ACTIVITY_WAITING_PREDICTION ||
            this == SourceSensor.AIMI_ACTIVITY_ACTIVE_PREDICTION ||
            this == SourceSensor.AIMI_ACTIVITY_TAIL_PREDICTION
}
