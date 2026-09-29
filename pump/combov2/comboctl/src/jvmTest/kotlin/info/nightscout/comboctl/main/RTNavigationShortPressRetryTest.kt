package info.nightscout.comboctl.main

import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.base.NullDisplayFrame
import info.nightscout.comboctl.base.testUtils.runBlockingWithWatchdog
import info.nightscout.comboctl.parser.ParsedScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Short RT button presses used for fine-tuning are verified against the screen:
 * a press the Combo did not register is repeated, an overshoot is corrected in
 * the other direction, and a quantity that never moves fails instead of looping.
 *
 * Unlike RTNavigationTest.TestRTNavigationContext (a list of distinct observations),
 * this context shows one screen until a press changes it, like a real pump does.
 */
class RTNavigationShortPressRetryTest {
    private class ScriptedRTNavigationContext(
        initial: ParsedScreen,
        private val onShortPress: (pressNumber: Int, button: RTNavigationButton, current: ParsedScreen) -> ParsedScreen
    ) : RTNavigationContext {
        private var current = initial
        private var last: ParsedScreen? = null
        private var blinkedOut = false
        val pressed = mutableListOf<RTNavigationButton>()
        override val maxNumCycleAttempts = 20
        override fun resetDuplicate() { last = null }
        // A setting screen blinks: frames alternate between the value and a blinked-out copy.
        override suspend fun getParsedDisplayFrame(filterDuplicates: Boolean, processAlertScreens: Boolean): ParsedDisplayFrame? {
            while (true) {
                delay(50)
                blinkedOut = !blinkedOut
                val shown = current
                val screen = if (!blinkedOut) shown else when (shown) {
                    is ParsedScreen.TemporaryBasalRatePercentageScreen -> ParsedScreen.TemporaryBasalRatePercentageScreen(null, shown.remainingDurationInMinutes)
                    is ParsedScreen.TemporaryBasalRateDurationScreen -> ParsedScreen.TemporaryBasalRateDurationScreen(null)
                    else -> shown
                }
                if (filterDuplicates && (last == screen)) continue
                last = screen
                return ParsedDisplayFrame(NullDisplayFrame, screen)
            }
        }
        // A held button counts as one press applied after the first predicate evaluation.
        private var longJob: kotlinx.coroutines.Job? = null
        val longPressed = mutableListOf<RTNavigationButton>()
        override suspend fun startLongButtonPress(button: RTNavigationButton, keepGoing: (suspend () -> Boolean)?) {
            longPressed.add(button)
            longJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()).launch {
                var applied = false
                while (keepGoing?.invoke() != false) {
                    if (!applied) { applied = true; current = onShortPress(pressed.size + longPressed.size, button, current) }
                    delay(50)
                }
            }
        }
        override suspend fun stopLongButtonPress() { longJob?.cancel(); longJob = null }
        override suspend fun waitForLongButtonPressToFinish() { longJob?.join(); longJob = null }
        val currentScreen get() = current
        override suspend fun shortPressButton(button: RTNavigationButton) {
            pressed.add(button)
            current = onShortPress(pressed.size, button, current)
        }
    }

    @BeforeTest
    fun init() {
        Logger.threshold = LogLevel.VERBOSE
    }

    private fun tbr(percentage: Int) = ParsedScreen.TemporaryBasalRatePercentageScreen(percentage, remainingDurationInMinutes = 30)
    private fun percentage(screen: ParsedScreen) = (screen as ParsedScreen.TemporaryBasalRatePercentageScreen).percentage
    private fun step(screen: ParsedScreen, button: RTNavigationButton) =
        tbr(percentage(screen)!! + if (button == RTNavigationButton.UP) 10 else -10)

    private fun adjust(context: ScriptedRTNavigationContext, target: Int, timeoutMs: Long = 20000) = runBlockingWithWatchdog(timeoutMs) {
        adjustQuantityOnScreen(
            context,
            targetQuantity = target,
            cyclicQuantityRange = null,
            longRTButtonPressPredicate = { _, _ -> false },
            incrementSteps = arrayOf(Pair(0, 10)),
            getQuantity = ::percentage
        )
    }

    @Test
    fun registeredShortPressesAreNotRepeated() {
        val context = ScriptedRTNavigationContext(tbr(120)) { _, button, current -> step(current, button) }
        adjust(context, 100)
        assertEquals(listOf(RTNavigationButton.DOWN, RTNavigationButton.DOWN), context.pressed)
    }

    @Test
    fun ignoredShortPressIsRepeatedUntilTheQuantityChanges() {
        // The first press is swallowed by the pump; the screen keeps showing 110.
        val context = ScriptedRTNavigationContext(tbr(110)) { pressNumber, button, current ->
            if (pressNumber == 1) current else step(current, button)
        }
        adjust(context, 100)
        assertEquals(listOf(RTNavigationButton.DOWN, RTNavigationButton.DOWN), context.pressed)
    }

    @Test
    fun overshootByAStepIsCorrectedInTheOppositeDirection() {
        // The first press moves two steps at once (120 -> 90); the next press goes back up.
        val context = ScriptedRTNavigationContext(tbr(120)) { pressNumber, button, current ->
            if (pressNumber == 1) tbr(90) else step(current, button)
        }
        adjust(context, 100)
        assertEquals(listOf(RTNavigationButton.DOWN, RTNavigationButton.UP), context.pressed)
    }

    @Test
    fun aRunOfPressesIsNotSentAheadOfThePump() {
        // A pump that only shows the effect of the first press must not receive the rest blindly:
        // each press waits for the screen, so an ignored press is repeated, never accumulated.
        val context = ScriptedRTNavigationContext(tbr(100)) { pressNumber, button, current ->
            if (pressNumber % 2 == 0) current else step(current, button)
        }
        adjust(context, 60, timeoutMs = 60000)
        assertEquals(60, percentage(context.currentScreen))
        // Four accepted presses (100 -> 60) plus the three ignored ones in between.
        assertEquals(7, context.pressed.size)
    }

    @Test
    fun ignoredMenuPressIsRepeatedUntilTheScreenChanges() {
        val context = ScriptedRTNavigationContext(tbr(0)) { pressNumber, _, current ->
            if (pressNumber == 1) current else ParsedScreen.TemporaryBasalRateDurationScreen(30)
        }
        var screen: ParsedScreen? = null
        runBlockingWithWatchdog(20000) {
            screen = pressButtonUntilScreenAppears(context, RTNavigationButton.MENU, ParsedScreen.TemporaryBasalRateDurationScreen::class)
        }
        // waitUntilScreenAppears() returns the first frame of the target type, which may be a blinked-out one.
        assertIs<ParsedScreen.TemporaryBasalRateDurationScreen>(screen)
        assertEquals(listOf(RTNavigationButton.MENU, RTNavigationButton.MENU), context.pressed)
    }

    @Test
    fun screenThatNeverAppearsFailsAfterBoundedPresses() {
        val context = ScriptedRTNavigationContext(tbr(0)) { _, _, current -> current }
        assertFailsWith<CouldNotFindRTScreenException> {
            runBlockingWithWatchdog(20000) {
                pressButtonUntilScreenAppears(context, RTNavigationButton.MENU, ParsedScreen.TemporaryBasalRateDurationScreen::class)
            }
        }
        assertEquals(3, context.pressed.size)
    }

    @Test
    fun aQuantityThatNeverMovesFailsInsteadOfPressingForever() {
        val context = ScriptedRTNavigationContext(tbr(110)) { _, _, current -> current }
        assertFailsWith<QuantityNotChangingException> { adjust(context, 100, timeoutMs = 60000) }
        assertEquals(5, context.pressed.size)
    }
}
