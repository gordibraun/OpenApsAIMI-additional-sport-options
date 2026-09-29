package app.aaps.combobench

internal object PairingTarget {
    private val addressPattern = Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
    fun matches(expected: String, actual: String): Boolean =
        addressPattern.matches(expected) && addressPattern.matches(actual) && expected.equals(actual, ignoreCase = true)
}

/** Discovery is limited to the second off-body bench, never the previous pump. */
internal class ManualPumpTarget(serial: String, address: String = "") {
    val serial = serial.trim()
    val pump = "PUMP_${this.serial}"
    val address = address.trim().uppercase().ifEmpty { null }

    init {
        require(this.serial == "10392647") { "Этот стенд разрешён только для тестовой Combo 10392647" }
        require(this.address == null || PairingTarget.matches(this.address, this.address)) { "Адрес должен иметь вид 00:0E:2F:12:34:56" }
        require(this.address == null || isSecondPumpAddress(this.address)) { "Адрес не относится к новой тестовой Combo" }
    }

    fun accepts(candidateAddress: String, name: String?): Boolean =
        isSecondPumpAddress(candidateAddress) &&
            (if (address != null) PairingTarget.matches(address, candidateAddress)
            else name == "SpiritCombo" || name == pump)

    private fun isSecondPumpAddress(value: String): Boolean =
        PairingTarget.matches(value, value) && value.startsWith("00:0E:2F:", ignoreCase = true) &&
            !PairingTarget.matches("00:0E:2F:25:24:BC", value)
}
