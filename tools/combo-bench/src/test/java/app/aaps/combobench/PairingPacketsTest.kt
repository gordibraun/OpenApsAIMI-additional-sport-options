package app.aaps.combobench

import info.nightscout.comboctl.base.*
import org.junit.Assert.*
import org.junit.Test

class PairingPacketsTest {
    private fun frame(command: ApplicationLayer.Command): List<Byte> {
        val info = ApplicationLayer.Packet(command).toTransportLayerPacketInfo()
        return TransportLayer.Packet(TransportLayer.Command.DATA, payload = info.payload).toByteList().toComboFrame()
    }

    @Test fun onlyFourApplicationControlCommandsAreAllowed() {
        val permitted = setOf(ApplicationLayer.Command.CTRL_CONNECT, ApplicationLayer.Command.CTRL_GET_SERVICE_VERSION,
            ApplicationLayer.Command.CTRL_BIND, ApplicationLayer.Command.CTRL_DISCONNECT)
        for (command in ApplicationLayer.Command.entries) {
            val result = runCatching { PairingPackets.inspect(frame(command)) }
            assertEquals(command.name, command in permitted, result.isSuccess)
            if (result.isSuccess) assertEquals(command.name, result.getOrThrow())
        }
    }

    @Test fun onlyPairingAndAcknowledgementTransportCommandsAreAllowed() {
        val permitted = setOf(TransportLayer.Command.REQUEST_PAIRING_CONNECTION, TransportLayer.Command.REQUEST_KEYS,
            TransportLayer.Command.GET_AVAILABLE_KEYS, TransportLayer.Command.REQUEST_ID,
            TransportLayer.Command.REQUEST_REGULAR_CONNECTION, TransportLayer.Command.ACK_RESPONSE)
        for (command in TransportLayer.Command.entries.filter { it != TransportLayer.Command.DATA }) {
            val result = runCatching { PairingPackets.inspect(TransportLayer.Packet(command).toByteList().toComboFrame()) }
            assertEquals(command.name, command in permitted, result.isSuccess)
        }
    }

    @Test fun multipleFramesCannotHideAnExtraCommand() {
        val allowed = frame(ApplicationLayer.Command.CTRL_CONNECT)
        assertTrue(runCatching { PairingPackets.inspect(allowed + allowed) }.isFailure)
    }

    @Test fun incompleteFramesAndTrailingDataAreRejected() {
        val allowed = frame(ApplicationLayer.Command.CTRL_CONNECT)
        assertTrue(runCatching { PairingPackets.inspect(allowed.dropLast(1)) }.isFailure)
        assertTrue(runCatching { PairingPackets.inspect(allowed + listOf(0.toByte())) }.isFailure)
        assertTrue(runCatching { PairingPackets.inspect(emptyList()) }.isFailure)
    }

    @Test fun packetDescriptionsNeverContainPayloads() {
        val packet = TransportLayer.Packet(TransportLayer.Command.REQUEST_ID,
            payload = ArrayList("secret material".toByteArray().toList()))
        assertEquals("REQUEST_ID", PairingPackets.inspect(packet.toByteList().toComboFrame()))
    }
}
