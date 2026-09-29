package app.aaps.combobench

import info.nightscout.comboctl.base.ApplicationLayer
import info.nightscout.comboctl.base.ComboFrameParser
import info.nightscout.comboctl.base.TransportLayer
import info.nightscout.comboctl.base.toComboFrame
import info.nightscout.comboctl.base.toTransportLayerPacket

/** A single control handshake, with no service activation, pairing or therapy packets. */
internal class ControlSessionPackets {
    var regularSent = false
        private set
    var regularAccepted = false
        private set
    var connectSent = false
        private set
    var disconnectSent = false
        private set
    var acknowledgements = 0
        private set

    @Synchronized fun accepted() {
        check(regularSent && !regularAccepted && !connectSent)
        regularAccepted = true
    }

    @Synchronized fun inspect(frame: List<Byte>): String {
        check(frame.size in 1..512) { "Unexpected frame length" }
        val parser = ComboFrameParser()
        parser.pushData(frame)
        val bytes = checkNotNull(parser.parseFrame()) { "Incomplete control frame" }
        check(parser.parseFrame() == null && bytes.toComboFrame() == frame) { "Non-canonical control frame" }
        val packet = bytes.toTransportLayerPacket()
        check(packet.toByteList() == bytes && !disconnectSent) { "Invalid or late control frame" }
        return when (packet.command) {
            TransportLayer.Command.REQUEST_REGULAR_CONNECTION -> {
                check(!regularSent && packet.payload.isEmpty() && !packet.reliabilityBit)
                regularSent = true
                packet.command.name
            }
            TransportLayer.Command.ACK_RESPONSE -> {
                check(regularSent && packet.payload.isEmpty() && !packet.reliabilityBit && acknowledgements < 8)
                acknowledgements++
                packet.command.name
            }
            TransportLayer.Command.DATA -> {
                check(regularAccepted && packet.reliabilityBit)
                val connect = ApplicationLayer.createCTRLConnectPacket().toTransportLayerPacketInfo().payload
                val disconnect = ApplicationLayer.createCTRLDisconnectPacket().toTransportLayerPacketInfo().payload
                when (packet.payload) {
                    connect -> {
                        check(!connectSent)
                        connectSent = true
                        "CTRL_CONNECT"
                    }
                    disconnect -> {
                        check(connectSent)
                        disconnectSent = true
                        "CTRL_DISCONNECT"
                    }
                    else -> error("Application command blocked by control-only gate")
                }
            }
            else -> error("Transport command blocked by control-only gate")
        }
    }
}
