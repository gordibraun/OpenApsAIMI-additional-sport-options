package app.aaps.combobench

import info.nightscout.comboctl.base.*
import kotlinx.datetime.UtcOffset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TherapySessionStoreTest {
    private val address = "00:0E:2F:E7:D1:95".toBluetoothAddress()

    /** Keys and nonce only; the TBR file is never touched by nonce handling. */
    private open class NonceOnlyStore : PumpStateStore {
        var nonce = Nonce.nullNonce()
        var writes = 0
        override fun createPumpState(pumpAddress: BluetoothAddress, invariantPumpData: InvariantPumpData,
                                     utcOffset: UtcOffset, tbrState: CurrentTbrState) = error("unused")
        override fun deletePumpState(pumpAddress: BluetoothAddress) = error("unused")
        override fun hasPumpState(pumpAddress: BluetoothAddress) = true
        override fun getAvailablePumpStateAddresses() = setOf("00:0E:2F:E7:D1:95".toBluetoothAddress())
        override fun getInvariantPumpData(pumpAddress: BluetoothAddress) = error("unused")
        override fun getCurrentTxNonce(pumpAddress: BluetoothAddress) = nonce
        override fun setCurrentTxNonce(pumpAddress: BluetoothAddress, currentTxNonce: Nonce) { writes++; nonce = currentTxNonce }
        override fun getCurrentUtcOffset(pumpAddress: BluetoothAddress) = UtcOffset.ZERO
        override fun setCurrentUtcOffset(pumpAddress: BluetoothAddress, utcOffset: UtcOffset) = Unit
        override fun getCurrentTbrState(pumpAddress: BluetoothAddress) = CurrentTbrState.NoTbrOngoing
        override fun setCurrentTbrState(pumpAddress: BluetoothAddress, currentTbrState: CurrentTbrState) = error("unused")
    }

    private val noFiles = object : JsonFiles {
        override fun exists(name: String) = error("file access not expected")
        override fun read(name: String): JSONObject = error("file access not expected")
        override fun write(name: String, value: JSONObject) = error("file access not expected")
    }

    private fun rejected(block: () -> Unit) = assertTrue(runCatching(block).isFailure)

    @Test fun ordinaryPacketsAdvanceByOneAndAreCounted() {
        val inner = NonceOnlyStore()
        val store = TherapySessionStore(inner, noFiles)
        repeat(3) { store.incrementTxNonce(address) }
        assertEquals(3, store.advances)
        assertEquals(0, store.recoveryJumps)
        assertEquals(3, inner.writes)
        assertEquals(Nonce.nullNonce().getIncrementedNonce(3), inner.nonce)
    }

    @Test fun driverRecoveryJumpIsAllowedAndCountedSeparately() {
        val inner = NonceOnlyStore()
        val store = TherapySessionStore(inner, noFiles)
        store.incrementTxNonce(address, TherapySessionPolicy.NONCE_RECOVERY_STEP)
        assertEquals(1, store.advances)
        assertEquals(1, store.recoveryJumps)
    }

    @Test fun otherJumpsAndRollbacksAreRejectedWithoutPersisting() {
        val inner = NonceOnlyStore()
        val store = TherapySessionStore(inner, noFiles)
        rejected { store.setCurrentTxNonce(address, inner.nonce) }
        rejected { store.incrementTxNonce(address, 2) }
        rejected { store.incrementTxNonce(address, 499) }
        rejected { store.incrementTxNonce(address, 1000) }
        assertEquals(0, inner.writes)
        assertEquals(0, store.advances)
    }

    @Test fun sessionBudgetStopsRunawayTraffic() {
        val inner = NonceOnlyStore()
        val store = TherapySessionStore(inner, noFiles)
        repeat(TherapySessionPolicy.NONCE_BUDGET) { store.incrementTxNonce(address) }
        rejected { store.incrementTxNonce(address) }
        assertEquals(TherapySessionPolicy.NONCE_BUDGET, inner.writes)
    }

    @Test fun unverifiedPersistenceIsReportedAfterCounting() {
        val inner = object : NonceOnlyStore() {
            override fun setCurrentTxNonce(pumpAddress: BluetoothAddress, currentTxNonce: Nonce) { writes++ }
        }
        val store = TherapySessionStore(inner, noFiles)
        rejected { store.incrementTxNonce(address) }
        assertEquals(1, store.advances)
    }

    @Test fun pairingChangesAreForbidden() {
        val store = TherapySessionStore(NonceOnlyStore(), noFiles)
        rejected { store.deletePumpState(address) }
    }
}
