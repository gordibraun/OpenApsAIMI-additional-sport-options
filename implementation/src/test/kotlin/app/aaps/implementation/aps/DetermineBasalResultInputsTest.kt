package app.aaps.implementation.aps

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.aps.TreatmentInputStamp
import app.aaps.core.objects.aps.BolusInputSnapshot
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DetermineBasalResultInputsTest : TestBaseWithProfile() {
    @Test fun constraintsCloneRetainsActualCalculationInputs() {
        val result = apsResultProvider.get().with(RT(algorithm = APSResult.Algorithm.AIMI, runningDynamicIsf = true))
        result.iobData = arrayOf(IobTotal(10_000_000, iob = 0.34,
            bolusInputs = listOf(TreatmentInputStamp(9_000_000, 1.0, 9_000_001)), bolusInputsSince = 1_000_000))
        result.mealData = MealData().apply { mealCOB = 7.0 }
        result.usePercent = true
        result.percent = 120
        val clone = result.newAndClone()
        assertThat(clone.iobData?.first()?.iob).isEqualTo(0.34)
        assertThat(BolusInputSnapshot.from(clone.iobData?.first())?.lastBolusTime).isEqualTo(9_000_000)
        assertThat(clone.mealData?.mealCOB).isEqualTo(7.0)
        assertThat(clone.usePercent).isTrue()
        assertThat(clone.percent).isEqualTo(120)
        clone.iobData!!.first().iob = 100.0
        assertThat(result.iobData!!.first().iob).isEqualTo(0.34)
    }
}
