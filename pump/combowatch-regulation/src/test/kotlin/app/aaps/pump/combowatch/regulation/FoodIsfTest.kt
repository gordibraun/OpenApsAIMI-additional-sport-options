package app.aaps.pump.combowatch.regulation

import app.aaps.pump.combowatch.protocol.RegulationSnapshot
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** A meal entered on the watch is corrected by the sensitivity the phone's wizard uses, when the phone sent it. */
class FoodIsfTest {

    @Test fun `the watch's meal correction uses the phone's food sensitivity when the snapshot carries it`() {
        val f = Fixture().apply { glucose(167.0, 0.0) }
        fun advice(snapshot: RegulationSnapshot) = WatchRegulator().adviseBolus(
            WatchRegulator.Inputs(nowEpochMs = f.now, readings = f.readings, snapshot = snapshot, pumpBasalUph = f.basal, delivery = DeliveryLog(), hourOfDay = { 12 }),
            carbsG = 0, maxTenthsIU = 200
        )
        val plain = advice(f.snapshot!!)
        val withFood = advice(f.snapshot!!.copy(foodSensitivityMgdlPerU = 100.0))
        // 50 mg/dL above the target: one unit at the run's sensitivity of 50, half a unit at the wizard's 100.
        assertThat(plain.correctionU).isWithin(0.01).of(1.0)
        assertThat(withFood.correctionU).isWithin(0.01).of(0.5)
        assertThat(withFood.lines.any { it.contains("ISF еды 100") }).isTrue()
        assertThat(plain.lines.any { it.contains("ISF 50") }).isTrue()
    }

    @Test fun `the food sensitivity survives the snapshot's trip through json`() {
        val f = Fixture()
        val sent = f.snapshot!!.copy(foodSensitivityMgdlPerU = 62.4)
        val back = RegulationSnapshot.fromJson(sent.toJson())
        assertThat(back.foodSensitivityMgdlPerU).isWithin(1e-9).of(62.4)
        assertThat(RegulationSnapshot.fromJson(f.snapshot!!.toJson()).foodSensitivityMgdlPerU).isNull()
    }
}
