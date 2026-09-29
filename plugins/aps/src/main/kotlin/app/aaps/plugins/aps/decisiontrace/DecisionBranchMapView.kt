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
import app.aaps.core.interfaces.aps.DecisionStage
import app.aaps.core.interfaces.aps.DecisionTraceStep

/** Renders recorded choices only. This view has no dosing or device dependencies. */
internal class DecisionBranchMapView(context: Context) : LinearLayout(context) {
    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val foreground get() = Color.parseColor(if (dark) "#EEEEEE" else "#202020")
    private val muted get() = Color.parseColor(if (dark) "#9E9E9E" else "#616161")
    private var previous: Triple<Long?, List<DecisionTraceStep>, Int>? = null

    init { orientation = VERTICAL }

    fun render(run: TraceRun?, throughSequence: Int) {
        val recordedBranches = run?.steps.orEmpty().filter { it.branch != null }
        val steps = recordedBranches.filter { it.sequence <= throughSequence }
        val key = Triple(run?.id, steps, recordedBranches.size)
        if (key == previous) return
        previous = key
        removeAllViews()
        val final = steps.lastOrNull { it.branch?.id == "final" }
        val resultText = when {
            final == null -> if (recordedBranches.any { it.branch?.id == "final" }) "Итог алгоритма ещё не показан" else "Итог алгоритма: нет записи"
            final.branch?.outcome == "error" -> "Ошибка расчёта; предложения нет"
            else -> "Предложение: " + final.values.joinToString("   ") { "${it.name}: ${number(it.after)} ${it.unit}" }
        }
        addView(label(resultText, 16f, foreground).apply { setTypeface(typeface, Typeface.BOLD) })
        steps.lastOrNull { it.branch?.id == "delivery_smb" }?.let { event ->
            addView(label(when (event.branch?.outcome) {
                "requested" -> "Отправлен запрос; это ещё не подтверждение введения"
                "wait" -> "Отправка SMB отложена: ${event.detail}"
                "blocked" -> "SMB не отправлена: ${event.detail}"
                else -> "SMB не запрошена"
            }, 14f, foreground))
        }
        val delivery = steps.lastOrNull { it.branch?.id == "delivery_result" }
        addView(label(when (delivery?.branch?.outcome) {
            "success" -> "Помпа подтвердила микродозу"
            "failed" -> "Микродоза не подтверждена"
            else -> "Подтверждения микродозы в этой записи нет"
        }, 13f, muted))
        addView(label("Выбранный путь — сплошной. Альтернативы — пунктир.", 12f, muted))
        addView(label("Показано развилок: ${steps.size} / ${recordedBranches.size}", 13f, foreground))
        if (steps.isEmpty()) addView(label(
            if (recordedBranches.isEmpty()) "В этом расчёте нет записанных ветвлений" else "До первой развилки", 15f, muted))

        var unseenHeading = false
        val visits = DecisionBranchMap.visits(steps)
        visits.forEachIndexed { index, visit ->
            if (visit.step == null && !unseenHeading) {
                unseenHeading = true
                addView(label("Ветки без записи в этом расчёте", 15f, muted).apply { setPadding(0, dp(20), 0, dp(8)) })
            }
            val selected = visit.selected
            val accent = when (visit.node.stage) {
                DecisionStage.SENSITIVITY -> Color.parseColor(if (dark) "#CE93D8" else "#7B1FA2")
                DecisionStage.CARBS -> Color.parseColor(if (dark) "#FFEB3B" else "#806000")
                DecisionStage.SMB, DecisionStage.INSULIN, DecisionStage.BASAL -> Color.parseColor(if (dark) "#64B5F6" else "#1565C0")
                DecisionStage.SAFETY, DecisionStage.CONSTRAINTS -> Color.parseColor(if (dark) "#FF8A80" else "#B71C1C")
                else -> Color.parseColor(if (dark) "#80CBC4" else "#00695C")
            }
            val branch = ForkLayout(context, accent, muted).apply {
                orientation = VERTICAL
                setPadding(dp(20), dp(8), 0, dp(8))
            }
            val nodeTitle = if (visit.node.id in setOf("smb_adjustment", "basal_rules")) visit.step?.title ?: visit.node.title else visit.node.title
            val question = label("${visit.step?.sequence?.let { "№$it · " } ?: ""}$nodeTitle", 15f,
                if (selected == null) muted else foreground).apply {
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = outline(if (selected == null) muted else accent, false)
            }
            branch.addView(question, LayoutParams(-1, -2))
            visit.node.options.forEach { option ->
                val chosen = selected == option
                val text = label(option.label + when {
                    chosen -> " · выбран"
                    selected == null -> " · выбор неизвестен"
                    else -> " · не выбран"
                }, 14f, if (chosen) accent else muted).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(42)
                    setPadding(dp(10), dp(8), dp(8), dp(8))
                    background = outline(if (chosen) accent else muted, chosen)
                    if (chosen) setTypeface(typeface, Typeface.BOLD)
                    contentDescription = "${visit.node.title}: $text"
                    isFocusable = true
                    setOnClickListener {
                        val event = visit.step
                        val status = when { event == null -> "Выбор не записан. Нельзя заключить, что проверка была пройдена."
                            chosen -> "Выбрано в записи №${event.sequence}."
                            else -> "В записи №${event.sequence} выбрана другая ветка: ${selected?.label ?: "неизвестный исход"}." }
                        val values = event?.values.orEmpty().joinToString("\n") {
                            "${it.name}: ${it.before?.let { before -> "${number(before)} → " }.orEmpty()}${number(it.after)} ${it.unit}"
                        }
                        AlertDialog.Builder(context).setTitle(visit.node.title)
                            .setMessage(listOf(status, event?.detail.orEmpty(), values).filter { it.isNotBlank() }.joinToString("\n\n"))
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
                }
                branch.addView(text, LayoutParams(-1, -2).apply { marginStart = dp(24); topMargin = dp(5) })
                branch.chosen.add(chosen)
            }
            addView(branch, LayoutParams(-1, -2))
            if (visit.step != null && visits.getOrNull(index + 1)?.step != null)
                addView(object : View(context) {
                    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; strokeWidth = dp(2).toFloat() }
                    override fun onDraw(canvas: Canvas) {
                        val x = width / 2f
                        val y = height - dp(4).toFloat()
                        canvas.drawLine(x, dp(2).toFloat(), x, y, paint)
                        canvas.drawLine(x - dp(4), y - dp(4), x, y, paint)
                        canvas.drawLine(x + dp(4), y - dp(4), x, y, paint)
                    }
                }, LayoutParams(-1, dp(24)))
        }
    }

    private inner class ForkLayout(context: Context, private val accent: Int, private val neutral: Int) : LinearLayout(context) {
        val chosen = mutableListOf<Boolean>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        override fun dispatchDraw(canvas: Canvas) {
            val origin = getChildAt(0) ?: return super.dispatchDraw(canvas)
            val spineX = dp(9).toFloat()
            val startY = (origin.top + origin.bottom) / 2f
            // Draw the highlighted path last so dashed alternatives cannot overwrite it.
            for (index in (1 until childCount).sortedBy { chosen.getOrElse(it - 1) { false } }) {
                val child = getChildAt(index)
                val endY = (child.top + child.bottom) / 2f
                val selected = chosen.getOrElse(index - 1) { false }
                paint.color = if (selected) accent else neutral
                paint.strokeWidth = dp(if (selected) 3 else 1).toFloat()
                paint.pathEffect = if (selected) null else DashPathEffect(floatArrayOf(dp(4).toFloat(), dp(4).toFloat()), 0f)
                canvas.drawLine(origin.left.toFloat(), startY, spineX, startY, paint)
                canvas.drawLine(spineX, startY, spineX, endY, paint)
                canvas.drawLine(spineX, endY, child.left.toFloat() - dp(3), endY, paint)
                canvas.drawLine(child.left.toFloat() - dp(7), endY - dp(4), child.left.toFloat() - dp(3), endY, paint)
                canvas.drawLine(child.left.toFloat() - dp(7), endY + dp(4), child.left.toFloat() - dp(3), endY, paint)
            }
            super.dispatchDraw(canvas)
        }
    }

    private fun label(value: String, size: Float, color: Int) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); letterSpacing = 0f
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun outline(color: Int, selected: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(4).toFloat()
        setColor(if (selected) (color and 0xffffff) or 0x18000000 else Color.TRANSPARENT)
        setStroke(dp(if (selected) 2 else 1), color)
    }
    private fun number(value: String) = value.toDoubleOrNull()?.let { String.format(java.util.Locale.getDefault(), "%.2f", it) } ?: value
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
