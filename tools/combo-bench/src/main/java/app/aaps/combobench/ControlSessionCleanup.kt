package app.aaps.combobench

internal object ControlSessionCleanup {
    fun settled(socketClosed: Boolean, guardOk: Boolean, protocolComplete: Boolean, nonceAdvances: Int,
                linkCleanupKnown: Boolean, cleanupFailed: Boolean): Boolean =
        socketClosed && guardOk && !cleanupFailed && linkCleanupKnown &&
            (protocolComplete || nonceAdvances == 0)
}
