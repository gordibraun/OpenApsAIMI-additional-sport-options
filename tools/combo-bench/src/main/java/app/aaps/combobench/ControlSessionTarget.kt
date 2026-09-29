package app.aaps.combobench

internal object ControlSessionTarget {
    fun validate(flavor: String, pump: String, address: String) {
        val legacy = flavor == "watch" && pump == "PUMP_41056642" &&
            PairingTarget.matches("00:0E:2F:25:24:BC", address)
        val manual = flavor == "manual" && pump == ManualReconnectPolicy.PUMP &&
            PairingTarget.matches(ManualReconnectPolicy.ADDRESS, address)
        check(legacy || manual) { "Control diagnostic target is not allowed in this package" }
    }
}
