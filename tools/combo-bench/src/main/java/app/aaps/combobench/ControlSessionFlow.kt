package app.aaps.combobench

import info.nightscout.comboctl.base.ApplicationLayer
import info.nightscout.comboctl.base.TransportLayer

internal interface ControlSessionExchange {
    suspend fun send(packet: TransportLayer.OutgoingPacketInfo)
    suspend fun receive(command: TransportLayer.Command): TransportLayer.Packet
}

internal object ControlSessionFlow {
    // Deliberately not PumpIO.connect(): that also activates services and may retry with nonce jumps.
    suspend fun open(exchange: ControlSessionExchange, regularAccepted: () -> Unit, phase: (String) -> Unit) {
        phase("REGULAR_CONNECTION")
        exchange.send(TransportLayer.createRequestRegularConnectionPacketInfo())
        val regular = exchange.receive(TransportLayer.Command.REGULAR_CONNECTION_REQUEST_ACCEPTED)
        check(regular.command == TransportLayer.Command.REGULAR_CONNECTION_REQUEST_ACCEPTED)
        regularAccepted()
        phase("CTRL_CONNECT")
        exchange.send(ApplicationLayer.createCTRLConnectPacket().toTransportLayerPacketInfo())
        val response = exchange.receive(TransportLayer.Command.DATA)
        check(response.command == TransportLayer.Command.DATA && response.reliabilityBit)
        val parsed = ApplicationLayer.checkAndParseTransportLayerDataPacket(response)
        check(parsed.command == ApplicationLayer.Command.CTRL_CONNECT_RESPONSE)
        phase("CTRL_CONNECTED")
    }
}
