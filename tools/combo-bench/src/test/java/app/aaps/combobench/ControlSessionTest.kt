package app.aaps.combobench

import info.nightscout.comboctl.base.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.UtcOffset
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class ControlSessionTest {
    private fun frame(info: TransportLayer.OutgoingPacketInfo) = TransportLayer.Packet(
        info.command, reliabilityBit = info.reliable, payload = info.payload).toByteList().toComboFrame()
    private val regular get() = frame(TransportLayer.createRequestRegularConnectionPacketInfo())
    private val connect get() = frame(ApplicationLayer.createCTRLConnectPacket().toTransportLayerPacketInfo())
    private val disconnect get() = frame(ApplicationLayer.createCTRLDisconnectPacket().toTransportLayerPacketInfo())
    private fun ready() = ControlSessionPackets().apply { inspect(regular); accepted() }
    private fun rejected(block: () -> Unit) = assertTrue(runCatching(block).isFailure)

    @Test fun exactSingleHandshakeIsAllowed() {
        val gate = ready()
        assertEquals("CTRL_CONNECT", gate.inspect(connect))
        assertEquals("ACK_RESPONSE", gate.inspect(frame(TransportLayer.createAckResponsePacketInfo(false))))
        assertEquals("CTRL_DISCONNECT", gate.inspect(disconnect))
        assertTrue(gate.disconnectSent)
    }

    @Test fun allOtherApplicationCommandsAreRejected() {
        ApplicationLayer.Command.entries.filter { it !in setOf(ApplicationLayer.Command.CTRL_CONNECT,
            ApplicationLayer.Command.CTRL_DISCONNECT) }.forEach { command ->
            val gate = ready().apply { inspect(connect) }
            rejected { gate.inspect(frame(ApplicationLayer.Packet(command).toTransportLayerPacketInfo())) }
        }
    }

    @Test fun pairingAndAllOtherTransportCommandsAreRejected() {
        TransportLayer.Command.entries.filter { it !in setOf(TransportLayer.Command.DATA,
            TransportLayer.Command.REQUEST_REGULAR_CONNECTION, TransportLayer.Command.ACK_RESPONSE) }.forEach {
            rejected { ready().inspect(TransportLayer.Packet(it).toByteList().toComboFrame()) }
        }
    }

    @Test fun orderDuplicatesAndLatePacketsAreRejected() {
        rejected { ControlSessionPackets().inspect(connect) }
        rejected { ControlSessionPackets().accepted() }
        val gate = ready()
        rejected { gate.inspect(regular) }
        rejected { gate.accepted() }
        rejected { gate.inspect(disconnect) }
        gate.inspect(connect)
        rejected { gate.inspect(connect) }
        gate.inspect(disconnect)
        rejected { gate.inspect(disconnect) }
        rejected { gate.inspect(frame(TransportLayer.createAckResponsePacketInfo(false))) }
    }

    @Test fun ackFloodAndAckBeforeRequestAreRejected() {
        val ack = frame(TransportLayer.createAckResponsePacketInfo(false))
        rejected { ControlSessionPackets().inspect(ack) }
        val gate = ready()
        repeat(8) { gate.inspect(ack) }
        rejected { gate.inspect(ack) }
    }

    @Test fun alteredPayloadUnreliableAndHiddenFramesAreRejected() {
        val info = ApplicationLayer.createCTRLConnectPacket().toTransportLayerPacketInfo()
        rejected { ready().inspect(TransportLayer.Packet(TransportLayer.Command.DATA, payload = info.payload).toByteList().toComboFrame()) }
        rejected { ready().inspect(frame(info.copy(payload = ArrayList(info.payload + 1.toByte())))) }
        listOf(connect + connect, connect + 0.toByte(), connect.dropLast(1), emptyList()).forEach { bad ->
            rejected { ready().inspect(bad) }
        }
    }

    private fun response(error: Int = 0, reliable: Boolean = true,
                         command: ApplicationLayer.Command = ApplicationLayer.Command.CTRL_CONNECT_RESPONSE): TransportLayer.Packet {
        val info = ApplicationLayer.Packet(command, payload = arrayListOf(error.toByte(), 0.toByte())).toTransportLayerPacketInfo()
        return TransportLayer.Packet(TransportLayer.Command.DATA, reliabilityBit = reliable, payload = info.payload)
    }

    private class Exchange(private val response: TransportLayer.Packet?, private val failAt: Int = 0) : ControlSessionExchange {
        val sent = mutableListOf<TransportLayer.OutgoingPacketInfo>()
        var receives = 0
        override suspend fun send(packet: TransportLayer.OutgoingPacketInfo) { sent.add(packet) }
        override suspend fun receive(command: TransportLayer.Command): TransportLayer.Packet {
            receives++
            check(receives != failAt)
            return if (receives == 1) TransportLayer.Packet(TransportLayer.Command.REGULAR_CONNECTION_REQUEST_ACCEPTED)
            else checkNotNull(response)
        }
    }

    @Test fun flowOnlyOpensControlAndNeverActivatesServices() = runBlocking {
        val exchange = Exchange(response())
        val phases = mutableListOf<String>()
        var accepted = false
        ControlSessionFlow.open(exchange, { accepted = true }, phases::add)
        assertTrue(accepted)
        assertEquals(2, exchange.sent.size)
        assertEquals(TransportLayer.Command.REQUEST_REGULAR_CONNECTION, exchange.sent[0].command)
        assertEquals(ApplicationLayer.createCTRLConnectPacket().toTransportLayerPacketInfo(), exchange.sent[1])
        assertEquals("CTRL_CONNECTED", phases.last())
    }

    @Test fun flowDoesNotRetryOnEitherReceiveFailure() = runBlocking {
        for (failure in 1..2) {
            val exchange = Exchange(response(), failure)
            assertTrue(runCatching { ControlSessionFlow.open(exchange, {}, {}) }.isFailure)
            assertEquals(failure, exchange.sent.size)
            assertEquals(failure, exchange.receives)
        }
    }

    @Test fun flowRejectsErrorUnreliableAndWrongResponse() = runBlocking {
        listOf(response(error = 1), response(reliable = false),
            response(command = ApplicationLayer.Command.CTRL_DISCONNECT)).forEach { bad ->
            assertTrue(runCatching { ControlSessionFlow.open(Exchange(bad), {}, {}) }.isFailure)
        }
    }

    private val address = "00:0E:2F:25:24:BC".toBluetoothAddress()
    private var nonce = Nonce.nullNonce()
    private var writes = 0
    private fun store(): ControlSessionStore {
        val delegate = Proxy.newProxyInstance(PumpStateStore::class.java.classLoader,
            arrayOf(PumpStateStore::class.java)) { _, method, args ->
            when (method.name) {
                "getCurrentTxNonce" -> nonce
                "setCurrentTxNonce" -> { nonce = args[1] as Nonce; writes++; null }
                else -> error("Forbidden store operation reached delegate")
            }
        } as PumpStateStore
        return ControlSessionStore(delegate)
    }

    @Test fun storeAllowsOnlyDurablePlusOneAndBoundedPacketCount() {
        val store = store()
        rejected { store.setCurrentTxNonce(address, nonce) }
        rejected { store.setCurrentTxNonce(address, nonce.getIncrementedNonce(2)) }
        assertEquals(0, writes)
        repeat(12) { store.setCurrentTxNonce(address, nonce.getIncrementedNonce()) }
        assertEquals(12, store.advances)
        rejected { store.setCurrentTxNonce(address, nonce.getIncrementedNonce()) }
        rejected { store.setCurrentTxNonce(address, Nonce.nullNonce()) }
        assertEquals(12, writes)
    }

    @Test fun storeForbidsPairingTimeAndTbrMutations() {
        val store = store()
        rejected { store.createPumpState(address, InvariantPumpData.nullData(), UtcOffset.ZERO, CurrentTbrState.NoTbrOngoing) }
        rejected { store.deletePumpState(address) }
        rejected { store.setCurrentUtcOffset(address, UtcOffset.ZERO) }
        rejected { store.setCurrentTbrState(address, CurrentTbrState.NoTbrOngoing) }
        assertEquals(0, writes)
    }
}
