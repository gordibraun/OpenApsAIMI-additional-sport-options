package app.aaps.plugins.aps.openAPSAIMI.meal

import app.aaps.core.interfaces.aps.AimiMealInput
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `fast carbs receive reduced carb bolus without prebolus boost`() {
        val balanced = sut.evaluate(
            baseInput(
                carbs = 10,
                requiredCarbs = 0,
                wizardCalculatedBolus = 1.0,
                wizardInsulinFromCarbs = 1.0,
                selectedFoodType = "balanced"
            )
        )
        val fast = sut.evaluate(
            baseInput(
                carbs = 10,
                requiredCarbs = 0,
                wizardCalculatedBolus = 1.0,
                wizardInsulinFromCarbs = 1.0,
                selectedFoodType = "fast"
            )
        )

        assertTrue(fast.recommendedBolus < balanced.recommendedBolus)
        assertEquals(0.0, fast.prebolusBonus, 0.0)
        assertEquals(0.80, fast.recommendedBolus, 0.0)
    }

    @Test
    fun `future activity factor reduces ordinary meal bolus`() {
        val withoutActivity = sut.evaluate(
            baseInput(
                carbs = 20,
                requiredCarbs = 0,
                wizardCalculatedBolus = 2.0,
                wizardInsulinFromCarbs = 2.0,
                activityNewInsulinFactor = 1.0
            )
        )
        val withWalk = sut.evaluate(
            baseInput(
                carbs = 20,
                requiredCarbs = 0,
                wizardCalculatedBolus = 2.0,
                wizardInsulinFromCarbs = 2.0,
                activityNewInsulinFactor = 0.8
            )
        )

        assertEquals(2.0, withoutActivity.recommendedBolus, 0.0)
        assertEquals(1.6, withWalk.recommendedBolus, 0.0)
    }

    @Test
    fun `manual negative correction reduces final AIMI bolus even with protective carbs`() {
        val withoutCorrection = sut.evaluate(
            baseInput(
                carbs = 10,
                requiredCarbs = 3,
                wizardCalculatedBolus = 1.0,
                wizardInsulinFromCarbs = 1.0,
                correction = 0.0
            )
        )
        val withCorrection = sut.evaluate(
            baseInput(
                carbs = 10,
                requiredCarbs = 3,
                wizardCalculatedBolus = 0.5,
                wizardInsulinFromCarbs = 1.0,
                correction = -0.5
            )
        )

        assertEquals(0.7, withoutCorrection.recommendedBolus, 0.0)
        assertEquals(0.2, withCorrection.recommendedBolus, 0.0)
    }

    @Test
    fun `protective carbs are handled but later unbolused extra carbs remain available for COB insulin`() {
        val now = System.currentTimeMillis()
        val protectiveInput = baseInput(
            timestamp = now,
            carbs = 12,
            requiredCarbs = 12,
            wizardCalculatedBolus = 0.0,
            wizardInsulinFromCarbs = 2.0,
            selectedFoodType = "fast"
        )
        sut.activate(protectiveInput, sut.evaluate(protectiveInput))

        val extraInput = baseInput(
            timestamp = now + 1_000L,
            carbs = 8,
            requiredCarbs = 0,
            wizardCalculatedBolus = 0.0,
            wizardInsulinFromCarbs = 1.33,
            selectedFoodType = "balanced"
        )
        sut.activate(extraInput, sut.evaluate(extraInput))

        val activeEpisode = checkNotNull(sut.activeEpisode())
        assertEquals(20, activeEpisode.carbs)
        assertEquals(12, activeEpisode.cobHandledCarbs)
    }

    @Test
    fun `bolused fast carbs are fully handled for later COB insulin`() {
        val now = System.currentTimeMillis()
        val fastInput = baseInput(
            timestamp = now,
            carbs = 12,
            requiredCarbs = 0,
            wizardCalculatedBolus = 1.0,
            wizardInsulinFromCarbs = 1.5,
            selectedFoodType = "fast"
        )

        sut.activate(fastInput, sut.evaluate(fastInput))

        val activeEpisode = checkNotNull(sut.activeEpisode())
        assertEquals(12, activeEpisode.carbs)
        assertEquals(12, activeEpisode.cobHandledCarbs)
    }

    @Test
    fun `mixed active carb types use balanced forecast type`() {
        val now = System.currentTimeMillis()
        val fastInput = baseInput(
            timestamp = now - 5 * 60_000L,
            carbs = 10,
            requiredCarbs = 0,
            wizardCalculatedBolus = 0.8,
            wizardInsulinFromCarbs = 1.0,
            selectedFoodType = "fast"
        )
        val balancedInput = baseInput(
            timestamp = now,
            carbs = 30,
            requiredCarbs = 0,
            wizardCalculatedBolus = 3.0,
            wizardInsulinFromCarbs = 3.0,
            selectedFoodType = "balanced"
        )

        sut.activate(fastInput, sut.evaluate(fastInput))
        sut.activate(balancedInput, sut.evaluate(balancedInput))

        assertEquals("balanced", sut.activeEpisode()?.selectedFoodType)
    }

    @Test
    fun `dominant active carb type is preserved for forecast`() {
        val now = System.currentTimeMillis()
        val fastInput = baseInput(
            timestamp = now - 5 * 60_000L,
            carbs = 35,
            requiredCarbs = 0,
            wizardCalculatedBolus = 2.8,
            wizardInsulinFromCarbs = 3.5,
            selectedFoodType = "fast"
        )
        val balancedInput = baseInput(
            timestamp = now,
            carbs = 5,
            requiredCarbs = 0,
            wizardCalculatedBolus = 0.5,
            wizardInsulinFromCarbs = 0.5,
            selectedFoodType = "balanced"
        )

        sut.activate(fastInput, sut.evaluate(fastInput))
        sut.activate(balancedInput, sut.evaluate(balancedInput))

        assertEquals("fast", sut.activeEpisode()?.selectedFoodType)
    }

    @Test
    fun `single active fast carb episode decays instead of staying full`() {
        val now = System.currentTimeMillis()
        val fastInput = baseInput(
            timestamp = now - 30 * 60_000L,
            carbs = 30,
            requiredCarbs = 0,
            wizardCalculatedBolus = 2.4,
            wizardInsulinFromCarbs = 3.0,
            selectedFoodType = "fast"
        )

        sut.activate(fastInput, sut.evaluate(fastInput))

        val activeEpisode = checkNotNull(sut.activeEpisode())
        assertEquals("fast", activeEpisode.selectedFoodType)
        assertEquals(6, activeEpisode.carbs)
    }

    @Test
    fun `single fast carb episode expires after fast absorption even inside active window`() {
        val now = System.currentTimeMillis()
        val fastInput = baseInput(
            timestamp = now - 50 * 60_000L,
            carbs = 30,
            requiredCarbs = 0,
            wizardCalculatedBolus = 2.4,
            wizardInsulinFromCarbs = 3.0,
            selectedFoodType = "fast"
        )

        sut.activate(fastInput, sut.evaluate(fastInput))

        assertNull(sut.activeEpisode())
    }

    @Test
    fun `single active balanced carb episode decays instead of staying full`() {
        val now = System.currentTimeMillis()
        val balancedInput = baseInput(
            timestamp = now - 60 * 60_000L,
            carbs = 33,
            requiredCarbs = 0,
            wizardCalculatedBolus = 3.3,
            wizardInsulinFromCarbs = 3.3,
            selectedFoodType = "balanced"
        )

        sut.activate(balancedInput, sut.evaluate(balancedInput))

        val activeEpisode = checkNotNull(sut.activeEpisode())
        assertEquals("balanced", activeEpisode.selectedFoodType)
        assertEquals(16, activeEpisode.carbs)
    }

    @Test
    fun `single balanced carb episode expires after balanced absorption even inside active window`() {
        val now = System.currentTimeMillis()
        val balancedInput = baseInput(
            timestamp = now - 170 * 60_000L,
            carbs = 33,
            requiredCarbs = 0,
            wizardCalculatedBolus = 3.3,
            wizardInsulinFromCarbs = 3.3,
            selectedFoodType = "balanced"
        )

        sut.activate(balancedInput, sut.evaluate(balancedInput))

        assertNull(sut.activeEpisode())
    }

    @Test
    fun `single active slow carb episode decays instead of staying full`() {
        val now = System.currentTimeMillis()
        val slowInput = baseInput(
            timestamp = now - 120 * 60_000L,
            carbs = 48,
            requiredCarbs = 0,
            wizardCalculatedBolus = 4.4,
            wizardInsulinFromCarbs = 4.8,
            selectedFoodType = "slow"
        )

        sut.activate(slowInput, sut.evaluate(slowInput))

        val activeEpisode = checkNotNull(sut.activeEpisode())
        assertEquals("slow", activeEpisode.selectedFoodType)
        assertEquals(16, activeEpisode.carbs)
    }

    @Test
    fun `single slow carb episode expires after slow absorption even inside active window`() {
        val now = System.currentTimeMillis()
        val slowInput = baseInput(
            timestamp = now - 250 * 60_000L,
            carbs = 48,
            requiredCarbs = 0,
            wizardCalculatedBolus = 4.4,
            wizardInsulinFromCarbs = 4.8,
            selectedFoodType = "slow"
        )

        sut.activate(slowInput, sut.evaluate(slowInput))

        assertNull(sut.activeEpisode())
    }

    private fun baseInput(
        timestamp: Long = 0L,
        carbs: Int,
        requiredCarbs: Int,
        wizardCalculatedBolus: Double,
        wizardInsulinFromCarbs: Double,
        selectedFoodType: String = "balanced",
        correction: Double = 0.0,
        activityNewInsulinFactor: Double = 1.0,
        bg: Double = 81.0,
        delta: Double = 0.0,
        bolusIob: Double = 0.0,
        basalIob: Double = 0.0
    ) = AimiMealInput(
        timestamp = timestamp,
        profileName = "test",
        selectedFoodType = selectedFoodType,
        bg = bg,
        delta = delta,
        carbs = carbs,
        requiredCarbs = requiredCarbs,
        cob = 0.0,
        carbTimeMinutes = 0,
        targetBgLow = 117.0,
        targetBgHigh = 117.0,
        ic = 10.0,
        isf = 54.0,
        bolusIob = bolusIob,
        basalIob = basalIob,
        wizardRecommendedBolus = wizardCalculatedBolus,
        wizardCalculatedBolus = wizardCalculatedBolus,
        wizardInsulinFromCarbs = wizardInsulinFromCarbs,
        wizardInsulinFromBg = 0.0,
        wizardInsulinFromTrend = 0.0,
        wizardInsulinFromCob = 0.0,
        wizardInsulinFromBolusIob = 0.0,
        wizardInsulinFromBasalIob = 0.0,
        wizardInsulinFromSuperBolus = 0.0,
        correction = correction,
        trendInsulin = 0.0,
        notes = "",
        activityNewInsulinFactor = activityNewInsulinFactor
    )
}
