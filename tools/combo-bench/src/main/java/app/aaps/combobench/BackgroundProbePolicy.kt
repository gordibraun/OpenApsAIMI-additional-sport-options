package app.aaps.combobench

internal object BackgroundProbePolicy {
    const val DELAY_MS = 5 * 60_000L
    const val LATENESS_MS = 5 * 60_000L

    fun mayRun(stage: String, expectedId: String, receivedId: String, due: Long, now: Long,
               serviceReady: Boolean, samePairing: Boolean): Boolean =
        stage == "WAITING" && expectedId.isNotEmpty() && expectedId == receivedId && due > 0 &&
            now >= due && now - due <= LATENESS_MS && serviceReady && samePairing
}
