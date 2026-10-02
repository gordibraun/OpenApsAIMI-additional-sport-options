package info.nightscout.comboctl.main

import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.base.NullDisplayFrame
import info.nightscout.comboctl.base.RTLinkProfile
import info.nightscout.comboctl.base.testUtils.runBlockingWithWatchdog
import info.nightscout.comboctl.parser.MainScreenContent
import info.nightscout.comboctl.parser.ParsedScreen
import kotlinx.coroutines.delay
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * The slow-link pacing (see [RTLinkProfile]) against a scripted pump. The standard pacing has its
 * own tests, which run with the switch at its default; these turn it on and restore it afterwards
 * so the two never mix.
 */
class RTNavigationSlowLinkTest {
    private class ScriptedContext(
        initial: ParsedScreen,
        private val onShortPress: (pressNumber: Int, button: RTNavigationButton, current: ParsedScreen) -> ParsedScreen
    ) : RTNavigationContext {
        var current = initial
        private var last: ParsedScreen? = null
        private var blinkedOut = false
        val pressed = mutableListOf<RTNavigationButton>()
        var framesServed = 0
        override val maxNumCycleAttempts = 20
        override fun resetDuplicate() { last = null }

        // A setting screen blinks: frames alternate between the value and a blinked-out copy.
        override suspend fun getParsedDisplayFrame(filterDuplicates: Boolean, processAlertScreens: Boolean): ParsedDisplayFrame? {
            while (true) {
                delay(50)
                blinkedOut = !blinkedOut
                val shown = current
                val screen = if (!blinkedOut) shown else when (shown) {
                    is ParsedScreen.TemporaryBasalRatePercentageScreen ->
                        ParsedScreen.TemporaryBasalRatePercentageScreen(null, shown.remainingDurationInMinutes)
                    else                                                -> shown
                }
                if (filterDuplicates && (last == screen)) continue
                last = screen
                framesServed++
                return ParsedDisplayFrame(NullDisplayFrame, screen)
            }
        }

        override suspend fun startLongButtonPress(button: RTNavigationButton, keepGoing: (suspend () -> Boolean)?) =
            error("no long press expected")
        override suspend fun stopLongButtonPress() = Unit
        override suspend fun waitForLongButtonPressToFinish() = Unit
        override suspend fun shortPressButton(button: RTNavigationButton) {
            pressed.add(button)
            current = onShortPress(pressed.size, button, current)
        }
    }

    @BeforeTest
    fun enableSlowLink() {
        Logger.threshold = LogLevel.VERBOSE
        RTLinkProfile.slowLink = true
    }

    @AfterTest
    fun restoreStandardPacing() {
        RTLinkProfile.slowLink = false
    }

    private fun tbr(percentage: Int) = ParsedScreen.TemporaryBasalRatePercentageScreen(percentage, remainingDurationInMinutes = 30)
    private fun percentage(screen: ParsedScreen) = (screen as ParsedScreen.TemporaryBasalRatePercentageScreen).percentage
    private fun step(screen: ParsedScreen, button: RTNavigationButton) =
        tbr(percentage(screen)!! + if (button == RTNavigationButton.UP) 10 else -10)

    private fun adjust(context: ScriptedContext, target: Int, timeoutMs: Long = 60000) = runBlockingWithWatchdog(timeoutMs) {
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
    fun everyPressIsConfirmedAndNoneIsRepeatedOnAGuess() {
        val context = ScriptedContext(tbr(60)) { _, button, current -> step(current, button) }
        adjust(context, 100)
        assertEquals(100, percentage(context.current))
        assertEquals(4, context.pressed.size)
    }

    @Test
    fun aSwallowedPressIsRepeatedOnlyAfterTheScreenShowedNoChange() {
        // The second press is swallowed; the run still ends exactly on the target, one press longer.
        val context = ScriptedContext(tbr(70)) { pressNumber, button, current ->
            if (pressNumber == 2) current else step(current, button)
        }
        adjust(context, 100)
        assertEquals(100, percentage(context.current))
        assertEquals(4, context.pressed.size)
    }

    @Test
    fun theRunStopsTheMomentThePumpLeavesTheSettingScreen() {
        // After the first press the pump drops to its main screen, discarding the edit. No further
        // press may go out into that screen.
        val mainScreen = ParsedScreen.MainScreen(
            MainScreenContent.Normal(
                currentTime = kotlinx.datetime.LocalDateTime(2026, 1, 1, 12, 0),
                activeBasalProfileNumber = 1,
                currentBasalRateFactor = 1000,
                batteryState = info.nightscout.comboctl.parser.BatteryState.FULL_BATTERY
            )
        )
        val context = ScriptedContext(tbr(0)) { _, _, _ -> mainScreen }
        assertFailsWith<QuantityNotChangingException> { adjust(context, 100) }
        assertEquals(1, context.pressed.size)
    }

    @Test
    fun aQuantityThatNeverMovesFailsAfterBoundedPresses() {
        val context = ScriptedContext(tbr(110)) { _, _, current -> current }
        assertFailsWith<QuantityNotChangingException> { adjust(context, 100, timeoutMs = 90000) }
        assertEquals(5, context.pressed.size)
    }

    @Test
    fun waitingForAScreenToleratesTheGapsBetweenFrames() {
        // The standard pacing treats a missing frame as "no screen arrived"; on a slow link frames
        // are simply late, and the screen that follows must still be found.
        val context = object : RTNavigationContext {
            private var served = 0
            override val maxNumCycleAttempts = 20
            override fun resetDuplicate() = Unit
            override suspend fun getParsedDisplayFrame(filterDuplicates: Boolean, processAlertScreens: Boolean): ParsedDisplayFrame? {
                delay(20)
                served++
                return if (served < 4) null
                else ParsedDisplayFrame(NullDisplayFrame, ParsedScreen.TemporaryBasalRateDurationScreen(30))
            }
            override suspend fun startLongButtonPress(button: RTNavigationButton, keepGoing: (suspend () -> Boolean)?) = Unit
            override suspend fun stopLongButtonPress() = Unit
            override suspend fun waitForLongButtonPressToFinish() = Unit
            override suspend fun shortPressButton(button: RTNavigationButton) = Unit
        }
        var screen: ParsedScreen? = null
        runBlockingWithWatchdog(20000) {
            screen = waitUntilScreenAppears(context, ParsedScreen.TemporaryBasalRateDurationScreen::class)
        }
        assertIs<ParsedScreen.TemporaryBasalRateDurationScreen>(screen)
    }
}
