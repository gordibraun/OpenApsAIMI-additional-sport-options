package app.aaps.combobench

/** Only a completed zero-byte socket may have its missed disconnect checked later. */
internal object ManualReconnectCleanup {
    fun canRecheck(stage: String, active: Boolean, socketClosed: Boolean,
                   receiverUnregistered: Boolean, bonded: Boolean, bondChanged: Boolean): Boolean =
        stage in setOf("SOCKET_OPENED_ZERO_BYTES", "TIMEOUT") && !active && socketClosed &&
            receiverUnregistered && bonded && !bondChanged

    fun disconnected(samples: List<Boolean?>): Boolean = samples.size == 3 && samples.all { it == false }
}
