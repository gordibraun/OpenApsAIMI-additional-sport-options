package app.aaps.combobench

internal enum class ProbeTransport(val wireName: String, val authenticated: Boolean = false) {
    SDP("sdp"), CHANNEL_ONE("channel1"),
    SDP_AUTHENTICATED("sdp-authenticated", true), CHANNEL_ONE_AUTHENTICATED("channel1-authenticated", true);

    companion object {
        fun parse(value: String): ProbeTransport = entries.firstOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("Unknown diagnostic transport")
    }
}
