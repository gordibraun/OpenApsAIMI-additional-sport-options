package info.nightscout.comboctl.base

import app.aaps.shared.tests.TestBase
import info.nightscout.comboctl.base.testUtils.TestComboIO
import info.nightscout.comboctl.base.testUtils.TestPumpStateStore
import info.nightscout.comboctl.base.testUtils.runBlockingWithWatchdog
import info.nightscout.comboctl.main.PumpManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.UtcOffset
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Offline characterization only: synthetic keys, in-memory state and transport.
 * These tests do not model Bluetooth bonding or prove acceptance by a real pump.
 */
class ControllerStateTransferTest : TestBase() {

    private val pumpAddress = BluetoothAddress(byteArrayListOfInts(1, 2, 3, 4, 5, 6))
    private val syntheticClientKey = Cipher(ByteArray(CIPHER_KEY_SIZE) { it.toByte() })
    private val syntheticPumpKey = Cipher(ByteArray(CIPHER_KEY_SIZE) { (it + 32).toByte() })

    @Test
    fun identicalSnapshotsProduceIdenticalRegularConnectionPackets() = runBlockingWithWatchdog(12000) {
        val firstStore = syntheticStore()
        val secondStore = snapshotOf(firstStore)

        val first = connectionPacket(firstStore)
        val second = connectionPacket(secondStore)

        assertEquals(first.toByteList(), second.toByteList())
        assertTrue(first.verifyAuthentication(syntheticClientKey))
        assertEquals(TransportLayer.Command.REQUEST_REGULAR_CONNECTION, first.command)
        assertEquals(0x10.toByte(), first.address)
    }

    @Test
    fun updatedSnapshotContinuesPersistedNonceOnNewTransport() = runBlockingWithWatchdog(12000) {
        val firstStore = syntheticStore()
        val first = connectionPacket(firstStore)
        val transferredStore = snapshotOf(firstStore)

        val next = connectionPacket(transferredStore)

        assertEquals(first.nonce.getIncrementedNonce(), next.nonce)
        assertEquals(next.nonce, transferredStore.getCurrentTxNonce(pumpAddress))
        assertEquals(first.nonce, firstStore.getCurrentTxNonce(pumpAddress))
        assertNotEquals(first.toByteList(), next.toByteList())
        assertTrue(next.verifyAuthentication(syntheticClientKey))
    }

    @Test
    fun resumingOldControllerAfterTransferReusesNewControllersNonce() = runBlockingWithWatchdog(12000) {
        val oldStore = syntheticStore()
        connectionPacket(oldStore)
        val newStore = snapshotOf(oldStore)

        val newControllerPacket = connectionPacket(newStore)
        val resumedOldControllerPacket = connectionPacket(oldStore)

        // Independent copies are not an ownership lock, even after a fresh transfer.
        assertEquals(newControllerPacket.toByteList(), resumedOldControllerPacket.toByteList())
        assertTrue(newControllerPacket.verifyAuthentication(syntheticClientKey))
        assertTrue(resumedOldControllerPacket.verifyAuthentication(syntheticClientKey))
    }

    @Test
    fun staleSnapshotReplaysAnAuthenticatedPacket() = runBlockingWithWatchdog(12000) {
        val currentStore = syntheticStore()
        val staleStore = snapshotOf(currentStore)
        val alreadySent = connectionPacket(currentStore)
        val latest = connectionPacket(currentStore)

        val replay = connectionPacket(staleStore)

        assertEquals(alreadySent.toByteList(), replay.toByteList())
        assertNotEquals(latest.nonce, replay.nonce)
        // Authentication alone does not check the receiving pump's freshness state.
        assertTrue(replay.verifyAuthentication(syntheticClientKey))
    }

    @Test
    fun wrongKeyDoesNotAuthenticateRegularConnectionPacket() = runBlockingWithWatchdog(12000) {
        val packet = connectionPacket(syntheticStore())

        assertTrue(packet.verifyAuthentication(syntheticClientKey))
        assertFalse(packet.verifyAuthentication(syntheticPumpKey))
    }

    @Test
    fun changingNonceWithoutReauthenticatingInvalidatesPacket() = runBlockingWithWatchdog(12000) {
        val packet = connectionPacket(syntheticStore())
        val modifiedBytes = packet.toByteList().toMutableList()
        // The nonce follows version, command, two length bytes and address.
        modifiedBytes[5] = (modifiedBytes[5].toInt() xor 1).toByte()
        val modifiedPacket = modifiedBytes.toTransportLayerPacket()

        assertNotEquals(packet.nonce, modifiedPacket.nonce)
        assertFalse(modifiedPacket.verifyAuthentication(syntheticClientKey))
    }

    @Test
    fun managerDiscardsImportedStateWithoutAndroidBond() {
        val store = syntheticStore()
        val bluetooth = mock(BluetoothInterface::class.java)
        `when`(bluetooth.getPairedDeviceAddresses()).thenReturn(emptySet())

        PumpManager(bluetooth, store).setup { }

        assertFalse(store.hasPumpState(pumpAddress))
    }

    @Test
    fun managerPreservesStateWhenBluetoothReportsPumpAsPaired() {
        val store = syntheticStore()
        val nonceBefore = store.getCurrentTxNonce(pumpAddress)
        val bluetooth = mock(BluetoothInterface::class.java)
        `when`(bluetooth.getPairedDeviceAddresses()).thenReturn(setOf(pumpAddress))

        PumpManager(bluetooth, store).setup { }

        assertTrue(store.hasPumpState(pumpAddress))
        assertEquals(nonceBefore, store.getCurrentTxNonce(pumpAddress))
    }

    private fun syntheticStore() = TestPumpStateStore().apply {
        createPumpState(
            pumpAddress,
            InvariantPumpData(syntheticClientKey, syntheticPumpKey, 0x10, "SYNTHETIC"),
            UtcOffset.ZERO,
            CurrentTbrState.NoTbrOngoing
        )
        setCurrentTxNonce(pumpAddress, Nonce.nullNonce().getIncrementedNonce(1000))
    }

    private fun snapshotOf(source: TestPumpStateStore) = TestPumpStateStore().apply {
        val original = source.getInvariantPumpData(pumpAddress)
        createPumpState(
            pumpAddress,
            original.copy(
                clientPumpCipher = original.clientPumpCipher.toString().toCipher(),
                pumpClientCipher = original.pumpClientCipher.toString().toCipher()
            ),
            source.getCurrentUtcOffset(pumpAddress),
            source.getCurrentTbrState(pumpAddress)
        )
        setCurrentTxNonce(pumpAddress, source.getCurrentTxNonce(pumpAddress).toString().toNonce())
    }

    private suspend fun CoroutineScope.connectionPacket(store: TestPumpStateStore): TransportLayer.Packet {
        val transport = TestComboIO()
        val io = TransportLayer.IO(store, pumpAddress, transport) {
            if (it.cause !is CancellationException) throw it
        }
        io.start(this) { TransportLayer.IO.ReceiverBehavior.FORWARD_PACKET }
        try {
            io.send(TransportLayer.createRequestRegularConnectionPacketInfo())
            return transport.sentPacketData.single().toTransportLayerPacket()
        } finally {
            io.stop()
        }
    }
}
