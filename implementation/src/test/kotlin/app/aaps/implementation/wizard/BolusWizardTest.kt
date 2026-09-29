package app.aaps.implementation.wizard

import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.aps.AimiMealAssist
import app.aaps.core.interfaces.aps.AimiMealDecision
import app.aaps.core.interfaces.aps.AimiMealInput
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.objects.wizard.BolusWizard
import app.aaps.core.keys.StringKey
import app.aaps.plugins.aps.openAPSSMB.OpenAPSSMBPlugin
import app.aaps.plugins.aps.openAPSAIMI.meal.AimiMealAssistImpl
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.invocation.InvocationOnMock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

class BolusWizardTest : TestBaseWithProfile() {

    private val pumpBolusStep = 0.1

    @Mock lateinit var constraintChecker: ConstraintsChecker
    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var loop: Loop
    @Mock lateinit var autosensDataStore: AutosensDataStore
    @Mock lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Mock lateinit var openAPSSMBPlugin: OpenAPSSMBPlugin
    @Mock lateinit var uel: UserEntryLogger
    @Mock lateinit var automation: Automation
    @Mock lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var aimiMealAssist: AimiMealAssist

    @BeforeEach
    fun prepare() {
        whenever(activePlugin.activeAPS).thenReturn(openAPSSMBPlugin)
        whenever(preferences.get(StringKey.GeneralUnits)).thenReturn(GlucoseUnit.MMOL.asText)
        whenever(profileFunction.getUnits()).thenReturn(GlucoseUnit.MMOL)
        // Isolate the wizard arithmetic; meal policy has its own tests.
        whenever(aimiMealAssist.evaluate(any())).thenAnswer {
            val input = it.getArgument<AimiMealInput>(0)
            AimiMealDecision(input.timestamp, input.wizardRecommendedBolus, 0.0, 0.0, 1.0, "none", 1.0, 0.0, "test", "")
        }
    }

    @Suppress("SameParameterValue")
    private fun setupProfile(targetLow: Double, targetHigh: Double, insulinSensitivityFactor: Double, insulinToCarbRatio: Double): Profile {
        val profile: Profile = mock()
        whenever(profile.units).thenReturn(GlucoseUnit.MMOL)
        whenever(profile.getTargetLowMgdl()).thenReturn(targetLow * 18.0)
        whenever(profile.getTargetHighMgdl()).thenReturn(targetHigh * 18.0)
        whenever(profile.getIsfMgdlForCarbs(any(), any(), any(), any())).thenReturn(insulinSensitivityFactor)
        whenever(profile.getIc()).thenReturn(insulinToCarbRatio)

        whenever(iobCobCalculator.calculateIobFromBolus()).thenReturn(IobTotal(System.currentTimeMillis()))
        whenever(iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended()).thenReturn(IobTotal(System.currentTimeMillis()))
        testPumpPlugin.pumpDescription = PumpDescription().also {
            it.bolusStep = pumpBolusStep
        }
        whenever(iobCobCalculator.ads).thenReturn(autosensDataStore)

        doAnswer { invocation: InvocationOnMock ->
            invocation.getArgument<Constraint<Double>>(0)
        }.whenever(constraintChecker).applyBolusConstraints(anyOrNull())
        return profile
    }

    @Test
    fun realMealPolicyKeepsProtectiveCarbsAndExplicitWizardAdjustmentsWithoutModeBonuses() {
        val mealPolicy = AimiMealAssistImpl(aapsLogger, mock())
        whenever(aimiMealAssist.evaluate(any())).thenAnswer {
            mealPolicy.evaluate(it.getArgument<AimiMealInput>(0))
        }
        val profile = setupProfile(4.0, 8.0, 20.0, 10.0)
        data class Case(val protectiveCarbs: Int, val correction: Double, val percentage: Int, val expected: Double)
        val cases = listOf(
            Case(0, 0.0, 100, 3.0),
            Case(5, 0.0, 100, 2.5),
            Case(5, -2.5, 100, 0.0),
            Case(0, 0.0, 50, 1.5)
        )
        for (case in cases) {
            val wizard = BolusWizard(
                aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
                commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
                persistenceLayer, decimalFormatter, processedDeviceStatusData, aimiMealAssist
            ).doCalc(
                profile, "test", null, 30, 0.0, 5.0, case.correction, case.percentage,
                useBg = false, useCob = false, includeBolusIOB = false, includeBasalIOB = false,
                useSuperBolus = false, useTT = false, useTrend = false, useAlarm = false,
                forecastRequiredCarbs = case.protectiveCarbs
            )
            assertThat(wizard.calculatedTotalInsulin).isWithin(0.0001).of(case.expected)
            assertThat(wizard.insulinAfterConstraints).isWithin(0.0001).of(case.expected)
            assertThat(wizard.aimiMealDecision!!.prebolusBonus).isEqualTo(0.0)
        }
    }

    private fun wearWizard(correction: Double = 0.0): BolusWizard {
        val profile = setupProfile(4.0, 8.0, 20.0, 10.0)
        val clock = mock<app.aaps.core.interfaces.utils.DateUtil>()
        whenever(clock.now()).thenReturn(1_000_000L)
        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(profile.toPureNsJson(clock)).thenReturn(JSONObject("{\"profile\":\"stable\"}"))
        whenever(constraintChecker.applyCarbsConstraints(any())).thenAnswer { it.getArgument<Constraint<Int>>(0) }
        val pump = mock<app.aaps.core.interfaces.pump.Pump>()
        whenever(pump.pumpDescription).thenReturn(PumpDescription().also { it.bolusStep = pumpBolusStep })
        whenever(pump.isInitialized()).thenReturn(true)
        whenever(activePlugin.activePump).thenReturn(pump)
        whenever(loop.runningMode).thenReturn(app.aaps.core.data.model.RM.Mode.CLOSED_LOOP)
        whenever(config.APS).thenReturn(true)
        whenever(aimiMealAssist.beginTreatment(any())).thenReturn(true)
        return BolusWizard(
            aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
            commandQueue, loop, iobCobCalculator, clock, config, uel, automation, glucoseStatusProvider, uiInteraction,
            persistenceLayer, decimalFormatter, processedDeviceStatusData, aimiMealAssist
        ).doCalc(profile, "test", null, 13, 0.0, 5.0, correction,
            useBg = false, useCob = false, includeBolusIOB = true, includeBasalIOB = true,
            useSuperBolus = false, useTT = false, useTrend = false, useAlarm = false, selectedFoodType = "slow")
    }

    @Test fun wearConfirmationUsesSharedReceiptsAndPreservesMealType() {
        val wizard = wearWizard()
        lateinit var callback: app.aaps.core.interfaces.queue.Callback
        whenever(commandQueue.bolus(any(), anyOrNull())).thenAnswer {
            val info = it.getArgument<app.aaps.core.interfaces.pump.DetailedBolusInfo>(0)
            assertThat(info.insulin).isEqualTo(wizard.insulinAfterConstraints)
            assertThat(info.carbs).isEqualTo(13.0)
            assertThat(info.notes).contains("AIMI_CARB_TYPE type=slow")
            assertThat(info.bolusCalculatorResult).isNotNull()
            callback = it.getArgument(1)
            true
        }
        val replies = mutableListOf<Pair<Boolean, Double>>()
        wizard.executeFromWear { success, _, amount -> replies += success to amount }
        assertThat(replies).isEmpty()
        val delivered = mock<app.aaps.core.interfaces.pump.PumpEnactResult>()
        whenever(delivered.success).thenReturn(true)
        whenever(delivered.comment).thenReturn("")
        whenever(delivered.bolusDelivered).thenReturn(0.3)
        callback.result(delivered).run()
        assertThat(replies).isEmpty()
        val stored = mock<app.aaps.core.interfaces.pump.PumpEnactResult>()
        whenever(stored.success).thenReturn(true)
        whenever(stored.comment).thenReturn("")
        callback.onCarbsStored(stored)
        assertThat(replies).containsExactly(true to 0.3)
        val decision = org.mockito.kotlin.argumentCaptor<AimiMealDecision>()
        verify(aimiMealAssist).activate(any(), decision.capture())
        assertThat(decision.firstValue.recommendedBolus).isEqualTo(0.3)
        wizard.executeFromWear { success, _, _ -> assertThat(success).isFalse() }
        verify(commandQueue, org.mockito.kotlin.times(1)).bolus(any(), anyOrNull())
    }

    @Test fun wearStaleInputsRejectBeforeQueueSubmission() {
        val wizard = wearWizard()
        whenever(persistenceLayer.getLastBolusId()).thenReturn(99L)
        val replies = mutableListOf<Boolean>()
        wizard.executeFromWear { success, _, _ -> replies += success }
        assertThat(replies).containsExactly(false)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test fun wearZeroDoseMealCanRecordCarbsWithUnavailablePumpOrSnapshot() {
        val wizard = wearWizard(correction = -1.3)
        assertThat(wizard.insulinAfterConstraints).isEqualTo(0.0)
        whenever(profileFunction.getProfile()).thenThrow(IllegalStateException("not available"))
        lateinit var callback: app.aaps.core.interfaces.queue.Callback
        whenever(commandQueue.bolus(any(), anyOrNull())).thenAnswer {
            assertThat(it.getArgument<app.aaps.core.interfaces.pump.DetailedBolusInfo>(0).insulin).isEqualTo(0.0)
            callback = it.getArgument(1)
            true
        }
        val replies = mutableListOf<Boolean>()
        wizard.executeFromWear { success, _, amount -> replies += success; assertThat(amount).isEqualTo(0.0) }
        val stored = mock<app.aaps.core.interfaces.pump.PumpEnactResult>()
        whenever(stored.success).thenReturn(true)
        whenever(stored.comment).thenReturn("")
        callback.onCarbsStored(stored)
        assertThat(replies).containsExactly(true)
        verify(aimiMealAssist, never()).beginTreatment(any())
    }

    @Test
    fun exactThreeRequiredCarbsDoNotCreateHalfAUnitFromNegativeBasalIob() {
        val mealPolicy = AimiMealAssistImpl(aapsLogger, mock())
        whenever(aimiMealAssist.evaluate(any())).thenAnswer {
            mealPolicy.evaluate(it.getArgument<AimiMealInput>(0))
        }
        val profile = setupProfile(4.0, 8.0, 20.0, 10.0)
        whenever(iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended())
            .thenReturn(IobTotal(System.currentTimeMillis()).also { it.basaliob = -0.5 })
        val wizard = BolusWizard(
            aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
            commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
            persistenceLayer, decimalFormatter, processedDeviceStatusData, aimiMealAssist
        ).doCalc(
            profile, "test", null, 3, 0.0, 5.0, 0.0, 100,
            useBg = false, useCob = false, includeBolusIOB = true, includeBasalIOB = true,
            useSuperBolus = false, useTT = false, useTrend = false, useAlarm = false,
            forecastRequiredCarbs = 3,
            forecastRequiredCarbsSource = "APS.carbsReq"
        )
        assertThat(wizard.insulinFromBasalIOB).isEqualTo(-0.5)
        assertThat(wizard.totalBeforePercentageAdjustment).isWithin(0.0001).of(0.8)
        assertThat(wizard.forecastRequiredCarbs).isEqualTo(3)
        assertThat(wizard.forecastRequiredCarbsSource).isEqualTo("APS.carbsReq")
        assertThat(wizard.calculatedTotalInsulin).isEqualTo(0.0)
        assertThat(wizard.insulinAfterConstraints).isEqualTo(0.0)
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test
    fun carbsWithManuallyCancelledInsulinDoNotValidateAnOldInsulinCalculation() {
        val profile = setupProfile(4.0, 8.0, 20.0, 10.0)
        val clock = mock<app.aaps.core.interfaces.utils.DateUtil>()
        whenever(clock.now()).thenReturn(1_000_000L)
        val wizard = BolusWizard(
            aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
            commandQueue, loop, iobCobCalculator, clock, config, uel, automation, glucoseStatusProvider, uiInteraction,
            persistenceLayer, decimalFormatter, processedDeviceStatusData, aimiMealAssist
        ).doCalc(
            profile, "test", null, 13, 0.0, 5.0, -1.3,
            useBg = false, useCob = false, includeBolusIOB = false, includeBasalIOB = false,
            useSuperBolus = false, useTT = false, useTrend = false, useAlarm = false
        )
        assertThat(wizard.calculatedTotalInsulin).isEqualTo(0.0)
        whenever(clock.now()).thenReturn(1_600_000L)
        whenever(profileFunction.getProfile()).thenThrow(IllegalStateException("Insulin inputs unavailable"))
        val confirm = BolusWizard::class.java.getDeclaredMethod("claimConfirmation", android.content.Context::class.java, Boolean::class.javaPrimitiveType, Function1::class.java)
            .also { it.isAccessible = true }
        assertThat(confirm.invoke(wizard, context, true, null)).isEqualTo(1_600_000L)
        assertThat(confirm.invoke(wizard, context, true, null)).isNull()
        verify(aimiMealAssist).markTreatmentAccepted(1_600_000L)
        verify(aimiMealAssist, never()).beginTreatment(any())
        verify(commandQueue, never()).bolusInQueue()
    }

    @Test
    fun mealEpisodeCreditsReportedDeliveryInsteadOfTheRecommendedBolus() {
        val profile = setupProfile(4.0, 8.0, 20.0, 10.0)
        val wizard = BolusWizard(
            aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
            commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
            persistenceLayer, decimalFormatter, processedDeviceStatusData, aimiMealAssist
        ).doCalc(
            profile, "test", null, 18, 0.0, 5.0, 0.0,
            useBg = false, useCob = false, includeBolusIOB = false, includeBasalIOB = false,
            useSuperBolus = false, useTT = false, useTrend = false, useAlarm = false
        )
        val recommendation = wizard.aimiMealDecision!!
        assertThat(recommendation.recommendedBolus).isGreaterThan(0.3)
        val credited = mutableListOf<Double>()
        whenever(aimiMealAssist.activate(any(), any())).thenAnswer {
            credited += it.getArgument<AimiMealDecision>(1).recommendedBolus
            mock<app.aaps.core.interfaces.aps.AimiMealEpisode>()
        }
        val activate = BolusWizard::class.java.getDeclaredMethod("activateMealEpisode", Double::class.javaPrimitiveType)
            .also { it.isAccessible = true }
        listOf(0.3, 0.0, Double.NaN, -1.0, Double.POSITIVE_INFINITY, 1.4).forEach { activate.invoke(wizard, it) }
        assertThat(credited).containsExactly(0.3, 0.0, 0.0, 0.0, 0.0, 1.4).inOrder()
        assertThat(wizard.aimiMealDecision).isEqualTo(recommendation)
    }

    @Test
    fun positiveInsulinCanBeConfirmedWhenRoomForbidsReadsOnTheCallingUiThread() {
        val profile = setupProfile(4.0, 8.0, 20.0, 10.0)
        val clock = mock<app.aaps.core.interfaces.utils.DateUtil>()
        whenever(clock.now()).thenReturn(1_000_000L)
        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(profile.toPureNsJson(clock)).thenReturn(JSONObject("{\"profile\":\"stable\"}"))
        val uiThread = Thread.currentThread()
        val reads = AtomicInteger()
        whenever(persistenceLayer.getLastBolusId()).thenAnswer {
            check(Thread.currentThread() !== uiThread) { "Cannot access database on the main thread" }
            reads.incrementAndGet()
            42L
        }
        val wizard = BolusWizard(
            aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
            commandQueue, loop, iobCobCalculator, clock, config, uel, automation, glucoseStatusProvider, uiInteraction,
            persistenceLayer, decimalFormatter, processedDeviceStatusData, aimiMealAssist
        ).doCalc(
            profile, "test", null, 13, 0.0, 5.0, 0.0,
            useBg = false, useCob = false, includeBolusIOB = false, includeBasalIOB = false,
            useSuperBolus = false, useTT = false, useTrend = false, useAlarm = false
        )
        assertThat(wizard.insulinAfterConstraints).isGreaterThan(0.0)
        assertThat(wizard.hasConfirmationInputs).isTrue()
        val confirm = BolusWizard::class.java.getDeclaredMethod("claimConfirmation", android.content.Context::class.java, Boolean::class.javaPrimitiveType, Function1::class.java)
            .also { it.isAccessible = true }
        assertThat(confirm.invoke(wizard, context, true, null)).isEqualTo(1_000_000L)
        assertThat(reads.get()).isEqualTo(2)
        verify(aimiMealAssist).markTreatmentAccepted(1_000_000L)
        // This test only claims a local confirmation, never enqueues a physical treatment.
        verify(commandQueue, never()).bolus(any(), anyOrNull())
    }

    @Test
    fun databaseFailureIsReportedAsUnavailableNotAsReadyForInsulin() {
        val profile = setupProfile(4.0, 8.0, 20.0, 10.0)
        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(persistenceLayer.getLastBolusId()).thenThrow(IllegalStateException("Database read failed"))
        val wizard = BolusWizard(
            aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
            commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
            persistenceLayer, decimalFormatter, processedDeviceStatusData, aimiMealAssist
        ).doCalc(
            profile, "test", null, 13, 0.0, 5.0, 0.0,
            useBg = false, useCob = false, includeBolusIOB = false, includeBasalIOB = false,
            useSuperBolus = false, useTT = false, useTrend = false, useAlarm = false
        )
        assertThat(wizard.hasConfirmationInputs).isFalse()
    }

    @Test
        /** Should calculate the same bolus when different blood glucose but both in target range  */
    fun shouldCalculateTheSameBolusWhenBGsInRange() {
        val profile = setupProfile(4.0, 8.0, 20.0, 12.0)
        var bw =
            BolusWizard(
                aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
                commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
                persistenceLayer, decimalFormatter, processedDeviceStatusData, aimiMealAssist
            ).doCalc(
                profile,
                "",
                null,
                20,
                0.0,
                4.2,
                0.0,
                100,
                useBg = true,
                useCob = true,
                includeBolusIOB = true,
                includeBasalIOB = true,
                useSuperBolus = false,
                useTT = false,
                useTrend = false,
                useAlarm = false
            )
        val bolusForBg42 = bw.calculatedTotalInsulin
        bw =
            BolusWizard(
                aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
                commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
                persistenceLayer, decimalFormatter, processedDeviceStatusData, aimiMealAssist
            ).doCalc(
                profile,
                "",
                null,
                20,
                0.0,
                5.4,
                0.0,
                100,
                useBg = true,
                useCob = true,
                includeBolusIOB = true,
                includeBasalIOB = true,
                useSuperBolus = false,
                useTT = false,
                useTrend = false,
                useAlarm = false
            )
        val bolusForBg54 = bw.calculatedTotalInsulin
        assertThat(bolusForBg54).isWithin(0.01).of(bolusForBg42)
    }

    @Test
    fun shouldCalculateHigherBolusWhenHighBG() {
        val profile = setupProfile(4.0, 8.0, 20.0, 12.0)
        var bw =
            BolusWizard(
                aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
                commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
                persistenceLayer, decimalFormatter, processedDeviceStatusData, aimiMealAssist
            ).doCalc(
                profile,
                "",
                null,
                20,
                0.0,
                9.8,
                0.0,
                100,
                useBg = true,
                useCob = true,
                includeBolusIOB = true,
                includeBasalIOB = true,
                useSuperBolus = false,
                useTT = false,
                useTrend = false,
                useAlarm = false
            )
        val bolusForHighBg = bw.calculatedTotalInsulin
        bw =
            BolusWizard(
                aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
                commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
                persistenceLayer, decimalFormatter, processedDeviceStatusData, aimiMealAssist
            ).doCalc(
                profile,
                "",
                null,
                20,
                0.0,
                5.4,
                0.0,
                100,
                useBg = true,
                useCob = true,
                includeBolusIOB = true,
                includeBasalIOB = true,
                useSuperBolus = false,
                useTT = false,
                useTrend = false,
                useAlarm = false
            )
        val bolusForBgInRange = bw.calculatedTotalInsulin
        assertThat(bolusForHighBg).isGreaterThan(bolusForBgInRange)
    }

    @Test
    fun shouldCalculateLowerBolusWhenLowBG() {
        val profile = setupProfile(4.0, 8.0, 20.0, 12.0)
        var bw =
            BolusWizard(
                aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
                commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
                persistenceLayer, decimalFormatter, processedDeviceStatusData, aimiMealAssist
            ).doCalc(
                profile,
                "",
                null,
                20,
                0.0,
                3.6,
                0.0,
                100,
                useBg = true,
                useCob = true,
                includeBolusIOB = true,
                includeBasalIOB = true,
                useSuperBolus = false,
                useTT = false,
                useTrend = false,
                useAlarm = false
            )
        val bolusForLowBg = bw.calculatedTotalInsulin
        bw =
            BolusWizard(
                aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
                commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
                persistenceLayer, decimalFormatter, processedDeviceStatusData, aimiMealAssist
            ).doCalc(
                profile,
                "",
                null,
                20,
                0.0,
                5.4,
                0.0,
                100,
                useBg = true,
                useCob = true,
                includeBolusIOB = true,
                includeBasalIOB = true,
                useSuperBolus = false,
                useTT = false,
                useTrend = false,
                useAlarm = false
            )
        val bolusForBgInRange = bw.calculatedTotalInsulin
        assertThat(bolusForLowBg).isLessThan(bolusForBgInRange)
    }
}
