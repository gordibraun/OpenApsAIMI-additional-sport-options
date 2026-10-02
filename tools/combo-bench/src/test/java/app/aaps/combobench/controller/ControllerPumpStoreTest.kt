package app.aaps.combobench.controller

import app.aaps.combobench.JsonFiles
import info.nightscout.comboctl.base.BluetoothAddress
import info.nightscout.comboctl.base.CurrentTbrState
import info.nightscout.comboctl.base.InvariantPumpData
import info.nightscout.comboctl.base.Nonce
import info.nightscout.comboctl.base.PumpStateStore
import info.nightscout.comboctl.base.incrementTxNonce
import info.nightscout.comboctl.base.toBluetoothAddress
import kotlinx.datetime.UtcOffset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The nonce is written to disk in blocks, ahead of use. What must hold: a nonce is never handed
 * out twice, whatever the moment at which the process dies and a new session starts from disk.
 */
class ControllerPumpStoreTest {

    private val address = "00:0E:2F:E7:D1:95".toBluetoothAddress()

    /** Stands in for the encrypted pairing store: remembers the nonce and counts the slow writes. */
    private class DiskStore : PumpStateStore {
        var nonce = Nonce.nullNonce()
        var writes = 0
        override fun createPumpState(pumpAddress: BluetoothAddress, invariantPumpData: InvariantPumpData,
                                     utcOffset: UtcOffset, tbrState: CurrentTbrState) = error("unused")
        override fun deletePumpState(pumpAddress: BluetoothAddress) = error("unused")
        override fun hasPumpState(pumpAddress: BluetoothAddress) = true
        override fun getAvailablePumpStateAddresses() = emptySet<BluetoothAddress>()
        override fun getInvariantPumpData(pumpAddress: BluetoothAddress) = error("unused")
        override fun getCurrentTxNonce(pumpAddress: BluetoothAddress) = nonce
        override fun setCurrentTxNonce(pumpAddress: BluetoothAddress, currentTxNonce: Nonce) { writes++; nonce = currentTxNonce }
        override fun getCurrentUtcOffset(pumpAddress: BluetoothAddress) = UtcOffset.ZERO
        override fun setCurrentUtcOffset(pumpAddress: BluetoothAddress, utcOffset: UtcOffset) = Unit
        override fun getCurrentTbrState(pumpAddress: BluetoothAddress) = CurrentTbrState.NoTbrOngoing
        override fun setCurrentTbrState(pumpAddress: BluetoothAddress, currentTbrState: CurrentTbrState) = Unit
    }

    private object NoFiles : JsonFiles {
        override fun exists(name: String) = false
        override fun read(name: String): JSONObject = error("unused")
        override fun write(name: String, value: JSONObject) = Unit
    }

    @Test fun aSessionOfPacketsCostsAHandfulOfSlowWritesNotOnePerPacket() {
        val disk = DiskStore()
        val store = ControllerPumpStore(disk, NoFiles)
        repeat(300) { store.incrementTxNonce(address) }
        assertEquals(300, store.advances)
        assertTrue("expected a couple of writes, got ${disk.writes}", disk.writes in 1..3)
        assertEquals(disk.writes, store.markWrites)
    }

    @Test fun everyNonceHandedOutIsNewAndIncreasing() {
        val store = ControllerPumpStore(DiskStore(), NoFiles)
        val seen = HashSet<Nonce>()
        repeat(700) { assertTrue(seen.add(store.incrementTxNonce(address))) }
    }

    @Test fun aSessionThatDiesAtAnyPointNeverLeadsToANonceBeingReused() {
        // Whatever number of packets the first session sent before dying, the next one, which
        // starts from what is on disk, must only hand out nonces the first one never used.
        for (packetsBeforeCrash in listOf(1, 2, 100, 255, 256, 257, 300, 511, 512, 513, 1000)) {
            val disk = DiskStore()
            val first = ControllerPumpStore(disk, NoFiles)
            val used = HashSet<Nonce>()
            repeat(packetsBeforeCrash) { used.add(first.incrementTxNonce(address)) }

            val second = ControllerPumpStore(disk, NoFiles)
            repeat(50) {
                val nonce = second.incrementTxNonce(address)
                assertTrue("nonce reused after a crash at $packetsBeforeCrash packets", nonce !in used)
            }
        }
    }

    @Test fun theDriversOwnRecoveryJumpIsPersistedBeforeItIsUsed() {
        // The driver jumps the nonce ahead by 500 when it recovers a connection. That lands
        // beyond the reserved block, so the mark on disk has to move before the jump is used.
        val disk = DiskStore()
        val first = ControllerPumpStore(disk, NoFiles)
        first.incrementTxNonce(address)
        val jumped = first.incrementTxNonce(address, 500)

        val second = ControllerPumpStore(disk, NoFiles)
        val next = second.incrementTxNonce(address)
        assertNotEquals(jumped, next)
        // And the restarted session is past the jump, not behind it.
        val third = ControllerPumpStore(DiskStore().also { it.nonce = jumped }, NoFiles)
        assertEquals(third.incrementTxNonce(address), jumped.getIncrementedNonce(1))
        assertTrue(disk.writes >= 2)
    }

    @Test(expected = IllegalStateException::class) fun aRollbackIsRejected() {
        val store = ControllerPumpStore(DiskStore().also { it.nonce = Nonce.nullNonce().getIncrementedNonce(10) }, NoFiles)
        store.setCurrentTxNonce(address, Nonce.nullNonce().getIncrementedNonce(5))
    }
}
