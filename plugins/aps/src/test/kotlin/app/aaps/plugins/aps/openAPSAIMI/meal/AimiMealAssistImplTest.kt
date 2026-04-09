package app.aaps.plugins.aps.openAPSAIMI.meal

import app.aaps.core.interfaces.aps.AimiMealInput
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AimiMealAssistImplTest {

    private val logger: AAPSLogger = mockk(relaxed = true)
    private val preferences: Preferences = mockk(relaxed = true)

    private lateinit var sut: AimiMealAssistImpl

    @Before
    fun setUp() {
        every { preferences.get(DoubleKey.OApsAIMISnackFactor) } returns 100.0
        every { preferences.get(DoubleKey.OApsAIMISnackPrebolus) } returns 1.0
        every { preferences.get(DoubleKey.OApsAIMIMealFactor) } returns 100.0
        every { preferences.get(DoubleKey.OApsAIMIMealPrebolus) } returns 0.0
        every { preferences.get(DoubleKey.OApsAIMIBFFactor) } returns 100.0
        every { preferences.get(DoubleKey.OApsAIMIBFPrebolus) } returns 0.0
        every { preferences.get(DoubleKey.OApsAIMILunchFactor) } returns 100.0
        every { preferences.get(DoubleKey.OApsAIMILunchPrebolus) } returns 0.0
        every { preferences.get(DoubleKey.OApsAIMIDinnerFactor) } returns 100.0
        every { preferences.get(DoubleKey.OApsAIMIDinnerPrebolus) } returns 0.0
        every { preferences.get(DoubleKey.OApsAIMIHCFactor) } returns 100.0
        every { preferences.get(DoubleKey.OApsAIMIHighCarbPrebolus) } returns 0.0

        sut = AimiMealAssistImpl(logger, preferences)
    }

    @Test
    fun `below required carbs forces zero bolus even if prebolus would otherwise add insulin`() {
        val input = baseInput(
            carbs = 8,
            requiredCarbs = 15,
            wizardCalculatedBolus = 0.0,
            wizardInsulinFromCarbs = 0.8
        )

        val decision = sut.evaluate(input)

        assertEquals(0.0, decision.recommendedBolus, 0.0)
    }

    @Test
    fun `equal required carbs keeps bolus at zero`() {
        val input = baseInput(
            carbs = 15,
            requiredCarbs = 15,
            wizardCalculatedBolus = 0.0,
            wizardInsulinFromCarbs = 1.5
        )

        val decision = sut.evaluate(input)

        assertEquals(0.0, decision.recommendedBolus, 0.0)
    }

    @Test
    fun `one gram above protective carbs can produce insulin again`() {
        val input = baseInput(
            carbs = 16,
            requiredCarbs = 15,
            wizardCalculatedBolus = 0.10,
            wizardInsulinFromCarbs = 2.67
        )

        val decision = sut.evaluate(input)

        assertEquals(0.1, decision.recommendedBolus, 0.0)
    }

    @Test
    fun `excess carbs beyond protection can produce bolus`() {
        val input = baseInput(
            carbs = 20,
            requiredCarbs = 15,
            wizardCalculatedBolus = 2.0,
            wizardInsulinFromCarbs = 2.0
        )

        val decision = sut.evaluate(input)

        assertEquals(0.5, decision.recommendedBolus, 0.0)
    }

    private fun baseInput(
        carbs: Int,
        requiredCarbs: Int,
        wizardCalculatedBolus: Double,
        wizardInsulinFromCarbs: Double
    ) = AimiMealInput(
        timestamp = 0L,
        profileName = "test",
        selectedFoodType = "balanced",
        bg = 81.0,
        delta = 0.0,
        carbs = carbs,
        requiredCarbs = requiredCarbs,
        cob = 0.0,
        carbTimeMinutes = 0,
        targetBgLow = 117.0,
        targetBgHigh = 117.0,
        ic = 10.0,
        isf = 54.0,
        bolusIob = 0.0,
        basalIob = 0.0,
        wizardRecommendedBolus = wizardCalculatedBolus,
        wizardCalculatedBolus = wizardCalculatedBolus,
        wizardInsulinFromCarbs = wizardInsulinFromCarbs,
        wizardInsulinFromBg = 0.0,
        wizardInsulinFromTrend = 0.0,
        wizardInsulinFromCob = 0.0,
        wizardInsulinFromBolusIob = 0.0,
        wizardInsulinFromBasalIob = 0.0,
        wizardInsulinFromSuperBolus = 0.0,
        correction = 0.0,
        trendInsulin = 0.0,
        notes = ""
    )
}
