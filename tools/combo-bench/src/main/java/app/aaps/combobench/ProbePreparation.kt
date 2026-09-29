package app.aaps.combobench

internal object ProbePreparation {
    data class Discovery(val wasActive: Boolean, val cancellationAccepted: Boolean, val elapsedMs: Long)

    fun stopDiscovery(
        isDiscovering: () -> Boolean,
        cancelDiscovery: () -> Boolean,
        now: () -> Long,
        sleep: (Long) -> Unit
    ): Discovery {
        val started = now()
        val wasActive = isDiscovering()
        val accepted = cancelDiscovery()
        while (isDiscovering()) {
            check(now() - started < 2_000) { "Bluetooth discovery did not stop; socket connect not attempted" }
            sleep(50)
        }
        return Discovery(wasActive, accepted, now() - started)
    }
}
