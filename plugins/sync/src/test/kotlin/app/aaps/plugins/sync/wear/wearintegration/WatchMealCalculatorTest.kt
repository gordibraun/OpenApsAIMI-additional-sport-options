package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.data.iob.CobInfo
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.aps.*
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.wizard.BolusWizard
import io.reactivex.rxjava3.core.Single
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.*
import javax.inject.Provider

class WatchMealCalculatorTest {
    private val profileFunction = mock<ProfileFunction>()
    private val profile = mock<Profile>()
    private val iob = mock<IobCobCalculator>()
    private val ads = mock<AutosensDataStore>()
    private val db = mock<PersistenceLayer>()
    private val loop = mock<Loop>()
    private val meal = mock<AimiMealAssist>()
    private val prefs = mock<Preferences>()
    private val date = mock<DateUtil>()
    private val config = mock<Config>()
    private val provider = mock<Provider<BolusWizard>>()
    private val now = 50_000_000L
    private val calls = mutableListOf<List<Any?>>()
    private lateinit var wizard: BolusWizard
    private lateinit var calculator: WatchMealCalculator

    @BeforeEach fun setup() {
        wizard = Mockito.mock(BolusWizard::class.java) { invocation ->
            when (invocation.method.name) {
                "doCalc" -> { calls += invocation.arguments.toList(); invocation.mock }
                "getHasConfirmationInputs" -> true
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        whenever(provider.get()).thenReturn(wizard)
        whenever(date.now()).thenReturn(now)
        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(profileFunction.getProfileName()).thenReturn("current")
        whenever(profileFunction.getUnits()).thenReturn(GlucoseUnit.MGDL)
        whenever(iob.ads).thenReturn(ads)
        whenever(ads.actualBg()).thenReturn(InMemoryGlucoseValue(timestamp = now, value = 110.0))
        whenever(iob.getCobInfo(any())).thenReturn(CobInfo(now, 5.0, 0.0))
        whenever(db.getTherapyEventDataFromToTime(any(), any())).thenReturn(Single.just(emptyList()))
        whenever(prefs.get(IntKey.OverviewBolusPercentage)).thenReturn(100)
        whenever(config.APS).thenReturn(true)
        val result = mock<APSResult>()
        whenever(result.date).thenReturn(now)
        whenever(result.carbsReq).thenReturn(3)
        whenever(loop.lastRun).thenReturn(Loop.LastRun().also { it.constraintsProcessed = result })
        calculator = WatchMealCalculator(profileFunction, iob, db, loop, meal, prefs, date, config, provider)
    }

    @Test fun delegatesToPhoneWizardWithFoodTypeProtectiveCarbsAndPhonePreferences() {
        whenever(prefs.get(BooleanKey.WizardIncludeCob)).thenReturn(true)
        whenever(prefs.get(BooleanKey.WizardIncludeTrend)).thenReturn(true)
        assertSame(wizard, calculator.calculate(3, "slow"))
        val args = calls.single()
        assertSame(profile, args[0])
        assertEquals(3, args[3])
        assertEquals(5.0, args[4])
        assertEquals(100, args[7])
        assertEquals(true, args[9])
        assertEquals(true, args[10])
        assertEquals(true, args[11])
        assertEquals(false, args[12])
        assertEquals(true, args[14])
        assertEquals("slow", args[18])
        assertEquals(3, args[23])
        assertEquals("APS.carbsReq", args[26])
        verify(wizard, never()).executeFromWear(any())
    }

    @Test fun usesSameActiveActivityFactorAsPhone() {
        val event = TE(timestamp = now, type = TE.Type.EXERCISE, duration = 30 * 60_000L,
            glucoseUnit = GlucoseUnit.MGDL, note = "AIMI_ACTIVITY_V2 mode=SPORT duration=30 tail=60")
        whenever(db.getTherapyEventDataFromToTime(any(), any())).thenReturn(Single.just(listOf(event)))
        calculator.calculate(10, "balanced")
        assertEquals(0.7, calls.single()[24])
    }

    @Test fun staleGlucoseDoesNotReachCalculator() {
        whenever(ads.actualBg()).thenReturn(InMemoryGlucoseValue(timestamp = now - 7 * 60_000, value = 110.0))
        assertThrows(IllegalArgumentException::class.java) { calculator.calculate(10, "fast") }
        verifyNoInteractions(provider)
    }

    @Test fun unknownCarbRequirementIsNotSilentlyZero() {
        whenever(loop.lastRun).thenReturn(null)
        calculator.calculate(10, "balanced")
        assertNull(calls.single()[23])
    }
}
