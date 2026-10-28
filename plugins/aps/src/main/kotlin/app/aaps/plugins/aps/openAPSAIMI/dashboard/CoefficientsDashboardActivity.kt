package app.aaps.plugins.aps.openAPSAIMI.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.widget.NestedScrollView
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.plugins.aps.openAPSAIMI.OpenAPSAIMIPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The two coefficients that set a meal's dose, at a glance: see [CoefficientsDashboard] for what is
 * said and the tests for how plainly. Opened from the overview's sensitivity block.
 */
class CoefficientsDashboardActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var aimi: OpenAPSAIMIPlugin
    @Inject lateinit var dateUtil: DateUtil

    private val bg = Color.parseColor("#10141C")
    private val card = Color.parseColor("#1E293B")
    private val textMain = Color.WHITE
    private val textDim = Color.parseColor("#B0BEC5")
    private val yellow = Color.parseColor("#FFD600")
    private val green = Color.parseColor("#66BB6A")
    private val purple = Color.parseColor("#BA68C8")
    private val orange = Color.parseColor("#FFB74D")

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Коэффициенты еды"
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(24))
            setBackgroundColor(bg)
        }
        setContentView(NestedScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        val loading = text("Считаю…", 16f, textDim).also { it.gravity = Gravity.CENTER; it.setPadding(0, dp(48), 0, 0); root.addView(it) }
        CoroutineScope(Dispatchers.IO).launch {
            val view = runCatching { aimi.coefficientsFacts()?.let { CoefficientsDashboard.build(it, { ms -> dateUtil.timeString(ms) }, { ms -> dateUtil.dateAndTimeString(ms) }) } }
            withContext(Dispatchers.Main) {
                root.removeView(loading)
                val v = view.getOrNull()
                if (v == null) {
                    root.addView(text(view.exceptionOrNull()?.let { "Не удалось собрать: ${it.javaClass.simpleName}" } ?: "Профиль не задан", 16f, orange))
                    return@withContext
                }
                render(v)
            }
        }
    }

    private fun render(v: CoefficientsDashboard.View) {
        root.addView(text(v.title, 22f, textMain, bold = true).apply { setPadding(dp(4), dp(8), 0, 0) })
        root.addView(text(v.updated, 13f, textDim).apply { setPadding(dp(4), 0, 0, dp(8)) })

        // The two big cards side by side.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(bigCard(v.isf), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(4) })
            addView(bigCard(v.ic), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(4) })
        })

        // Where they are used, with a check mark when the numbers are the ones above.
        root.addView(cardView {
            addView(text("Где используются", 15f, textMain, bold = true))
            v.usedIn.forEach { row ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(8), 0, 0)
                    val mark = when (row.matches) { true -> "✓"; false -> "!"; null -> "·" }
                    val markColor = when (row.matches) { true -> green; false -> orange; null -> textDim }
                    addView(text(mark, 20f, markColor, bold = true).apply { minWidth = dp(28) })
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            addView(text(row.who, 15f, textMain, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                            addView(text(row.whenText, 13f, textDim))
                        })
                        addView(text(row.values, 14f, if (row.matches == false) orange else textDim))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                })
            }
        })

        // The lessons per time of day.
        root.addView(cardView {
            addView(text("Уроки IC по времени суток", 15f, textMain, bold = true))
            addView(tableRow("период", "урок", "обедов", "сейчас", header = true))
            v.lessons.forEach { addView(tableRow(it.period, it.lesson, it.meals, it.applied, current = it.current)) }
        })

        // The sensitivity over the day.
        v.trend?.let { t ->
            root.addView(cardView {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(text(t.title, 15f, textMain, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(text(t.summary, 13f, purple))
                })
                addView(TrendView(context, t.points, t.labels, purple, textDim), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)))
            })
        }

        // The meals the lessons rest on.
        if (v.meals.isNotEmpty()) root.addView(cardView {
            addView(text("Последние обеды", 15f, textMain, bold = true))
            v.meals.forEach { m -> addView(text(m.text, 13f, if (m.belowProfile == true) orange else textDim).apply { setPadding(0, dp(4), 0, 0) }) }
        })

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(10), dp(6), 0)
            v.legend.forEach { addView(text(it, 12f, textDim)) }
        })
    }

    private fun bigCard(c: CoefficientsDashboard.BigCard): View {
        val accent = if (c.accent == CoefficientsDashboard.Accent.FOOD_ISF) yellow else green
        return cardView {
            addView(text(c.title, 13f, accent, bold = true))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                addView(text(c.value, 40f, textMain, bold = true))
                addView(text(c.unit, 12f, textDim).apply { setPadding(dp(6), 0, 0, dp(8)) })
            })
            addView(text(c.meaning, 14f, textMain).apply { setPadding(0, dp(2), 0, dp(6)) })
            c.details.forEach { addView(text(it, 12f, textDim)) }
            c.status?.let { s ->
                val color = when (s.kind) {
                    CoefficientsDashboard.StatusKind.LEARNED  -> green
                    CoefficientsDashboard.StatusKind.LEARNING -> yellow
                    CoefficientsDashboard.StatusKind.OFF      -> orange
                }
                addView(text("● ${s.text}", 12f, color, bold = true).apply { setPadding(0, dp(8), 0, 0) })
            }
        }
    }

    private fun tableRow(a: String, b: String, c: String, d: String, header: Boolean = false, current: Boolean = false): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
            val color = if (header) textDim else if (current) textMain else textDim
            val size = if (header) 12f else 14f
            addView(text(a + (if (current) " ◀" else ""), size, color, bold = current), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2.2f))
            addView(text(b, size, if (header) textDim else green, bold = !header), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(c, size, color), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
            addView(text(d, size, if (header) textDim else textMain, bold = current), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun cardView(content: LinearLayout.() -> Unit): CardView = CardView(this).apply {
        radius = dp(12).toFloat()
        setCardBackgroundColor(card)
        cardElevation = 0f
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            content()
        })
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** A line of hourly means, lowest and highest labelled, first and last hour under it. */
    private class TrendView(context: Context, private val points: List<Double>, private val labels: List<String>, lineColor: Int, private val dimColor: Int) : View(context) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lineColor; strokeWidth = 4f; style = Paint.Style.STROKE }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lineColor }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dimColor; textSize = 11f * context.resources.displayMetrics.scaledDensity }

        override fun onDraw(canvas: Canvas) {
            if (points.isEmpty()) return
            val padL = 40f * resources.displayMetrics.density
            val padB = 18f * resources.displayMetrics.density
            val padT = 10f * resources.displayMetrics.density
            val w = width - padL - 8f
            val h = height - padB - padT
            val min = points.min(); val max = points.max()
            val span = (max - min).takeIf { it > 1.0 } ?: 1.0
            fun x(i: Int) = padL + if (points.size == 1) w / 2 else w * i / (points.size - 1)
            fun y(v: Double) = (padT + h - ((v - min) / span * h)).toFloat()
            for (i in 1 until points.size) canvas.drawLine(x(i - 1), y(points[i - 1]), x(i), y(points[i]), line)
            points.forEachIndexed { i, v -> canvas.drawCircle(x(i), y(v), 5f, dot) }
            label.textAlign = Paint.Align.RIGHT
            canvas.drawText(max.toInt().toString(), padL - 8f, y(max) + label.textSize / 3, label)
            canvas.drawText(min.toInt().toString(), padL - 8f, y(min) + label.textSize / 3, label)
            label.textAlign = Paint.Align.LEFT
            labels.firstOrNull()?.let { canvas.drawText(it, x(0), height.toFloat() - 4f, label) }
            if (labels.size > 2) { label.textAlign = Paint.Align.CENTER; canvas.drawText(labels[labels.size / 2], x(labels.size / 2), height.toFloat() - 4f, label) }
            label.textAlign = Paint.Align.RIGHT
            labels.lastOrNull()?.let { canvas.drawText(it, x(points.size - 1), height.toFloat() - 4f, label) }
        }
    }
}
