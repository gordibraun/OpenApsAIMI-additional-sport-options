package app.aaps.plugins.aps.decisiontrace

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.util.Locale

/** Read-only presentation. No command, treatment or algorithm references. */
internal class DecisionExplanationView(context: Context) : LinearLayout(context) {
    var onMap: () -> Unit = {}
    var onJournal: () -> Unit = {}
    private var previous: TraceRun? = null
    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val foreground get() = Color.parseColor(if (dark) "#EEEEEE" else "#202020")
    private val muted get() = Color.parseColor(if (dark) "#BDBDBD" else "#616161")
    private val insulin get() = Color.parseColor(if (dark) "#64B5F6" else "#1565C0")
    private val food get() = Color.parseColor(if (dark) "#FFEB3B" else "#806000")
    private val sensitivity get() = Color.parseColor(if (dark) "#CE93D8" else "#7B1FA2")
    private val warning get() = Color.parseColor(if (dark) "#FF8A80" else "#B71C1C")

    init { orientation = VERTICAL }

    fun render(run: TraceRun?) {
        if (run == previous && childCount > 0) return
        previous = run
        removeAllViews()
        if (run == null) { addView(label("Нет записанного расчёта", 18f)); return }
        val story = DecisionExplanation.from(run)
        addView(label(story.title, 21f).apply { setTypeface(typeface, Typeface.BOLD) })
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(label("${number(story.smb)} Е\nМикродоза к отправке", 18f, insulin), LayoutParams(0, -2, 1f))
            addView(label("${number(story.basal)} Е/ч\nНазначенный базал", 18f, insulin), LayoutParams(0, -2, 1f))
        })
        if ((story.carbs ?: 0.0) > 0) addView(label("Углеводы по расчёту: ${number(story.carbs)} г", 18f, food))
        divider()
        with(story.context) {
            val trend = delta?.takeIf { it.isFinite() }?.let { " · Δ ${number(it)}" }.orEmpty()
            addView(label("Глюкоза ${number(bg)}$trend · цель ${number(target)} мг/дл", 16f))
            addView(label("Активный инсулин ${number(iob)} Е", 16f, insulin))
            addView(label("Остаток еды ${number(cob)} г", 16f, food))
            addView(label("ISF решения ${number(isf)} мг/дл/Е", 15f, sensitivity))
        }
        divider()
        if (story.changes.isEmpty()) addView(label("Числовой путь микродозы в этой записи отсутствует.", 15f, muted))
        story.changes.forEachIndexed { index, change -> changeRow(index + 1, change) }
        if (story.changes.none { it.stopped } && story.smb == 0.0) {
            story.blockers.distinctBy { it.branch?.id }.forEach { event ->
                addView(label(event.title, 16f, warning))
                addView(label(readableDecisionReason(event.detail), 14f, muted))
            }
        }
        divider()
        addView(label("Итоговый прогноз после решения", 17f).apply { setTypeface(typeface, Typeface.BOLD) })
        val forecast = story.context.forecast
        if (forecast.size >= 2 && forecast.all { it > 0 }) {
            addView(RecordedForecastView(context, forecast, story.context.target, foreground, muted, insulin, warning),
                LayoutParams(-1, dp(190)))
            addView(label("Пик ${forecast.maxOrNull()} · минимум ${forecast.minOrNull()} мг/дл", 15f))
            addView(label("Линия для предложения AIMI: ${number(story.context.forecastSmb)} Е и ${number(story.context.forecastBasal)} Е/ч.", 13f, muted))
            if (story.forecastDiffersFromRequest) addView(label("Поздние ограничения изменили запрос. Эта линия не пересчитана под них.", 15f, warning))
        } else addView(label("Записанной линии прогноза нет.", 14f, muted))
        divider()
        addView(label("Как выбран базал", 18f, insulin).apply { setTypeface(typeface, Typeface.BOLD) })
        story.basalChanges.forEachIndexed { index, change -> changeRow(index + 1, change) }
        if (story.basalChanges.isEmpty()) addView(label("Изменения базала в журнале не записаны.", 14f, muted))
        divider()
        addView(label("Факт подачи", 18f).apply { setTypeface(typeface, Typeface.BOLD) })
        addView(label(story.delivery, 15f))
        addView(label(story.basalDelivery, 14f, muted))
        divider()
        disclosure("Остальные проверки", onJournal)
        disclosure("Полная карта веток", onMap)
    }

    private fun changeRow(index: Int, change: ExplainedChange) {
        val accent = if (change.reduced) warning else insulin
        if (change.gapBefore) addView(label("Между этими значениями нет полного числового перехода в журнале.", 13f, warning))
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        row.addView(object : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            init { contentDescription = "Шаг $index" }
            override fun onDraw(canvas: Canvas) {
                paint.color = accent
                paint.strokeWidth = dp(2).toFloat()
                val cx = width / 2f
                canvas.drawLine(cx, dp(30).toFloat(), cx, height.toFloat(), paint)
                canvas.drawCircle(cx, dp(16).toFloat(), dp(12).toFloat(), paint)
                paint.color = if (dark) Color.BLACK else Color.WHITE
                paint.textSize = dp(13).toFloat()
                paint.typeface = Typeface.DEFAULT_BOLD
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(index.toString(), cx, dp(16) - (paint.ascent() + paint.descent()) / 2, paint)
            }
        }, LayoutParams(dp(28), -1))
        val body = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(10), dp(6), dp(8), dp(10))
            background = GradientDrawable().apply {
                setColor(if (change.reduced) (accent and 0xffffff) or 0x14000000 else Color.TRANSPARENT)
                setStroke(dp(1), (accent and 0xffffff) or 0x60000000)
                cornerRadius = dp(4).toFloat()
            }
        }
        body.addView(label(change.event.title, 16f).apply { setTypeface(typeface, Typeface.BOLD) })
        val value = change.before?.let { "${number(it)} → " }.orEmpty() + number(change.after) + if (change.basal) " Е/ч" else " Е"
        body.addView(label(value, 21f, accent).apply { setTypeface(typeface, Typeface.BOLD) })
        if (change.reduced && change.reason.isNotBlank()) {
            val brief = when (change.event.title) {
                "Ослабление с учётом остаточного действия" -> "Учтено остаточное действие ранее введённого инсулина."
                "Округление до шага помпы" -> "Предложение округлено до шага помпы."
                else -> readableDecisionReason(change.reason)
            }
            body.addView(label(brief, 14f))
        }
        if (change.stopped && !change.basal) {
            body.addView(label("Выбрана отмена микродозы", 14f, warning))
            body.addView(label("Это предложение отменено в данном расчёте.", 13f, muted))
        }
        body.addView(label("Запись №${change.event.sequence} · подробности ›", 13f, muted).apply {
            minHeight = dp(44)
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            contentDescription = "Подробности: ${change.event.title}"
            setOnClickListener {
                AlertDialog.Builder(context).setTitle(change.event.title)
                    .setMessage("$value\n\n${change.reason.ifBlank { "Причина в этом шаге не записана." }}")
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        })
        row.addView(body, LayoutParams(0, -2, 1f))
        addView(row, LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(6) })
    }

    private fun disclosure(text: String, action: () -> Unit) {
        addView(label("$text  ›", 16f).apply {
            minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; isFocusable = true
            setOnClickListener { action() }
        })
    }

    private fun divider() = addView(View(context).apply {
        setBackgroundColor(if (dark) 0xff383838.toInt() else 0xffdddddd.toInt())
    }, LayoutParams(-1, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(12) })

    private fun label(value: String, size: Float, color: Int = foreground) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); letterSpacing = 0f
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun number(value: Double?) = value?.takeIf { it.isFinite() }?.let {
        String.format(Locale.getDefault(), "%.2f", it).trimEnd('0').trimEnd('.', ',')
    } ?: "нет данных"
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** Plots only saved samples, at their recorded five-minute spacing. */
internal class RecordedForecastView(
    context: Context, private val series: List<Int>, private val target: Double?,
    private val foreground: Int, private val muted: Int, private val line: Int, private val low: Int
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private fun dp(n: Float) = n * density
    init { contentDescription = "Записанный финальный прогноз, ${series.size} точек" }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (series.size < 2) return
        paint.textSize = 12 * resources.displayMetrics.scaledDensity
        val target = target?.takeIf { it.isFinite() && it > 0 }
        val min = minOf(series.min().toDouble(), target ?: Double.MAX_VALUE) - 15
        val max = maxOf(series.max().toDouble(), target ?: 0.0) + 15
        val left = dp(8f)
        val right = width - dp(8f)
        val top = dp(26f)
        val bottom = height - dp(32f)
        fun x(i: Int) = left + (right - left) * i / series.lastIndex
        fun y(n: Double) = (bottom - (n - min) / (max - min) * (bottom - top)).toFloat()
        paint.strokeWidth = dp(1f)
        paint.color = muted
        canvas.drawLine(left, bottom, right, bottom, paint)
        target?.let {
            paint.pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
            canvas.drawLine(left, y(it), right, y(it), paint)
            paint.pathEffect = null
            val label = "Цель ${it.toInt()}"
            canvas.drawText(label, right - paint.measureText(label), top - dp(8f), paint)
        }
        paint.strokeWidth = dp(2.5f)
        for (i in 1..series.lastIndex) {
            paint.color = if (target != null && series[i] < target) low else line
            canvas.drawLine(x(i - 1), y(series[i - 1].toDouble()), x(i), y(series[i].toDouble()), paint)
        }
        paint.color = foreground
        for (i in listOf(0, series.lastIndex)) {
            canvas.drawCircle(x(i), y(series[i].toDouble()), dp(3f), paint)
        }
        paint.color = muted
        canvas.drawText("Расчёт", left, height - dp(8f), paint)
        val end = "+${series.lastIndex * 5} мин"
        canvas.drawText(end, right - paint.measureText(end), height - dp(8f), paint)
    }
}
