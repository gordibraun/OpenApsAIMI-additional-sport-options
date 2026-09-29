package app.aaps.plugins.aps.decisiontrace

import android.graphics.Color
import android.graphics.Paint
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.LinearLayout
import androidx.core.view.MenuCompat
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.DecisionStage
import app.aaps.core.interfaces.aps.DecisionTraceStep
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.objects.extensions.target
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.utils.HtmlHelper
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.databinding.DecisionTraceFragmentBinding
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.core.interfaces.rx.events.EventLoopUpdateGui
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

class DecisionTraceFragment : DaggerFragment(), MenuProvider {

    private var disposable: CompositeDisposable = CompositeDisposable()

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var loop: Loop

    @Suppress("PrivatePropertyName")
    private val ID_MENU_RUN = 504

    private var _binding: DecisionTraceFragmentBinding? = null
    private var handler = Handler(
        HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper
    )

    private val binding get() = _binding!!
    private var historyLoaded = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        DecisionTraceFragmentBinding.inflate(inflater, container, false).also {
            _binding = it
            requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.swipeRefresh.setColorSchemeColors(
            rh.gac(context, android.R.attr.colorPrimaryDark),
            rh.gac(context, android.R.attr.colorPrimary),
            rh.gac(context, com.google.android.material.R.attr.colorSecondary)
        )
        binding.swipeRefresh.setOnRefreshListener {
            updateGUI()
        }
        binding.summaryTitle.paintFlags = binding.summaryTitle.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        binding.traceTitle.paintFlags = binding.traceTitle.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        binding.rawTitle.paintFlags = binding.rawTitle.paintFlags or Paint.UNDERLINE_TEXT_FLAG

        setSectionHelp(
            binding.summaryTitle,
            "Decision Trace",
            "Показывает, какие прогнозные величины AIMI реально сравнил, что из них оказалось решающим, какой инсулин был запрошен и какие guard или ограничения затем вмешались."
        )
        setSectionHelp(
            binding.traceTitle,
            "Этапы решения",
            "Шаги записываются во время расчёта, включая ранний выход. Ниже идут ограничения цикла и отдельно подтверждение помпы. Это не реконструкция по ключевым словам."
        )
        setSectionHelp(
            binding.rawTitle,
            "Подробный лог",
            "Сырьё для разбора: отфильтрованные строки reason и consoleLog, по которым можно понять, где именно логика усилила, ограничила или отменила инсулин."
        )
        binding.summaryTitle.setOnClickListener { toggle(binding.summaryMain) }
        binding.rawTitle.setOnClickListener { toggle(binding.rawTrace) }
        binding.traceTitle.setOnClickListener {
            toggle(binding.traceStages)
            toggle(binding.journalSteps)
            if (binding.journalSteps.visibility == View.VISIBLE) updateGUI()
        }
    }

    private fun toggle(view: View) {
        view.visibility = if (view.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        menu.add(Menu.FIRST, ID_MENU_RUN, 0, "Обновить журнал")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        MenuCompat.setGroupDividerEnabled(menu, true)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            ID_MENU_RUN -> {
                updateGUI()
                true
            }

            else -> false
        }

    override fun onResume() {
        super.onResume()
        disposable += rxBus.toObservable(EventLoopUpdateGui::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventOpenAPSUpdateGui::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventResetOpenAPSGui::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ resetGUI(it.text) }, fabricPrivacy::logException)

        updateGUI()
        binding.flowPlayer.start()
        if (!historyLoaded) {
            val now = dateUtil.now()
            disposable += Single.fromCallable {
                persistenceLayer.getApsResults(now - 2 * 60 * 60 * 1000L, now)
                    .sortedByDescending { it.date }
                    .mapNotNull { result -> (result.rawData() as? RT)?.decisionTrace?.takeIf { it.isNotEmpty() }?.let {
                        TraceRun(result.date, it, TraceRunState.CALCULATED, DecisionContext.from(result))
                    } }
                    .take(12)
            }.subscribeOn(aapsSchedulers.io).observeOn(aapsSchedulers.main).subscribe({ records ->
                records.forEach { DecisionTraceLive.history.merge(it.id, it.steps, it.context) }
                historyLoaded = true
                _binding?.flowPlayer?.refresh()
            }, fabricPrivacy::logException)
        }
    }

    override fun onPause() {
        super.onPause()
        disposable.clear()
        _binding?.flowPlayer?.stop()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        handler.looper.quitSafely()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding?.flowPlayer?.stop()
        _binding = null
    }

    private fun updateGUI() {
        if (_binding == null) return
        val apsPlugin = activePlugin.activeAPS
        val requested = apsPlugin.lastAPSResult
        val lastAPSResult = loop.lastRun?.constraintsProcessed?.takeIf { it.date == requested?.date } ?: requested
        if (lastAPSResult == null) {
            resetGUI(rh.gs(R.string.no_aps_selected))
            return
        }
        val raw = lastAPSResult.rawData() as? RT
        val contextWarning = requested?.let { buildContextWarning(it) }

        binding.lastrun.text = dateUtil.dateAndTimeString(lastAPSResult.date)
        val steps = raw?.decisionTrace.orEmpty()
        DecisionTraceLive.history.merge(lastAPSResult.date, steps, requested?.takeIf { it.date == lastAPSResult.date }?.let(DecisionContext::from))
        binding.flowPlayer.refresh()
        val summary = if (steps.isEmpty()) buildDecisionSummary(lastAPSResult, raw) else buildRecordedSummary(lastAPSResult, raw!!)
        setInteractiveText(binding.summaryMain, HtmlHelper.fromHtml(withContextWarning(contextWarning, summary)))
        binding.traceStages.text = if (steps.isEmpty())
            "В этом расчёте нет записанного порядка шагов. Исторический лог доступен ниже; порядок по его тексту не восстанавливается."
        else "${steps.size} записей в порядке выполнения; расчёт ${dateUtil.dateAndTimeString(lastAPSResult.date)}"
        if (binding.journalSteps.visibility == View.VISIBLE) renderJournal(steps)
        setInteractiveText(binding.rawTrace, HtmlHelper.fromHtml(withContextWarning(contextWarning, buildRawTrace(lastAPSResult, raw))))
        binding.swipeRefresh.isRefreshing = false
    }

    private fun withContextWarning(contextWarning: String?, body: String): String =
        if (contextWarning == null) body else "$contextWarning<br><br>$body"

    private fun buildContextWarning(lastAPSResult: APSResult): String? {
        val resultProfile = lastAPSResult.oapsProfileAimi ?: return null
        val now = dateUtil.now()
        val currentProfile = profileFunction.getProfile(now)
        val currentProfileSwitch = persistenceLayer.getEffectiveProfileSwitchActiveAt(now)
        val currentPercentage = currentProfileSwitch?.originalPercentage ?: currentProfile?.percentage
        val currentTempTarget = persistenceLayer.getTemporaryTargetActiveAt(now)
        val currentTempTargetSet = currentTempTarget != null
        val currentTarget = currentTempTarget?.target() ?: currentProfile?.getTargetMgdl()

        val issues = mutableListOf<String>()
        if (resultProfile.temptargetSet != currentTempTargetSet) {
            issues += "временная цель в расчете: ${yesNo(resultProfile.temptargetSet)}, сейчас: ${yesNo(currentTempTargetSet)}"
        }
        if (currentTarget != null && abs(currentTarget - resultProfile.target_bg) >= 2.0) {
            issues += "цель в расчете: ${formatMgdl(resultProfile.target_bg)}, сейчас: ${formatMgdl(currentTarget)}"
        }
        if (currentPercentage != null && currentPercentage != resultProfile.profile_percentage) {
            issues += "профиль в расчете: ${resultProfile.profile_percentage}%, сейчас: ${currentPercentage}%"
        }

        if (issues.isEmpty()) return null
        return buildString {
            append("<b>Внимание: расчет не от текущего контекста</b><br>")
            append(issues.joinToString("<br>"))
            append("<br>")
            append("Ниже показана запись старого расчёта. Новый цикл появится отдельно; просмотр не запускает алгоритм.")
        }
    }

    private fun resetGUI(text: String) {
        if (_binding == null) return
        binding.lastrun.text = ""
        binding.summaryMain.text = text
        binding.traceStages.text = ""
        binding.journalSteps.removeAllViews()
        binding.rawTrace.text = ""
        binding.swipeRefresh.isRefreshing = false
    }

    private fun buildRecordedSummary(result: APSResult, raw: RT): String = buildString {
        val proposed = activePlugin.activeAPS.lastAPSResult?.takeIf { it.date == result.date }?.rawData() as? RT ?: raw
        append("<b>Предложение алгоритма, не факт подачи</b><br>")
        append("Микродоза ${formatUnits(proposed.units)} Е; базал ${formatUnits(proposed.rate)} Е/ч; углеводы ${proposed.carbsReq ?: 0} г.<br>")
        val constrained = loop.lastRun?.constraintsProcessed?.takeIf { it.date == result.date }
        if (constrained != null) {
            append("<b>После внешних ограничений</b><br>")
            append("Микродоза ${formatUnits(constrained.smb)} Е; базал ${formatUnits(constrained.rate)} Е/ч.<br>")
            if (abs(constrained.smb - (proposed.units ?: 0.0)) > 0.001 || abs(constrained.rate - (proposed.rate ?: constrained.rate)) > 0.001)
                append("Прогноз рассчитан для предложения AIMI; ограничения изменили подачу.<br>")
        } else append("Внешние ограничения этого расчёта ещё не получены.<br>")
        val delivery = raw.decisionTrace.lastOrNull { it.stage == DecisionStage.DELIVERY }
        append("<b>Состояние передачи</b><br>")
        append(android.text.TextUtils.htmlEncode(delivery?.let { "${it.title}: ${it.detail}" } ?: "Подтверждения помпы для этого расчёта нет."))
    }

    private val expandedStages = mutableSetOf<Int>()
    private var journalTimestamp: Long? = null

    private fun renderJournal(steps: List<DecisionTraceStep>) {
        if (journalTimestamp != (loop.lastRun?.constraintsProcessed?.date ?: activePlugin.activeAPS.lastAPSResult?.date)) {
            expandedStages.clear()
            journalTimestamp = loop.lastRun?.constraintsProcessed?.date ?: activePlugin.activeAPS.lastAPSResult?.date
        }
        binding.journalSteps.removeAllViews()
        val density = resources.displayMetrics.density
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val groups = mutableListOf<MutableList<DecisionTraceStep>>()
        steps.forEach { step ->
            if (groups.lastOrNull()?.lastOrNull()?.stage != step.stage) groups.add(mutableListOf())
            groups.last().add(step)
        }
        groups.forEach { group ->
            val first = group.first()
            val (name, color) = when (first.stage) {
                DecisionStage.INPUT -> "Исходные данные" to if (dark) "#80CBC4" else "#00695C"
                DecisionStage.INSULIN -> "Активный инсулин" to if (dark) "#64B5F6" else "#1565C0"
                DecisionStage.SENSITIVITY -> "ISF решения и автосенс" to if (dark) "#CE93D8" else "#7B1FA2"
                DecisionStage.CARBS -> "Еда и остаток углеводов" to if (dark) "#FFEB3B" else "#806000"
                DecisionStage.FORECAST -> "Прогноз" to if (dark) "#80CBC4" else "#00695C"
                DecisionStage.SMB -> "Автоматическая микродоза" to if (dark) "#64B5F6" else "#1565C0"
                DecisionStage.SAFETY, DecisionStage.CONSTRAINTS -> "Проверки и ограничения" to if (dark) "#FF8A80" else "#B71C1C"
                DecisionStage.BASAL -> "Временный базал" to if (dark) "#64B5F6" else "#1565C0"
                DecisionStage.FINAL -> "Итог расчёта" to if (dark) "#A5D6A7" else "#2E7D32"
                DecisionStage.DELIVERY -> "Передача помпе" to if (dark) "#FFCC80" else "#A34800"
            }
            val details = TextView(requireContext()).apply {
                text = group.joinToString("\n\n") { "${it.sequence}. ${it.title}\n${it.detail}" }
                textSize = 14f
                setTextColor(Color.parseColor(color))
                setTextIsSelectable(true)
                setPadding((12 * density).toInt(), 0, (8 * density).toInt(), (12 * density).toInt())
                visibility = if (first.sequence in expandedStages) View.VISIBLE else View.GONE
            }
            val heading = TextView(requireContext()).apply {
                text = "${first.sequence}–${group.last().sequence}. $name"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(color))
                minHeight = (48 * density).toInt()
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.arrow_down_float, 0)
                isFocusable = true
                setOnClickListener {
                    val expand = details.visibility != View.VISIBLE
                    details.visibility = if (expand) View.VISIBLE else View.GONE
                    if (expand) expandedStages.add(first.sequence) else expandedStages.remove(first.sequence)
                    setCompoundDrawablesWithIntrinsicBounds(0, 0, if (expand) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float, 0)
                }
            }
            binding.journalSteps.addView(heading, LinearLayout.LayoutParams(-1, -2))
            binding.journalSteps.addView(details, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun buildDecisionSummary(lastAPSResult: APSResult, raw: RT?): String {
        val bg = raw?.bg ?: lastAPSResult.glucoseStatus?.glucose
        val predicted = raw?.predictedBG
        val eventual = raw?.eventualBG
        val minGuard = raw?.minGuardBG
        val threshold = raw?.hypoThreshold
        val decisive = detectDecisiveSignal(bg, predicted, eventual, minGuard)
        val smbMismatch = detectSmbRecommendationMismatch(lastAPSResult, raw)
        val humanSummary = extractHumanSummary(lastAPSResult)
            ?: buildHumanSummary(lastAPSResult, raw, bg, predicted, eventual, minGuard, threshold)

        return buildString {
            append("<b>Итог простыми словами</b><br>")
            append(humanSummary)
            append("<br><br>")
            if (smbMismatch != null) {
                append("<b>Внимание: возможная ошибка SMB</b><br>")
                append(smbMismatch.simpleText)
                append("<br><br>")
            }
            append("<b>Что система реально сравнила</b><br>")
            append("BG сейчас: ${formatMgdl(bg)}")
            append("<br>")
            append("Predicted BG: ${formatMgdl(predicted)}")
            append("<br>")
            append("Eventual BG: ${formatMgdl(eventual)}")
            append("<br>")
            append("Решающий минимум: ${formatMgdl(minGuard)}")
            append(" | Safety threshold: ${formatMgdl(threshold)}")
            append("<br><br>")
            append("<b>Что оказалось решающим</b><br>")
            append(decisive)
            append("<br><br>")
            append("<b>Что AIMI запросил</b><br>")
            append("SMB: ${formatUnits(lastAPSResult.smb)} Е")
            append(" | insulinReq: ${formatUnits(raw?.insulinReq)} Е")
            append("<br>")
            append("TBR: ${formatTempBasal(lastAPSResult.rate, lastAPSResult.duration)}")
        }
    }

    private fun extractHumanSummary(lastAPSResult: APSResult): String? =
        lastAPSResult.reason
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("Итог простыми словами:", ignoreCase = true) }
            ?.removePrefix("Итог простыми словами:")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun buildHumanSummary(
        lastAPSResult: APSResult,
        raw: RT?,
        bg: Double?,
        predicted: Double?,
        eventual: Double?,
        minGuard: Double?,
        threshold: Double?
    ): String {
        val forecast = minGuard ?: predicted ?: eventual
        val carbsReq = raw?.carbsReq ?: lastAPSResult.carbsReq
        val carbsWithin = raw?.carbsReqWithin ?: lastAPSResult.carbsReqWithin
        val insulinReq = raw?.insulinReq ?: 0.0
        val smb = lastAPSResult.smb

        val state = buildString {
            append("Сейчас сахар ${formatMgdl(bg)}")
            forecast?.let { append(", решающий прогноз ${formatMgdl(it)}") }
            threshold?.let { append(", опасная граница ${formatMgdl(it)}") }
            append(".")
        }

        val action = when {
            carbsReq > 0 -> "Система просит $carbsReq г углеводов за $carbsWithin мин и не должна усиливать инсулин."
            smb > 0.01 -> "Система добавляет SMB ${formatUnits(smb)} Е и ставит TBR ${formatTempBasal(lastAPSResult.rate, lastAPSResult.duration)}."
            insulinReq > 0.01 -> "Расчет видел потребность в инсулине ${formatUnits(insulinReq)} Е, но итоговый SMB сейчас ${formatUnits(smb)} Е."
            lastAPSResult.rate > 0.01 && lastAPSResult.duration > 0 -> "Система не дает SMB, но ставит TBR ${formatTempBasal(lastAPSResult.rate, lastAPSResult.duration)}."
            else -> "Система не добавляет инсулин: SMB 0.00 Е, TBR ${formatTempBasal(lastAPSResult.rate, lastAPSResult.duration)}."
        }

        val why = when {
            forecast != null && threshold != null && forecast <= threshold + 10.0 ->
                "Причина: прогноз близко к опасной зоне, поэтому приоритет у защиты от гипо."
            forecast != null && bg != null && forecast < bg - 15.0 ->
                "Причина: несмотря на текущий сахар, прогноз заметно ниже текущего значения, поэтому инсулин ограничен."
            smb > 0.01 ->
                "Причина: прогноз и ограничения разрешили коррекцию инсулином."
            carbsReq > 0 ->
                "Причина: расчет видит риск низкой глюкозы и предлагает поднять ее углеводами."
            else ->
                "Причина: итоговые ограничения не разрешили дополнительный SMB."
        }

        return "$state $action $why"
    }

    private fun buildDecisionStages(lastAPSResult: APSResult, raw: RT?): String {
        val bg = raw?.bg ?: lastAPSResult.glucoseStatus?.glucose
        val predicted = raw?.predictedBG
        val eventual = raw?.eventualBG
        val minGuard = raw?.minGuardBG
        val threshold = raw?.hypoThreshold
        val decisive = detectDecisiveSignal(bg, predicted, eventual, minGuard)
        val primaryRequest = extractPrimaryRequest(raw, lastAPSResult)
        val safetyLines = extractSafetyLines(lastAPSResult, raw)
        val finalAction = buildFinalAction(lastAPSResult, raw)
        val smbMismatch = detectSmbRecommendationMismatch(lastAPSResult, raw)

        return buildString {
            append("<b>Этап 1. Прогнозные сигналы</b><br>")
            append("BG=${formatMgdl(bg)} | Predicted BG=${formatMgdl(predicted)} | Eventual BG=${formatMgdl(eventual)}")
            append("<br>")
            append("minGuardBG=${formatMgdl(minGuard)} | hypoThreshold=${formatMgdl(threshold)}")
            append("<br><br>")

            append("<b>Этап 2. Решающий минимум</b><br>")
            append(decisive)
            append("<br><br>")

            append("<b>Этап 3. Первичный запрос AIMI</b><br>")
            append(primaryRequest)
            append("<br><br>")

            append("<b>Этап 4. Guard / fallback / clamp</b><br>")
            append(if (safetyLines.isBlank()) "Явный модификатор решения не найден." else safetyLines)
            append("<br><br>")

            append("<b>Этап 5. Итоговое действие</b><br>")
            append(finalAction)
            if (smbMismatch != null) {
                append("<br><br>")
                append("<b>Диагностика несостыковки</b><br>")
                append(smbMismatch.detailText)
            }
        }
    }

    private fun buildRawTrace(lastAPSResult: APSResult, raw: RT?): String {
        if (!raw?.decisionTrace.isNullOrEmpty()) return raw!!.decisionTrace.joinToString("<br><br>") {
            android.text.TextUtils.htmlEncode("${it.sequence}. ${it.title}: ${it.detail}")
        }
        val smbMismatch = detectSmbRecommendationMismatch(lastAPSResult, raw)
        val filteredReason = lastAPSResult.reason
            .split('\n')
            .map { it.trim() }
            .filter { line ->
                line.contains("Safety", true) ||
                    line.contains("SMB", true) ||
                    line.contains("BasalPlanner", true) ||
                    line.contains("LGS", true) ||
                    line.contains("Predicted BG", true) ||
                    line.contains("Eventual BG", true) ||
                    line.contains("BGI", true) ||
                    line.contains("COB", true) ||
                    line.contains("Target", true) ||
                    line.contains("fallback", true) ||
                    line.contains("clamp", true)
            }

        val filteredConsole = raw?.consoleLog
            ?.filter { line ->
                line.contains("SMB Decision", true) ||
                    line.contains("SMB forced", true) ||
                    line.contains("BasalPlanner", true) ||
                    line.contains("LGS", true) ||
                    line.contains("Safety", true) ||
                    line.contains("fallback", true) ||
                    line.contains("clamp", true) ||
                    line.contains("PKPD", true) ||
                    line.contains("BGI", true) ||
                    line.contains("Carb Impact", true)
            }
            .orEmpty()

        val lines = (filteredReason + filteredConsole).distinct()
        if (lines.isEmpty()) return "Подходящих строк для Decision Trace пока нет."
        return buildString {
            if (smbMismatch != null) {
                append("<b>Быстрая диагностика</b><br>")
                append(smbMismatch.detailText)
                append("<br><br>")
            }
            append("<b>Ключевые строки</b><br>")
            append(lines.joinToString("<br><br>") { HtmlHelper.fromHtml(it).toString() })
        }
    }

    private fun detectDecisiveSignal(bg: Double?, predicted: Double?, eventual: Double?, minGuard: Double?): String {
        if (minGuard == null) return "Система не сохранила minGuardBG для этого расчёта."
        return when {
            bg != null && abs(bg - minGuard) < 0.5 -> "Решающим стало текущее BG: именно оно оказалось минимальным среди сигналов."
            predicted != null && abs(predicted - minGuard) < 0.5 -> "Решающим стал Predicted BG: safety ориентировался на более низкий операционный прогноз."
            eventual != null && abs(eventual - minGuard) < 0.5 -> "Решающим стал Eventual BG: дальняя точка прогноза оказалась самой опасной."
            else -> "Решающий минимум есть, но не совпал точно ни с BG, ни с Predicted BG, ни с Eventual BG."
        }
    }

    private fun extractPrimaryRequest(raw: RT?, lastAPSResult: APSResult): String {
        val reason = lastAPSResult.reason
        val proposed = Regex("""proposed=([0-9.,]+)""", RegexOption.IGNORE_CASE)
            .find(reason)
            ?.groupValues
            ?.getOrNull(1)
        val finalSmb = Regex("""Final SMB:\s*([0-9.,]+)""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
            .find(reason)
            ?.groupValues
            ?.getOrNull(1)

        val pieces = mutableListOf<String>()
        if (proposed != null) pieces += "Proposed SMB до safety: ${normalizeNumber(proposed)} Е"
        if (finalSmb != null) pieces += "Final SMB после логики AIMI: ${normalizeNumber(finalSmb)} Е"
        pieces += "smb в результате: ${formatUnits(lastAPSResult.smb)} Е"
        pieces += "insulinReq: ${formatUnits(raw?.insulinReq)} Е"
        pieces += "TBR: ${formatTempBasal(lastAPSResult.rate, lastAPSResult.duration)}"
        return pieces.joinToString("<br>")
    }

    private fun extractSafetyLines(lastAPSResult: APSResult, raw: RT?): String {
        val lines = buildList {
            raw?.safetyMechanism?.takeIf { it.isNotBlank() }?.let { add("safetyMechanism: $it") }
            addAll(
                lastAPSResult.reason.split('\n').map { it.trim() }.filter {
                    it.contains("Safety", true) ||
                        it.contains("guard", true) ||
                        it.contains("fallback", true) ||
                        it.contains("clamp", true) ||
                        it.contains("LGS", true) ||
                        it.contains("BasalPlanner", true)
                }
            )
            addAll(
                raw?.consoleLog.orEmpty().filter {
                    it.contains("Safety", true) ||
                        it.contains("forced", true) ||
                        it.contains("fallback", true) ||
                        it.contains("clamp", true) ||
                        it.contains("LGS", true) ||
                        it.contains("BasalPlanner", true)
                }
            )
        }.distinct()
        return lines.joinToString("<br>")
    }

    private fun buildFinalAction(lastAPSResult: APSResult, raw: RT?): String = buildString {
        append("SMB к исполнению: ${formatUnits(lastAPSResult.smb)} Е")
        append("<br>")
        append("TBR к исполнению: ${formatTempBasal(lastAPSResult.rate, lastAPSResult.duration)}")
        raw?.carbsReq?.takeIf { it > 0 }?.let {
            append("<br>")
            append("Carbs request: $it г за ${raw.carbsReqWithin ?: 0} мин")
        }
    }

    private fun detectSmbRecommendationMismatch(lastAPSResult: APSResult, raw: RT?): SmbRecommendationMismatch? {
        val reason = lastAPSResult.reason
        val internalFinalSmb = extractLastNumber(reason, """Final SMB:\s*([0-9]+(?:[,.][0-9]+)?)""")
        val quantizedSmb = extractLastNumber(reason, """quantized=([0-9]+(?:[,.][0-9]+)?)""")
        val internalRecommended = listOfNotNull(internalFinalSmb, quantizedSmb).maxOrNull() ?: return null
        val finalSmb = lastAPSResult.smb
        val finalInsulinReq = raw?.insulinReq ?: 0.0
        val finalIsZero = finalSmb <= 0.01 && finalInsulinReq <= 0.01

        if (internalRecommended <= 0.05 || !finalIsZero) return null

        val simple = "AIMI внутри рассчитал SMB ${formatUnits(internalRecommended)} Е, но финально к подаче ушло 0.00 Е."
        val detail = buildString {
            append(simple)
            append("<br>")
            append("Это не похоже на обычный safety-стоп: проверь, почему положительный SMB не дошел до итогового `units`.")
            append("<br>")
            append("Внутри: Final SMB=${formatUnits(internalFinalSmb)} Е")
            append(" | quantized=${formatUnits(quantizedSmb)} Е")
            append(" | итоговый SMB=${formatUnits(finalSmb)} Е")
            append(" | итоговый insulinReq=${formatUnits(finalInsulinReq)} Е")
        }
        return SmbRecommendationMismatch(simple, detail)
    }

    private fun extractLastNumber(text: String, pattern: String): Double? =
        Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
            .findAll(text)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull() }
            .lastOrNull()

    private fun normalizeNumber(value: String): String = value.replace(',', '.')

    private fun formatMgdl(value: Double?): String =
        value?.let { String.format(Locale.US, "%.0f мг/дл", it) } ?: "—"

    private fun formatUnits(value: Double?): String =
        value?.let { String.format(Locale.US, "%.2f", it) } ?: "0.00"

    private fun yesNo(value: Boolean): String =
        if (value) "да" else "нет"

    private fun formatTempBasal(rate: Double, duration: Int): String =
        if (rate < 0 || duration < 0) "без запроса"
        else String.format(Locale.US, "%.2f Е/ч на %d мин", rate, duration)

    private data class SmbRecommendationMismatch(
        val simpleText: String,
        val detailText: String
    )

    private data class GlossaryDefinition(
        val title: String,
        val body: String
    )

    private val glossary by lazy {
        linkedMapOf(
            "Прогнозные сигналы" to GlossaryDefinition(
                "Прогнозные сигналы",
                "Это набор чисел, которые AIMI реально сравнивает перед решением: текущее BG, Predicted BG, Eventual BG, minGuardBG и safety threshold."
            ),
            "Решающий минимум" to GlossaryDefinition(
                "Решающий минимум",
                "Самое опасное число среди прогнозных сигналов. Именно оно сильнее всего влияет на защиту от гипо и может остановить SMB или поднятый basal."
            ),
            "Первичный запрос AIMI" to GlossaryDefinition(
                "Первичный запрос AIMI",
                "То, что AIMI хотел сделать до того, как guard, fallback и clamp изменили запрос."
            ),
            "Guard" to GlossaryDefinition(
                "Guard",
                "Защитное правило, которое ограничивает решение при опасном или подозрительном контексте."
            ),
            "fallback" to GlossaryDefinition(
                "fallback",
                "Запасной путь логики: система использует его, когда обычный прогноз или основной путь недостаточно надёжны."
            ),
            "clamp" to GlossaryDefinition(
                "clamp",
                "Ограничитель. Он урезает уже рассчитанное действие, чтобы оно не вышло за безопасные рамки."
            ),
            "SMB" to GlossaryDefinition(
                "SMB",
                "Super Micro Bolus — маленький автоматический болюс, который loop может запросить для быстрой коррекции."
            ),
            "TBR" to GlossaryDefinition(
                "TBR",
                "Temporary Basal Rate — временная базальная скорость на заданный интервал."
            ),
            "BG" to GlossaryDefinition(
                "BG",
                "Текущее значение глюкозы сенсора, с которого стартует весь расчёт."
            ),
            "Predicted BG" to GlossaryDefinition(
                "Predicted BG",
                "Операционный прогноз BG для safety и части логики принятия решения."
            ),
            "Eventual BG" to GlossaryDefinition(
                "Eventual BG",
                "Дальняя конечная точка прогноза, которую AIMI ожидает в конце текущей траектории."
            ),
            "minGuardBG" to GlossaryDefinition(
                "minGuardBG",
                "Минимум, который safety использует как главный сигнал опасности среди нескольких прогнозных величин."
            ),
            "hypoThreshold" to GlossaryDefinition(
                "hypoThreshold",
                "Порог, ниже которого система считает подачу инсулина рискованной."
            ),
            "Decision Trace" to GlossaryDefinition(
                "Decision Trace",
                "Пошаговый след решения: какие величины были сравнены, что оказалось решающим и как safety изменил исходный запрос."
            )
        )
    }

    private fun setSectionHelp(view: TextView, title: String, message: String) {
        view.setOnClickListener {
            if (context != null) OKDialog.show(requireContext(), title, message)
        }
    }

    private fun setInteractiveText(textView: TextView, content: CharSequence) {
        val interactive = makeInteractiveGlossary(content)
        textView.text = interactive
        textView.movementMethod = LinkMovementMethod.getInstance()
        textView.highlightColor = Color.TRANSPARENT
    }

    private fun makeInteractiveGlossary(content: CharSequence): CharSequence {
        val spannable = SpannableStringBuilder(content)
        val occupied = mutableListOf<IntRange>()
        glossary.keys.sortedByDescending { it.length }.forEach { term ->
            val regex = Regex(Regex.escape(term), RegexOption.IGNORE_CASE)
            regex.findAll(spannable).forEach { match ->
                val range = match.range
                val overlaps = occupied.any { existing -> range.first <= existing.last && existing.first <= range.last }
                if (!overlaps) {
                    val definition = glossary[term] ?: return@forEach
                    val span = object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            if (context != null) OKDialog.show(requireContext(), definition.title, definition.body)
                        }

                        override fun updateDrawState(ds: TextPaint) {
                            super.updateDrawState(ds)
                            ds.isUnderlineText = true
                        }
                    }
                    spannable.setSpan(span, range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    occupied += range
                }
            }
        }
        return spannable
    }
}
