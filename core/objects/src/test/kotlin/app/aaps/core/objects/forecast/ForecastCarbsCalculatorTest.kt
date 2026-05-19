package app.aaps.core.objects.forecast

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ForecastCarbsCalculatorTest {

    @Test
    fun returnsCarbsNeededToBringFinalForecastBackToTarget() {
        val now = 1_000_000L
        val result = ForecastCarbsCalculator.fromFinalForecast(
            predictions = listOf(
                prediction(now, 15, 80.0),
                prediction(now, 30, 90.0),
                prediction(now, 60, 70.0),
                prediction(now, 150, 60.0)
            ),
            now = now,
            targetMgdl = 110.0,
            isfMgdl = 40.0,
            ic = 8.0
        )

        assertThat(result?.carbs).isEqualTo(8)
        assertThat(result?.minBgMgdl).isEqualTo(70.0)
        assertThat(result?.minMinutes).isEqualTo(60)
    }

    @Test
    fun returnsZeroWhenFinalForecastStaysAboveTarget() {
        val now = 1_000_000L
        val result = ForecastCarbsCalculator.fromFinalForecast(
            predictions = listOf(
                prediction(now, 30, 130.0),
                prediction(now, 60, 125.0)
            ),
            now = now,
            targetMgdl = 110.0,
            isfMgdl = 40.0,
            ic = 8.0
        )

        assertThat(result?.carbs).isEqualTo(0)
        assertThat(result?.minBgMgdl).isEqualTo(125.0)
    }

    @Test
    fun ignoresNonFinalPredictionLines() {
        val now = 1_000_000L
        val result = ForecastCarbsCalculator.fromFinalForecast(
            predictions = listOf(
                prediction(now, 60, 60.0, SourceSensor.AIMI_FINAL_PREDICTION_STALE),
                prediction(now, 60, 115.0, SourceSensor.AIMI_FINAL_PREDICTION)
            ),
            now = now,
            targetMgdl = 110.0,
            isfMgdl = 40.0,
            ic = 8.0
        )

        assertThat(result?.carbs).isEqualTo(0)
        assertThat(result?.minBgMgdl).isEqualTo(115.0)
    }

    private fun prediction(
        now: Long,
        minutes: Int,
        value: Double,
        sourceSensor: SourceSensor = SourceSensor.AIMI_FINAL_PREDICTION
    ) = GV(
        timestamp = now + T.mins(minutes.toLong()).msecs(),
        raw = null,
        value = value,
        trendArrow = TrendArrow.NONE,
        noise = null,
        sourceSensor = sourceSensor
    )
}
