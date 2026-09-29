package app.aaps.combobench

internal object PairingPinInput {
    fun valid(value: String) = value.length == 10 && value.all { it in '0'..'9' }
}
