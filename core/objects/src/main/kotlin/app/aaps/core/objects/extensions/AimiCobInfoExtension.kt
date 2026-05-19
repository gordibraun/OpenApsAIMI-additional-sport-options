package app.aaps.core.objects.extensions

import app.aaps.core.data.iob.CobInfo
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.RT
import kotlin.math.max

private const val AIMI_COB_MAX_AGE_MS = 12L * 60L * 1000L

fun CobInfo.withAimiResultCob(loop: Loop, now: Long): CobInfo {
    val result = loop.lastRun?.constraintsProcessed ?: return this
    if (result.algorithm != APSResult.Algorithm.AIMI) return this
    val age = now - result.date
    if (age < 0L || age > AIMI_COB_MAX_AGE_MS) return this

    val aimiCob = (result.rawData() as? RT)
        ?.COB
        ?.takeIf { it.isFinite() && it >= 0.0 }
        ?: return this

    return copy(timestamp = max(timestamp, result.date), displayCob = aimiCob)
}
