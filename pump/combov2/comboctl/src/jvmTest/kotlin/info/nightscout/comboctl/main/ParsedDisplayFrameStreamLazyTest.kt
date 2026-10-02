package info.nightscout.comboctl.main

import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.base.DriverProfile
import info.nightscout.comboctl.parser.AlertScreenException
import info.nightscout.comboctl.parser.ParsedScreen
import info.nightscout.comboctl.parser.testFrameStandardBolusMenuScreen
import info.nightscout.comboctl.parser.testFrameTemporaryBasalRatePercentage110Screen
import info.nightscout.comboctl.parser.testFrameW6CancelTbrWarningScreen
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DriverProfile.lazyDisplayFrameParsing]: frames are stored as they arrive and parsed when a
 * screen is asked for. What a caller gets must be the same as with eager parsing; what changes
 * is who pays for the parsing and that frames nobody asked for are never parsed at all.
 */
class ParsedDisplayFrameStreamLazyTest {

    @BeforeTest
    fun enableLazyParsing() {
        Logger.threshold = LogLevel.VERBOSE
        DriverProfile.lazyDisplayFrameParsing = true
    }

    @AfterTest
    fun restoreEagerParsing() {
        DriverProfile.lazyDisplayFrameParsing = false
    }

    @Test
    fun aFrameIsRecognisedWhenItIsAskedFor() = runBlocking {
        val stream = ParsedDisplayFrameStream()
        stream.feedDisplayFrame(testFrameStandardBolusMenuScreen)
        val parsedFrame = stream.getParsedDisplayFrame()
        assertNotNull(parsedFrame)
        assertEquals(ParsedScreen.StandardBolusMenuScreen, parsedFrame.parsedScreen)
    }

    @Test
    fun feedingAFrameDoesNotParseIt() = runBlocking {
        // Nothing is published until somebody asks: the receive loop only stored the frame.
        val stream = ParsedDisplayFrameStream()
        stream.feedDisplayFrame(testFrameStandardBolusMenuScreen)
        assertTrue(stream.flow.replayCache.isEmpty())
        assertTrue(stream.hasStoredDisplayFrame())

        stream.getParsedDisplayFrame()
        assertEquals(1, stream.flow.replayCache.size)
    }

    @Test
    fun onlyTheNewestFrameIsKept() = runBlocking {
        // Two frames arrived before anybody looked; the stale one is dropped unparsed.
        val stream = ParsedDisplayFrameStream()
        stream.feedDisplayFrame(testFrameStandardBolusMenuScreen)
        stream.feedDisplayFrame(testFrameTemporaryBasalRatePercentage110Screen)
        val parsedFrame = stream.getParsedDisplayFrame()
        assertNotNull(parsedFrame)
        assertIs<ParsedScreen.TemporaryBasalRatePercentageScreen>(parsedFrame.parsedScreen)
        assertEquals(110, (parsedFrame.parsedScreen as ParsedScreen.TemporaryBasalRatePercentageScreen).percentage)
    }

    @Test
    fun aNullFrameIsHandedThrough() = runBlocking {
        val stream = ParsedDisplayFrameStream()
        stream.feedDisplayFrame(null)
        assertNull(stream.getParsedDisplayFrame())
    }

    @Test
    fun alertScreensAreStillRaisedToTheCaller() {
        val stream = ParsedDisplayFrameStream()
        stream.feedDisplayFrame(testFrameW6CancelTbrWarningScreen)
        assertFailsWith<AlertScreenException> {
            runBlocking { stream.getParsedDisplayFrame(processAlertScreens = true) }
        }
    }

    @Test
    fun resettingDropsAFrameThatWasNotAskedFor() = runBlocking {
        val stream = ParsedDisplayFrameStream()
        stream.feedDisplayFrame(testFrameStandardBolusMenuScreen)
        stream.resetAll()
        assertTrue(!stream.hasStoredDisplayFrame())
    }
}
