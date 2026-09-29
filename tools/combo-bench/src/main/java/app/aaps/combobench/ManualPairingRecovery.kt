package app.aaps.combobench

internal object ManualPairingRecovery {
    private val preparationEvents = setOf("STARTING", "WAITING_VISIBILITY", "VISIBILITY_RESULT",
        "BLUETOOTH_EVENT", "DISCOVERABLE", "WAITING_DISCOVERABLE")

    fun canRetry(stage: String, hasAddress: Boolean, hasStoredPairing: Boolean, events: List<String>): Boolean =
        stage == "INTERRUPTED" && !hasAddress && !hasStoredPairing && "STARTING" in events &&
            events.all { it in preparationEvents }
}
