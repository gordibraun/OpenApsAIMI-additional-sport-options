package app.aaps.combobench

internal object PairingTarget {
    private val addressPattern = Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
    fun matches(expected: String, actual: String): Boolean =
        addressPattern.matches(expected) && addressPattern.matches(actual) && expected.equals(actual, ignoreCase = true)
}

/**
 * The pump this watch is to be paired with, as its owner entered it.
 *
 * Any Combo can be paired: the controller on the watch drives whichever pump its owner pairs it
 * with, on commands from the phone. What stays limited to the off-body test pump is the bench's
 * own experiments (connection probes, the manual stop / resume buttons) - see [isTestPump] and
 * [ManualReconnectPolicy] - because those act on the pump without the phone having asked.
 */
internal class ManualPumpTarget(serial: String, address: String = "") {
    val serial = serial.trim()
    val pump = "PUMP_${this.serial}"
    val address = address.trim().uppercase().ifEmpty { null }

    /** True for the off-body bench pump, the only one the bench's experiments may touch. */
    val isTestPump: Boolean get() = serial == TEST_SERIAL

    init {
        require(SERIAL_PATTERN.matches(this.serial)) { "Номер помпы — восемь цифр с наклейки на корпусе" }
        require(this.address == null || PairingTarget.matches(this.address, this.address)) { "Адрес должен иметь вид 00:0E:2F:12:34:56" }
        require(this.address == null || isComboAddress(this.address)) { "Адрес не похож на адрес помпы Combo" }
    }

    /**
     * Whether a device found during discovery may be the pump. Without a given address any Combo
     * in pairing mode qualifies; that it is the right one is established afterwards, when the
     * pump states its own id during the handshake and it has to equal [pump].
     */
    fun accepts(candidateAddress: String, name: String?): Boolean =
        isComboAddress(candidateAddress) &&
            (if (address != null) PairingTarget.matches(address, candidateAddress)
            else name == "SpiritCombo" || name == pump)

    /** True for a Bluetooth address in the range Roche gave the Combo. */
    fun isCombo(candidateAddress: String): Boolean = isComboAddress(candidateAddress)

    private fun isComboAddress(value: String): Boolean =
        PairingTarget.matches(value, value) && value.startsWith("00:0E:2F:", ignoreCase = true)

    companion object {
        const val TEST_SERIAL = "10392647"
        private val SERIAL_PATTERN = Regex("[0-9]{8}")
    }
}
