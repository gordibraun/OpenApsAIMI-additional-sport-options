package app.aaps.combobench

import java.util.concurrent.atomic.AtomicReference

/** Latches observed re-pairing; closing a socket cannot undo Android's bond changes. */
internal class ProbeBondGuard(private val initiallyBonded: Boolean) {
    private val stopped = AtomicReference<String?>(null)
    val stopReason: String? get() = stopped.get()

    fun requireExistingBond() = check(initiallyBonded) { "Authenticated probe requires an existing bond" }

    fun observe(bonded: Boolean, pairingRequested: Boolean = false, bondChanged: Boolean = false) {
        val reason = when {
            pairingRequested -> "PAIRING_REQUESTED"
            bondChanged || (initiallyBonded && !bonded) -> "BOND_CHANGED"
            else -> null
        }
        if (reason != null) stopped.compareAndSet(null, reason)
    }
}
