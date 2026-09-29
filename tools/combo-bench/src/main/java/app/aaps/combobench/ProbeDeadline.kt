package app.aaps.combobench

import java.util.concurrent.atomic.AtomicReference

// A cancelled connection timer must not close a socket that has entered the hold phase.
internal class ProbeDeadline {
    enum class Phase { CONNECTING, HOLDING, DONE, CONNECT_TIMEOUT, HOLD_TIMEOUT }

    private val state = AtomicReference(Phase.CONNECTING)
    val phase: Phase get() = state.get()
    val expired: Boolean get() = phase == Phase.CONNECT_TIMEOUT || phase == Phase.HOLD_TIMEOUT
    fun opened(): Boolean = state.compareAndSet(Phase.CONNECTING, Phase.HOLDING)
    fun expireConnection(): Boolean = state.compareAndSet(Phase.CONNECTING, Phase.CONNECT_TIMEOUT)
    fun expireHold(): Boolean = state.compareAndSet(Phase.HOLDING, Phase.HOLD_TIMEOUT)
    fun finish(): Boolean = state.compareAndSet(Phase.HOLDING, Phase.DONE)
}
