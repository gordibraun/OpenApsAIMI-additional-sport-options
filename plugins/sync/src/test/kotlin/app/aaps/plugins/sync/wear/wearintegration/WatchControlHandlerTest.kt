package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.aps.AimiMealAssist
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.data.model.RM
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.wizard.BolusWizard
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.Mockito.*

class WatchControlHandlerTest {
    private val preferences = mock(Preferences::class.java)
    private val constraints = mock(ConstraintsChecker::class.java)
    private val queue = mock(CommandQueue::class.java)
    private val persistence = mock(PersistenceLayer::class.java)
    private val bus = mock(RxBus::class.java)
    private val date = mock(DateUtil::class.java)
    private val active = mock(ActivePlugin::class.java)
    private val loop = mock(Loop::class.java)
    private val pump = mock(Pump::class.java)
    private val mealCalculator = mock(WatchMealCalculator::class.java)
    private val messages = mutableListOf<EventData.ConfirmAction>()
    private val submitted = mutableListOf<DetailedBolusInfo>()
    private lateinit var handler: WatchControlHandler

    @BeforeEach fun setup() {
        `when`(date.now()).thenReturn(100_000L)
        `when`(preferences.get(BooleanKey.WearControl)).thenReturn(true)
        `when`(constraints.applyCarbsConstraints(any())).thenAnswer { it.getArgument<Constraint<Int>>(0) }
        `when`(constraints.applyBolusConstraints(any())).thenAnswer { it.getArgument<Constraint<Double>>(0) }
        `when`(active.activePump).thenReturn(pump)
        `when`(pump.isInitialized()).thenReturn(true)
        `when`(loop.runningMode).thenReturn(RM.Mode.CLOSED_LOOP)
        doAnswer { invocation ->
            val event = invocation.getArgument<Event>(0)
            if (event is EventMobileToWear && event.payload is EventData.ConfirmAction) messages += event.payload as EventData.ConfirmAction
            null
        }.`when`(bus).send(any())
        `when`(queue.bolus(any(), any())).thenAnswer { submitted += it.getArgument<DetailedBolusInfo>(0); true }
        handler = WatchControlHandler(preferences, mock(ProfileFunction::class.java), mock(ProfileUtil::class.java),
            mock(GlucoseStatusProvider::class.java), loop, active, constraints,
            persistence, queue, bus, mock(AAPSLogger::class.java), mock(UserEntryLogger::class.java), date, mock(AimiMealAssist::class.java), mealCalculator)
    }

    private fun request() = EventData.WatchControlRequest("request", 100_000, "CARBS", carbs = 3).also { it.sourceNodeId = "watch" }
    private fun confirmation(): EventData.WatchControlConfirmed =
        (messages.last().returnCommand as EventData.WatchControlConfirmed).also { it.sourceNodeId = "watch" }

    @Test fun previewNeverRecordsOrDeliversAnything() {
        handler.preview(request())
        assertTrue(messages.last().message.contains("3 г"))
        assertEquals("watch", messages.last().sourceNodeId)
        verifyNoInteractions(queue, persistence)
    }

    @Test fun carbsOnlyGoThroughQueueOnceWithZeroInsulin() {
        handler.preview(request())
        val confirmation = confirmation()
        handler.confirm(confirmation)
        handler.confirm(confirmation)
        assertEquals(1, submitted.size)
        assertEquals(3.0, submitted.single().carbs)
        assertEquals(0.0, submitted.single().insulin)
    }

    @Test fun expiredRequestCannotOpenConfirmation() {
        handler.preview(request().copy(createdAt = 1L).also { it.sourceNodeId = "watch" })
        assertNull(messages.last().returnCommand)
        verifyNoInteractions(queue, persistence)
    }

    @Test fun disabledControlIsRecheckedBeforeExecution() {
        handler.preview(request())
        val confirmation = confirmation()
        `when`(preferences.get(BooleanKey.WearControl)).thenReturn(false)
        handler.confirm(confirmation)
        assertTrue(submitted.isEmpty())
    }

    @Test fun expiredConfirmationIsNotExecuted() {
        handler.preview(request())
        val confirmation = confirmation()
        `when`(date.now()).thenReturn(160_001)
        handler.confirm(confirmation)
        assertTrue(submitted.isEmpty())
    }

    @Test fun modifiedOrForeignConfirmationIsRejected() {
        handler.preview(request())
        val confirmation = confirmation().also { it.sourceNodeId = "other" }
        handler.confirm(confirmation)
        assertTrue(submitted.isEmpty())
    }

    @Test fun malformedQuantityIsNotSubmitted() {
        handler.preview(request().copy(kind = "INSULIN", insulin = Double.NaN).also { it.sourceNodeId = "watch" })
        assertNull(messages.last().returnCommand)
        verifyNoInteractions(queue, persistence)
    }

    @Test fun insulinRequiresConfirmationAndIsNeverRetried() {
        handler.preview(EventData.WatchControlRequest("insulin", 100_000, "INSULIN", insulin = 0.3).also { it.sourceNodeId = "watch" })
        assertTrue(submitted.isEmpty())
        val confirm = confirmation()
        handler.confirm(confirm)
        handler.confirm(confirm)
        assertEquals(1, submitted.size)
        assertEquals(0.3, submitted.single().insulin)
        assertEquals(0.0, submitted.single().carbs)
    }

    @Test fun pumpUnavailableAfterPreviewPreventsBolus() {
        handler.preview(EventData.WatchControlRequest("insulin", 100_000, "INSULIN", insulin = 0.3).also { it.sourceNodeId = "watch" })
        val confirm = confirmation()
        `when`(pump.isInitialized()).thenReturn(false)
        handler.confirm(confirm)
        assertTrue(submitted.isEmpty())
    }

    @Test fun mealPreviewUsesPhoneWizardAndConfirmationExecutesItOnce() {
        val wizard = mock(BolusWizard::class.java)
        `when`(mealCalculator.calculate(3, "slow")).thenReturn(wizard)
        `when`(wizard.insulinAfterConstraints).thenReturn(0.2)
        `when`(wizard.explainShort()).thenReturn("calculation")
        handler.preview(request().copy(kind = "MEAL", carbType = "slow").also { it.sourceNodeId = "watch" })
        assertTrue(messages.last().message.contains("0.2 Е"))
        verify(wizard, never()).executeFromWear(any())
        verifyNoInteractions(queue)
        val confirm = confirmation()
        handler.confirm(confirm)
        handler.confirm(confirm)
        verify(wizard, times(1)).executeFromWear(any())
        verifyNoInteractions(queue)
    }

    @Test fun expiredMealPreviewCannotExecuteWizard() {
        val wizard = mock(BolusWizard::class.java)
        `when`(mealCalculator.calculate(3, "fast")).thenReturn(wizard)
        handler.preview(request().copy(kind = "MEAL").also { it.sourceNodeId = "watch" })
        val confirm = confirmation()
        `when`(date.now()).thenReturn(170_000L)
        handler.confirm(confirm)
        verify(wizard, never()).executeFromWear(any())
    }

    @Test fun mealNeverAcceptsAnInsulinAmountFromTheWatch() {
        handler.preview(request().copy(kind = "MEAL", insulin = 0.5).also { it.sourceNodeId = "watch" })
        verifyNoInteractions(mealCalculator, queue)
        assertNull(messages.last().returnCommand)
    }

    @Test fun carbsOnlyPreserveTypeWithoutCallingCalculator() {
        handler.preview(request().copy(carbType = "slow").also { it.sourceNodeId = "watch" })
        handler.confirm(confirmation())
        assertTrue(submitted.single().notes!!.contains("AIMI_CARB_TYPE type=slow"))
        verifyNoInteractions(mealCalculator)
    }
}
