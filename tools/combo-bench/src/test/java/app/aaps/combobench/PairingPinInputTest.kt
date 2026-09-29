package app.aaps.combobench

import org.junit.Assert.*
import org.junit.Test

class PairingPinInputTest {
    @Test fun acceptsTenDigitsIncludingLeadingZeroes() {
        assertTrue(PairingPinInput.valid("0123456789"))
        assertTrue(PairingPinInput.valid("0000000000"))
    }

    @Test fun rejectsWrongLengthWhitespaceLettersAndUnicodeDigits() {
        listOf("", "123456789", "12345678901", "01234 6789", "012345678a", "１２３４５６７８９０").forEach {
            assertFalse(PairingPinInput.valid(it))
        }
    }
}
