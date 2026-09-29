package app.aaps.plugins.aps.decisiontrace

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import app.aaps.core.interfaces.aps.DecisionStage
import app.aaps.core.interfaces.aps.DecisionTraceStep
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Playback has no reference to APS, treatment entry, or the pump command queue. */
class DecisionFlowPlayer @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val playback = DecisionPlayback()
    private val uiHandler = Handler(Looper.getMainLooper())
    private var active = false
    private var playing = false
    private var revision = -1L
    private var runChoices = emptyList<TraceRun>()
    private var updating = false
    private var lastRouteKey: Pair<Long?, List<Int>>? = null
    private var lastSelectedSequence: Int? = null
    private var nodes = emptyList<Pair<DecisionTraceStep, TextView>>()
    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val neutral get() = Color.parseColor(if (dark) "#DADADA" else "#333333")

    private val modes = MaterialButtonToggleGroup(context).apply { isSingleSelection = true; isSelectionRequired = true }
    private val live = modeButton("Сейчас")
    private val replay = modeButton("Разбор")
    private val runs = Spinner(context).apply { contentDescription = "Выбранный расчёт" }
    private val status = label(13f)
    private val route = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val routeScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(route, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
    private val levels = MaterialButtonToggleGroup(context).apply { isSingleSelection = true; isSelectionRequired = true }
    private val map = modeButton("Карта")
    private val important = modeButton("Шаги")
    private val all = modeButton("Журнал")
    private val branchMap = DecisionBranchMapView(context)
    private val explanation = DecisionExplanationView(context)
    private var showingExplanation = true
    private val backToExplanation = label(16f).apply {
        text = "‹ К объяснению решения"; minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        setOnClickListener { showingExplanation = true; stopPlaying(); render(); revealStart() }
    }
    private val playbackControls = LinearLayout(context)
    private var showingMap = true
    private val progress = SeekBar(context)
    private val position = label(13f)
    private val previous = icon(android.R.drawable.ic_media_previous, "Предыдущий шаг") { playback.previous(); pauseAndRender() }
    private val play = icon(android.R.drawable.ic_media_play, "Воспроизвести запись") { togglePlayback() }
    private val next = icon(android.R.drawable.ic_media_next, "Следующий шаг") { playback.next(); pauseAndRender() }
    private val wholePath = icon(android.R.drawable.ic_media_ff, "Весь записанный путь") { playback.run?.let(playback::inspect); pauseAndRender() }
    private val title = label(18f).apply { setTypeface(typeface, Typeface.BOLD) }
    private val detail = label(15f).apply { setTextIsSelectable(true) }
    private val values = LinearLayout(context).apply { orientation = VERTICAL }
    private val eventList = LinearLayout(context).apply { orientation = VERTICAL }

    private val poll = object : Runnable {
        override fun run() {
            if (!active) return
            refresh()
            uiHandler.postDelayed(this, 400)
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!active || !playing) return
            if (!playback.next()) playing = false
            render()
            if (playing) uiHandler.postDelayed(this, 1100)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(12))
        modes.addView(live, LayoutParams(0, dp(48), 1f))
        modes.addView(replay, LayoutParams(0, dp(48), 1f))
        addView(modes)
        addView(runs, LayoutParams(-1, dp(48)))
        addView(status)
        addView(backToExplanation, LayoutParams(-1, -2))
        addView(explanation, LayoutParams(-1, -2))
        addView(routeScroll, LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(12) })
        levels.addView(map, LayoutParams(0, dp(44), 1f))
        levels.addView(important, LayoutParams(0, dp(44), 1f))
        levels.addView(all, LayoutParams(0, dp(44), 1f))
        addView(levels)
        addView(progress, LayoutParams(-1, dp(40)))
        addView(playbackControls.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(previous, LayoutParams(dp(48), dp(48)))
            addView(play, LayoutParams(dp(48), dp(48)))
            addView(next, LayoutParams(dp(48), dp(48)))
            addView(wholePath, LayoutParams(dp(48), dp(48)))
            addView(position, LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        })
        addView(title, LayoutParams(-1, -2).apply { topMargin = dp(12) })
        addView(values)
        addView(detail, LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) })
        addView(eventList)
        addView(branchMap, LayoutParams(-1, -2))

        modes.check(live.id)
        levels.check(map.id)
        explanation.onMap = { showingExplanation = false; showingMap = true; levels.check(map.id); pauseAndRender(); revealStart() }
        explanation.onJournal = { showingExplanation = false; showingMap = false; levels.check(all.id); playback.showAll(true); pauseAndRender(); revealStart() }
        modes.addOnButtonCheckedListener { _, id, checked ->
            if (!updating && checked) {
                stopPlaying()
                if (id == live.id) DecisionTraceLive.history.snapshots().firstOrNull()?.let(playback::follow)
                else playback.run?.let(playback::inspect)
                render()
            }
        }
        levels.addOnButtonCheckedListener { _, id, checked ->
            if (!updating && checked) {
                showingMap = id == map.id
                playback.showAll(id == all.id)
                pauseAndRender()
            }
        }
        runs.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, index: Int, id: Long) {
                val chosen = runChoices.getOrNull(index) ?: return
                if (!updating && chosen.id != playback.run?.id) {
                    playback.inspect(chosen)
                    pauseAndRender()
                }
            }
        }
        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) { stopPlaying() }
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) { playback.seek(value); render() }
            }
        })
        render()
    }

    fun start() {
        if (active) return
        active = true
        revision = -1
        uiHandler.post(poll)
    }

    fun stop() {
        active = false
        stopPlaying()
        uiHandler.removeCallbacks(poll)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    fun refresh() {
        val history = DecisionTraceLive.history
        if (history.revision == revision) return
        revision = history.revision
        val latest = history.snapshots()
        if (playback.following) latest.firstOrNull()?.let(playback::follow)
        val frozen = playback.run
        val oldChoices = runChoices.map { it.id }
        runChoices = if (frozen != null && latest.none { it.id == frozen.id }) latest + frozen else latest
        updating = true
        if (runChoices.map { it.id } != oldChoices) {
            runs.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                runChoices.map { time(it.id) })
        }
        runs.setSelection(runChoices.indexOfFirst { it.id == frozen?.id }.coerceAtLeast(0), false)
        updating = false
        render()
    }

    private fun togglePlayback() {
        if (playing) { pauseAndRender(); return }
        if (playback.following || playback.index == playback.steps.lastIndex) playback.run?.let(playback::replay)
        if (playback.steps.size < 2) return
        playing = true
        render()
        uiHandler.postDelayed(tick, 1100)
    }

    private fun stopPlaying() { playing = false; uiHandler.removeCallbacks(tick) }
    private fun pauseAndRender() { stopPlaying(); render() }

    private fun revealStart() {
        post { requestRectangleOnScreen(Rect(0, 0, width, dp(180).coerceAtMost(height)), false) }
    }

    private fun render() {
        updating = true
        modes.check(if (playback.following) live.id else replay.id)
        runs.setSelection(runChoices.indexOfFirst { it.id == playback.run?.id }.coerceAtLeast(0), false)
        updating = false
        val run = playback.run
        val step = playback.selected
        explanation.visibility = if (showingExplanation) VISIBLE else GONE
        backToExplanation.visibility = if (showingExplanation) GONE else VISIBLE
        listOf(levels, progress, playbackControls).forEach { it.visibility = if (showingExplanation) GONE else VISIBLE }
        listOf(routeScroll, title, values, detail, eventList).forEach { it.visibility = if (showingExplanation || showingMap) GONE else VISIBLE }
        branchMap.visibility = if (!showingExplanation && showingMap) VISIBLE else GONE
        if (showingExplanation) explanation.render(run)
        else if (showingMap) branchMap.render(run, if (playback.following) Int.MAX_VALUE else step?.sequence ?: 0)
        status.text = when {
            run == null -> "Нет записанного расчёта"
            !playback.following -> "Запись ${time(run.id)} • ${if (playing) "воспроизведение" else "пауза"}" +
                when (run.state) { TraceRunState.CALCULATING -> " • незавершённая"; TraceRunState.FAILED -> " • ошибка расчёта"; else -> "" }
            run.state == TraceRunState.CALCULATING -> "Расчёт выполняется • ${time(run.id)}"
            run.state == TraceRunState.FAILED -> "Ошибка расчёта • ${time(run.id)}"
            else -> "Расчёт завершён • ${time(run.id)}"
        }
        progress.max = (playback.steps.size - 1).coerceAtLeast(0)
        progress.progress = playback.index
        progress.isEnabled = step != null
        position.text = if (step == null) "Нет шагов" else "Шаг ${playback.index + 1} / ${playback.steps.size}\nЗапись №${step.sequence}"
        previous.isEnabled = playback.index > 0
        next.isEnabled = playback.index < playback.steps.lastIndex
        wholePath.isEnabled = step != null && playback.index < playback.steps.lastIndex
        play.isEnabled = playback.steps.size > 1
        play.setImageResource(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        play.contentDescription = if (playing) "Пауза просмотра" else "Воспроизвести запись"
        play.tooltipText = play.contentDescription
        renderRoute(run, step)
        title.text = step?.title.orEmpty()
        title.setTextColor(step?.stage?.let(::color) ?: neutral)
        detail.text = step?.detail.orEmpty()
        values.removeAllViews()
        step?.values?.forEach { value ->
            values.addView(label(15f).apply {
                setPadding(0, dp(7), 0, dp(7))
                setTextColor(color(value.stage ?: step.stage))
                val change = value.before?.let { "${number(it)} → " }.orEmpty()
                text = "${value.name}\n$change${number(value.after)} ${value.unit}".trimEnd()
                setTypeface(typeface, Typeface.BOLD)
            })
        }
        eventList.removeAllViews()
        if (step != null) {
            val allSteps = run?.steps.orEmpty()
            val groupStart = allSteps.take(step.sequence).indexOfLast { it.sequence == 1 || allSteps[it.sequence - 2].stage != it.stage }
            val group = allSteps.drop(groupStart.coerceAtLeast(0)).takeWhile { it.stage == step.stage }
            eventList.addView(label(13f).apply { text = "${name(step.stage)} • записей: ${group.size}" })
            playback.steps.filter { candidate -> group.any { it.sequence == candidate.sequence } }.forEach { candidate ->
                eventList.addView(label(14f).apply {
                    text = "${if (candidate.sequence == step.sequence) "▶" else "·"} ${candidate.sequence}. ${candidate.title}"
                    minHeight = dp(44)
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(if (candidate.sequence == step.sequence) color(step.stage) else neutral)
                    isFocusable = true
                    setOnClickListener { playback.seek(playback.steps.indexOf(candidate)); pauseAndRender() }
                })
            }
        }
    }

    private fun renderRoute(run: TraceRun?, selected: DecisionTraceStep?) {
        val steps = run?.steps.orEmpty()
        val entries = steps.filterIndexed { i, step -> i == 0 || steps[i - 1].stage != step.stage }
        val key = run?.id to entries.map { it.sequence }
        if (key != lastRouteKey) {
            if (run?.id != lastRouteKey?.first) lastSelectedSequence = null
            lastRouteKey = key
            route.removeAllViews()
            nodes = entries.mapIndexed { i, entry ->
                if (i > 0) route.addView(label(22f).apply { text = "→"; gravity = Gravity.CENTER }, LayoutParams(dp(28), dp(48)))
                val node = label(14f).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    isFocusable = true
                    setOnClickListener {
                        playback.seek(playback.steps.indexOfFirst { it.sequence >= entry.sequence }.coerceAtLeast(0))
                        pauseAndRender()
                    }
                }
                route.addView(node, LayoutParams(dp(148), dp(120)))
                entry to node
            }
        }
        val selectedNode = entries.indexOfLast { it.sequence <= (selected?.sequence ?: 0) }
        nodes.forEachIndexed { i, (entry, node) ->
            val accent = color(entry.stage)
            val selectedHere = i == selectedNode
            val state = when { selectedHere -> "Текущий шаг"; i < selectedNode -> "Просмотрено"; else -> "Далее в записи" }
            node.text = "${i + 1}. ${name(entry.stage)}\n$state"
            node.setTextColor(if (i <= selectedNode) accent else neutral)
            node.background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(if (selectedHere) (accent and 0x00ffffff) or 0x20000000 else Color.TRANSPARENT)
                setStroke(dp(if (selectedHere) 2 else 1), if (i <= selectedNode) accent else Color.GRAY)
            }
        }
        if (selected?.sequence != lastSelectedSequence) {
            lastSelectedSequence = selected?.sequence
            nodes.getOrNull(selectedNode)?.second?.let { node ->
                routeScroll.post { if (isAttachedToWindow) routeScroll.smoothScrollTo((node.left - (routeScroll.width - node.width) / 2).coerceAtLeast(0), 0) }
            }
        }
    }

    private fun label(size: Float) = TextView(context).apply { textSize = size; setTextColor(neutral) }
    private fun modeButton(caption: String) = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        id = View.generateViewId(); text = caption; textSize = 14f; isAllCaps = false; letterSpacing = 0f
        minWidth = 0; minimumWidth = 0; isCheckable = true; cornerRadius = dp(4)
        setPadding(dp(8), 0, dp(8), 0)
    }
    private fun icon(resource: Int, caption: String, action: () -> Unit) = ImageButton(context).apply {
        setImageResource(resource); contentDescription = caption; tooltipText = caption
        background = null; setColorFilter(neutral); setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun time(id: Long) = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date(id))
    private fun number(raw: String): String = raw.toDoubleOrNull()?.takeIf { it.isFinite() }
        ?.let { String.format(Locale.getDefault(), "%.2f", it).trimEnd('0').trimEnd('.', ',') } ?: raw
    private fun name(stage: DecisionStage) = when (stage) {
        DecisionStage.INPUT -> "Исходные данные"
        DecisionStage.INSULIN -> "Активный инсулин"
        DecisionStage.SENSITIVITY -> "ISF решения"
        DecisionStage.CARBS -> "Еда"
        DecisionStage.FORECAST -> "Прогноз"
        DecisionStage.SMB -> "Микродоза"
        DecisionStage.SAFETY -> "Безопасность"
        DecisionStage.BASAL -> "Базал"
        DecisionStage.FINAL -> "Итог расчёта"
        DecisionStage.CONSTRAINTS -> "Внешние лимиты"
        DecisionStage.DELIVERY -> "Передача помпе"
    }
    private fun color(stage: DecisionStage) = Color.parseColor(when (stage) {
        DecisionStage.SENSITIVITY -> if (dark) "#CE93D8" else "#7B1FA2"
        DecisionStage.CARBS -> if (dark) "#FFEB3B" else "#806000"
        DecisionStage.INSULIN, DecisionStage.SMB, DecisionStage.BASAL -> if (dark) "#64B5F6" else "#1565C0"
        DecisionStage.SAFETY, DecisionStage.CONSTRAINTS -> if (dark) "#FF8A80" else "#B71C1C"
        DecisionStage.FINAL -> if (dark) "#A5D6A7" else "#2E7D32"
        DecisionStage.DELIVERY -> if (dark) "#FFCC80" else "#A34800"
        else -> if (dark) "#80CBC4" else "#00695C"
    })
}
