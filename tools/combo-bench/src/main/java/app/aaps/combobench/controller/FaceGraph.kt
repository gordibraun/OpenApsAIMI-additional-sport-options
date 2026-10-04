package app.aaps.combobench.controller

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The face's graph, drawn as a bitmap for a small-image complication: the last hour of readings
 * as dots, the next hour and a half of the forecast as dots ten minutes apart past a "now" mark,
 * the target and the 80 line with their values, insulin on board in the corner and the trend as
 * an arrow. A glance tells which way glucose is going and whether insulin is still working. Few
 * dots on purpose: thirty-seven forecast points five minutes apart read as a smear (4 Oct).
 */
internal object FaceGraph {

    /** The slot on the face is 340×86 of 450; the screen is 466 px wide. */
    const val WIDTH = 352
    const val HEIGHT = 89

    private const val HISTORY_MIN = 60
    private const val FORECAST_MIN = 90

    /** Forecast dots this far apart; the series itself is five minutes apart. */
    private const val FORECAST_STEP_MIN = 10
    private const val PLOT_TOP = 23f
    private const val PLOT_BOTTOM = HEIGHT - 5f
    private const val PLOT_LEFT = 4f
    private const val PLOT_RIGHT = WIDTH - 4f

    fun draw(facts: FaceFacts?, nowEpochMs: Long): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 19f }

        val history = facts?.readings.orEmpty().filter { it.atEpochMs >= nowEpochMs - HISTORY_MIN * 60_000L && it.atEpochMs <= nowEpochMs + 60_000L }
        // Every second point of the series, from ten minutes on: the first is glucose now, already drawn.
        val forecast = facts?.forecast?.series.orEmpty().take(FORECAST_MIN / 5 + 1)
            .withIndex().filter { it.index > 0 && (it.index * 5) % FORECAST_STEP_MIN == 0 }
        if (history.isEmpty() && forecast.isEmpty()) {
            text.color = Color.GRAY; text.textAlign = Paint.Align.CENTER
            canvas.drawText("график: нет данных", WIDTH / 2f, HEIGHT / 2f + 7f, text)
            return bitmap
        }

        val values = history.map { it.mgdl } + forecast.map { it.value.toDouble() }
        val low = minOf(60.0, values.min() - 10.0)
        val high = maxOf(180.0, values.max() + 10.0)
        val total = (HISTORY_MIN + FORECAST_MIN).toDouble()
        fun x(minutesFromNow: Double) = (PLOT_LEFT + (minutesFromNow + HISTORY_MIN) / total * (PLOT_RIGHT - PLOT_LEFT)).toFloat()
        fun y(mgdl: Double) = (PLOT_BOTTOM - ((mgdl - low) / (high - low)).coerceIn(0.0, 1.0) * (PLOT_BOTTOM - PLOT_TOP)).toFloat()

        // Guides: the target and the 80 line, each with its value at the left end, and now.
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f }
        paint.strokeWidth = 1f
        facts?.targetMgdl?.let {
            paint.color = 0x806B8E9E.toInt(); canvas.drawLine(PLOT_LEFT, y(it), PLOT_RIGHT, y(it), paint)
            label.color = 0xCC9AA5AE.toInt(); canvas.drawText(it.roundToInt().toString(), PLOT_LEFT + 2f, y(it) - 2f, label)
        }
        paint.color = 0x80FF7043.toInt(); canvas.drawLine(PLOT_LEFT, y(80.0), PLOT_RIGHT, y(80.0), paint)
        label.color = 0xCCFF7043.toInt(); canvas.drawText("80", PLOT_LEFT + 2f, y(80.0) - 2f, label)
        paint.color = 0x66FFFFFF; canvas.drawLine(x(0.0), PLOT_TOP, x(0.0), PLOT_BOTTOM, paint)

        // The forecast first, the readings over it.
        paint.color = if (facts?.forecast?.byWatch == true) 0xFF4DD0E1.toInt() else 0xFF80DEEA.toInt()
        for (point in forecast) canvas.drawCircle(x(point.index * 5.0), y(point.value.toDouble()), 2.8f, paint)
        paint.color = Color.WHITE
        for (reading in history) canvas.drawCircle(x((reading.atEpochMs - nowEpochMs) / 60_000.0), y(reading.mgdl), 3.6f, paint)

        // Corners: insulin (and carbohydrates) on board; the trend; the forecast's lowest point.
        text.color = Color.WHITE; text.textAlign = Paint.Align.LEFT
        canvas.drawText(onBoard(facts), PLOT_LEFT, 17f, text)
        facts?.deltaPer5Min?.let { delta ->
            text.textAlign = Paint.Align.RIGHT
            text.color = if (delta <= -5.0) 0xFFFFB74D.toInt() else Color.WHITE
            canvas.drawText("${arrow(delta)} ${if (delta > 0) "+" else ""}${delta.roundToInt()}", PLOT_RIGHT, 17f, text)
        }
        facts?.forecast?.let { f ->
            text.textSize = 13f; text.typeface = Typeface.DEFAULT; text.textAlign = Paint.Align.RIGHT
            text.color = if (f.minMgdl < 80) 0xFFFF7043.toInt() else 0xFF9AA5AE.toInt()
            canvas.drawText("мин ${f.minMgdl} · ${if (f.byWatch) "часы" else "тел"}", PLOT_RIGHT, PLOT_BOTTOM - 2f, text)
        }
        return bitmap
    }

    private fun onBoard(facts: FaceFacts?): String {
        val iob = facts?.iobU?.let { "IOB %.1f".format(Locale.getDefault(), it) } ?: "IOB —"
        val cob = facts?.cobG?.takeIf { it >= 1.0 }?.let { " · COB ${it.roundToInt()}" } ?: ""
        return iob + cob
    }

    /** Dexcom's own thresholds, per five minutes: 5, 10 and 17.5 mg/dL. */
    fun arrow(deltaPer5Min: Double): String = when {
        deltaPer5Min >= 17.5 -> "⇈"
        deltaPer5Min >= 10.0 -> "↑"
        deltaPer5Min >= 5.0  -> "↗"
        deltaPer5Min > -5.0  -> "→"
        deltaPer5Min > -10.0 -> "↘"
        deltaPer5Min > -17.5 -> "↓"
        else                 -> "⇊"
    }

    fun describe(facts: FaceFacts?): String {
        val f = facts?.forecast ?: return "График глюкозы: данных нет"
        return "График: последний час и прогноз на полтора часа, минимум ${f.minMgdl}, через четыре часа ${f.endMgdl}; " +
            (facts.iobU?.let { "активный инсулин %.1f ЕД".format(Locale.getDefault(), it) } ?: "активный инсулин неизвестен")
    }
}
