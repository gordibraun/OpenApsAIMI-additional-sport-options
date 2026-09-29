package app.aaps.combobench

import info.nightscout.comboctl.base.*
import kotlinx.datetime.UtcOffset

/** Only ordinary, durable +1 advances of this controller's own nonce are allowed. */
internal class ControlSessionStore(private val store: PumpStateStore) : PumpStateStore by store {
    var advances = 0
        private set

    @Synchronized override fun setCurrentTxNonce(pumpAddress: BluetoothAddress, currentTxNonce: Nonce) {
        check(advances < 12 && currentTxNonce == store.getCurrentTxNonce(pumpAddress).getIncrementedNonce()) {
            "Nonce jump, rollback or control-session packet budget exceeded"
        }
        // Count before persistence: an uncertain write must never be treated as untouched state.
        advances++
        store.setCurrentTxNonce(pumpAddress, currentTxNonce)
        check(store.getCurrentTxNonce(pumpAddress) == currentTxNonce) { "Nonce persistence not verified" }
    }

    override fun createPumpState(pumpAddress: BluetoothAddress, invariantPumpData: InvariantPumpData,
                                 utcOffset: UtcOffset, tbrState: CurrentTbrState): Unit = error("Pairing forbidden")
    override fun deletePumpState(pumpAddress: BluetoothAddress): Boolean = error("Pairing deletion forbidden")
    override fun setCurrentUtcOffset(pumpAddress: BluetoothAddress, utcOffset: UtcOffset): Unit = error("Time changes forbidden")
    override fun setCurrentTbrState(pumpAddress: BluetoothAddress, currentTbrState: CurrentTbrState): Unit = error("TBR state changes forbidden")
}
