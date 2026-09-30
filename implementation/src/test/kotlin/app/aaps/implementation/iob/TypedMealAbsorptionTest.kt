package app.aaps.implementation.iob

import app.aaps.core.interfaces.aps.AutosensData
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.aps.MealAbsorptionPolicy
import app.aaps.core.objects.aps.MealAbsorptionSchedule
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TypedMealAbsorptionTest {
    private val preferences = mock<Preferences>().also {
        whenever(it.get(DoubleKey.AbsorptionMaxTime)).thenReturn(6.0)
    }
    private fun data() = AutosensDataObject(mock(), preferences, mock())

    @Test fun fallbackAndAllocationKeepCobEqualToTheSumOfMealRemaindersUntilExpiration() {
        val d = data()
        d.cob = 10.0
        d.activeCarbsList.add(AutosensData.CarbsInPast(0L, 10.0, remaining = 10.0, foodType = "balanced"))
        for (minute in 5..170 step 5) {
            d.time = minute * 60_000L
            val r = MealAbsorptionPolicy.calculate(d.time, 0.0, 8.0, 10.3, 55.58743788, d.cob, d.activeCarbsList, true)
            d.this5MinAbsorption = r.totalG
            d.cob = (d.cob - r.totalG).coerceAtLeast(0.0)
            d.deductAbsorbedCarbs(d.time - 300_000L)
            d.removeOldCarbs(d.time, false)
            assertEquals(d.activeCarbsList.sumOf { it.remaining }, d.cob, 1e-8)
            if (minute == 40) assertEquals(10 * MealAbsorptionSchedule.remainingFraction(40.0, "balanced"), d.cob, 1e-8)
        }
        assertEquals(0.0, d.cob, 1e-8)
    }

    @Test fun newFoodIsNotUsedToPayForAbsorptionFromThePreviousBucket() {
        val d = data()
        d.time = 40 * 60_000L
        d.cob = 2.0
        val old = AutosensData.CarbsInPast(0L, 10.0, remaining = 2.0, foodType = "balanced")
        val fresh = AutosensData.CarbsInPast(d.time - 60_000L, 3.0, remaining = 3.0, foodType = "fast")
        d.activeCarbsList.addAll(listOf(old, fresh))
        val r = MealAbsorptionPolicy.calculate(d.time, 100.0, 8.0, 10.3, 55.0, d.cob, d.activeCarbsList, true)
        assertTrue(r.typedFallback)
        d.this5MinAbsorption = r.totalG
        d.cob -= r.totalG
        d.deductAbsorbedCarbs(d.time - 300_000L)
        d.cob += 3.0
        assertEquals(0.0, old.remaining, 1e-8)
        assertEquals(3.0, fresh.remaining, 1e-8)
        assertEquals(3.0, d.cob, 1e-8)
    }
}
