package app.aaps.combobench

import info.nightscout.comboctl.base.ApplicationLayer
import info.nightscout.comboctl.base.ComboFrameParser
import info.nightscout.comboctl.base.TransportLayer
import info.nightscout.comboctl.base.toComboFrame
import info.nightscout.comboctl.base.toTransportLayerPacket

/** Every outgoing frame must be a pairing/control packet, never a dosing or RT command. */
internal object PairingPackets {
    private val transportCommands = setOf(
        TransportLayer.Command.REQUEST_PAIRING_CONNECTION, TransportLayer.Command.REQUEST_KEYS,
        TransportLayer.Command.GET_AVAILABLE_KEYS, TransportLayer.Command.REQUEST_ID,
        TransportLayer.Command.REQUEST_REGULAR_CONNECTION, TransportLayer.Command.ACK_RESPONSE
    )
    private val applicationCommands = setOf(
        ApplicationLayer.Command.CTRL_CONNECT, ApplicationLayer.Command.CTRL_GET_SERVICE_VERSION,
        ApplicationLayer.Command.CTRL_BIND, ApplicationLayer.Command.CTRL_DISCONNECT
    )

    fun inspect(frame: List<Byte>): String {
        val parser = ComboFrameParser()
        parser.pushData(frame)
        val bytes = checkNotNull(parser.parseFrame()) { "Incomplete pairing frame" }
        check(parser.parseFrame() == null && bytes.toComboFrame() == frame) { "Non-canonical pairing frame" }
        val packet = bytes.toTransportLayerPacket()
        return if (packet.command == TransportLayer.Command.DATA) {
            val command = ApplicationLayer.Packet(packet).command
            check(command in applicationCommands) { "Non-pairing application command blocked" }
            command.name
        } else {
            check(packet.command in transportCommands) { "Non-pairing transport command blocked" }
            packet.command.name
        }
    }
}
