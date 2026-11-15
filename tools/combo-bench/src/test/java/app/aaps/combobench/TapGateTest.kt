package app.aaps.combobench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Three quick taps on a complication read the pump; a tap nothing follows opens what it always opened. */
class TapGateTest {

    @Before fun settle() {
        // The gate is one object for the whole app; leave nothing behind from another test.
        repeat(TapGate.TAPS) { TapGate.tap(Long.MAX_VALUE / 2) }
    }

    private fun earlier(tap: TapGate.Tap) = (tap as TapGate.Tap.Earlier).id

    @Test fun threeTapsWithinASecondAreTheTripleTap() {
        val first = earlier(TapGate.tap(10_000))
        val second = earlier(TapGate.tap(10_300))
        assertEquals(TapGate.Tap.Third, TapGate.tap(10_600))
        // Neither of the first two stands as a plain tap afterwards.
        assertFalse(TapGate.settleAsPlain(first))
        assertFalse(TapGate.settleAsPlain(second))
    }

    @Test fun aLoneTapIsAPlainTapOnce() {
        val only = earlier(TapGate.tap(20_000))
        assertTrue(TapGate.settleAsPlain(only))
        assertFalse(TapGate.settleAsPlain(only))
    }

    @Test fun ofTwoTapsOnlyTheSecondStandsAsPlain() {
        val first = earlier(TapGate.tap(30_000))
        val second = earlier(TapGate.tap(30_400))
        assertFalse(TapGate.settleAsPlain(first))
        assertTrue(TapGate.settleAsPlain(second))
    }

    @Test fun threeSlowTapsAreNotATripleTap() {
        earlier(TapGate.tap(40_000))
        earlier(TapGate.tap(40_700))
        // The first is more than a second old by now.
        assertTrue(TapGate.tap(41_400) is TapGate.Tap.Earlier)
    }

    @Test fun afterATripleTapTheCountStartsAfresh() {
        TapGate.tap(50_000); TapGate.tap(50_200)
        assertEquals(TapGate.Tap.Third, TapGate.tap(50_400))
        assertTrue(TapGate.tap(50_600) is TapGate.Tap.Earlier)
    }
}
