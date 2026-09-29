package app.aaps.combobench

internal object PeerControlPolicy {
    const val SERVICE_UUID = "d9e30fb7-4192-49f8-93c2-4f12a6d08dce"

    fun expectedPeer(flavor: String): String = when (flavor) {
        "watch" -> "BC:B2:CC:26:0D:79"
        "phone" -> "7C:F0:E5:5F:5D:52"
        else -> error("Unknown bench device")
    }

    fun validate(flavor: String, mode: String, peer: String, session: String,
                 expectedSession: String, remainingMs: Long, noPumpAccess: Boolean) {
        require(mode == "server" || mode == "client")
        require(peer.equals(expectedPeer(flavor), ignoreCase = true)) { "Only the other bench device is allowed" }
        require(session.isNotEmpty() && session == expectedSession)
        require(remainingMs in 1..120_000)
        require(noPumpAccess)
    }
}
