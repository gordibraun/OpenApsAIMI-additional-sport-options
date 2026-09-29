package app.aaps.core.objects.activity

import app.aaps.core.data.model.TE
import app.aaps.core.data.time.T
import kotlin.math.abs

/** The same activity adjustment for phone and watch meal calculations. */
object ActivityBolusContextCalculator {
    data class Context(val factor: Double, val description: String)
    private data class Window(val mode: String, val effect: Double, val start: Long, val activeEnd: Long, val tailEnd: Long, val tailMinutes: Long)

    fun calculate(events: List<TE>, now: Long): Context? {
        val window = events.filter { it.isValid && it.type == TE.Type.EXERCISE && it.note?.contains("AIMI_ACTIVITY_V2") == true }
            .mapNotNull { event ->
                val mode = token(event.note, "mode")?.uppercase() ?: return@mapNotNull null
                val effect = token(event.note, "effect")?.toDoubleOrNull()?.let { (it / 100.0).coerceIn(0.0, 0.45) }
                    ?: when (mode) { "WALK" -> 0.20; "SPORT" -> 0.30; else -> 0.0 }
                if (!effect.isFinite() || effect <= 0) return@mapNotNull null
                val duration = (token(event.note, "duration")?.toLongOrNull() ?: event.duration / T.mins(1).msecs()).coerceAtLeast(0L)
                val tail = (token(event.note, "tail")?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                val activeEnd = event.timestamp + T.mins(duration).msecs()
                val tailEnd = activeEnd + T.mins(tail).msecs()
                if (event.timestamp > now + T.hours(2).msecs() || tailEnd < now - T.mins(5).msecs()) return@mapNotNull null
                Window(mode, effect, event.timestamp, activeEnd, tailEnd, tail)
            }.minByOrNull { abs(it.start - now) } ?: return null
        val offset = ((window.start - now) / T.mins(1).msecs()).toInt()
        val overlap = when {
            now in window.start..window.activeEnd -> 1.0
            now in (window.activeEnd + 1)..window.tailEnd && window.tailMinutes > 0 ->
                ((window.tailEnd - now).toDouble() / T.mins(window.tailMinutes).msecs()).coerceIn(0.0, 1.0)
            offset in 1..75 -> 1.0
            offset in 76..120 -> ((120 - offset).toDouble() / 45.0).coerceIn(0.0, 1.0)
            else -> 0.0
        }
        if (overlap <= 0) return null
        val factor = (1.0 - window.effect * overlap).coerceIn(0.55, 1.0)
        val phase = when { now < window.start -> "старт через ${offset.coerceAtLeast(0)} мин"; now <= window.activeEnd -> "активна"; else -> "хвост" }
        return Context(factor, "${window.mode} $phase, новый инсулин x${"%.2f".format(factor)}")
    }

    private fun token(note: String?, key: String) = note?.split(' ')?.firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')?.takeIf { it.isNotBlank() }
}
