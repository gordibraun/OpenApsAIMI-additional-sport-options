package app.aaps.pump.combowatch.regulation

import app.aaps.pump.combowatch.regulation.InsulinEffectiveness.ForecastMade
import app.aaps.pump.combowatch.regulation.InsulinEffectiveness.Level
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Insulin that does not act: glucose stays far above what the forecasts of an hour or two ago promised. */
class InsulinEffectivenessTest {
    private val f = Fixture()

    /** A forecast made [minutesAgo] that promised glucose [promised] at every step from then on. */
    private fun forecast(minutesAgo: Double, promised: Int) = ForecastMade(f.minutesAgo(minutesAgo), List(49) { promised })

    private fun verdict(bg: Double, per5: Double, forecasts: List<ForecastMade>, boluses: List<BolusRecord>) =
        InsulinEffectiveness.evaluate(f.now, f.glucose(bg, per5).readings, forecasts, DeliveryLog(boluses = boluses))

    @Test fun `high glucose far above what two forecasts promised, with boluses given, is insulin not acting`() {
        val v = verdict(240.0, 2.0, listOf(forecast(90.0, 130), forecast(70.0, 140)), listOf(BolusRecord(f.minutesAgo(80.0), 2.0)))
        assertThat(v.level).isEqualTo(Level.STRONG)
        // Residuals 100 and 110: the lower median.
        assertThat(v.residualMgdl).isEqualTo(100)
        assertThat(v.bolusesU).isWithin(0.01).of(2.0)
        assertThat(v.text).contains("почти не действует")
        assertThat(v.text).contains("2,0 ЕД".replace(',', '.'))
    }

    @Test fun `a smaller miss is a suspicion, not a verdict`() {
        val v = verdict(200.0, 0.0, listOf(forecast(90.0, 130), forecast(70.0, 135)), listOf(BolusRecord(f.minutesAgo(80.0), 1.5)))
        assertThat(v.level).isEqualTo(Level.SUSPECT)
        assertThat(v.text).contains("хуже, чем ожидалось")
    }

    @Test fun `without boluses in the window nothing is said - that is a meal or basal, not insulin failing`() {
        val v = verdict(240.0, 2.0, listOf(forecast(90.0, 130), forecast(70.0, 140)), emptyList())
        assertThat(v.level).isEqualTo(Level.NONE)
    }

    @Test fun `glucose under 180 is never an insulin alarm`() {
        val v = verdict(170.0, 0.0, listOf(forecast(90.0, 90), forecast(70.0, 95)), listOf(BolusRecord(f.minutesAgo(80.0), 2.0)))
        assertThat(v.level).isEqualTo(Level.NONE)
    }

    @Test fun `forecasts too recent or too old are not compared`() {
        val v = verdict(240.0, 2.0, listOf(forecast(30.0, 130), forecast(150.0, 130)), listOf(BolusRecord(f.minutesAgo(80.0), 2.0)))
        assertThat(v.level).isEqualTo(Level.NONE)
        assertThat(v.comparisons).isEqualTo(0)
    }

    @Test fun `glucose that followed the forecast is fine`() {
        val v = verdict(240.0, 2.0, listOf(forecast(90.0, 235), forecast(70.0, 238)), listOf(BolusRecord(f.minutesAgo(80.0), 2.0)))
        assertThat(v.level).isEqualTo(Level.NONE)
    }
}
