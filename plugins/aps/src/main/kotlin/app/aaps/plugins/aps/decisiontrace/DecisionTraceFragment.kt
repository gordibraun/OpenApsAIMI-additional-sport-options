package app.aaps.plugins.aps.decisiontrace

import android.graphics.Color
import android.graphics.Paint
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
import androidx.core.view.MenuCompat
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.utils.HtmlHelper
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.databinding.DecisionTraceFragmentBinding
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
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
    @Inject lateinit var dateUtil: DateUtil

    @Suppress("PrivatePropertyName")
    private val ID_MENU_RUN = 504

    private var _binding: DecisionTraceFragmentBinding? = null
    private var handler = Handler(
        HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper
    )

    private val binding get() = _binding!!

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
            handler.post { activePlugin.activeAPS.invoke("Decision Trace swipe refresh", false) }
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
            "Это последовательность шагов determine-basal: сигналы прогноза, решающий минимум, первичный запрос, safety-механизмы и итоговое действие."
        )
        setSectionHelp(
            binding.rawTitle,
            "Подробный лог",
            "Сырьё для разбора: отфильтрованные строки reason и consoleLog, по которым можно понять, где именно логика усилила, ограничила или отменила инсулин."
        )
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        menu.add(Menu.FIRST, ID_MENU_RUN, 0, rh.gs(R.string.openapsma_run))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        MenuCompat.setGroupDividerEnabled(menu, true)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            ID_MENU_RUN -> {
                handler.post { activePlugin.activeAPS.invoke("Decision Trace menu", false) }
                true
            }

            else -> false
        }

    override fun onResume() {
        super.onResume()
        disposable += rxBus
            .toObservable(EventOpenAPSUpdateGui::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventResetOpenAPSGui::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ resetGUI(it.text) }, fabricPrivacy::logException)

        updateGUI()
    }

    override fun onPause() {
        super.onPause()
        disposable.clear()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        handler.looper.quitSafely()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateGUI() {
        if (_binding == null) return
        val apsPlugin = activePlugin.activeAPS
        val lastAPSResult = apsPlugin.lastAPSResult
        if (lastAPSResult == null) {
            resetGUI(rh.gs(R.string.no_aps_selected))
            return
        }
        val raw = lastAPSResult.rawData() as? RT

        binding.lastrun.text = dateUtil.dateAndTimeString(lastAPSResult.date)
        setInteractiveText(binding.summaryMain, HtmlHelper.fromHtml(buildDecisionSummary(lastAPSResult, raw)))
        setInteractiveText(binding.traceStages, HtmlHelper.fromHtml(buildDecisionStages(lastAPSResult, raw)))
        setInteractiveText(binding.rawTrace, HtmlHelper.fromHtml(buildRawTrace(lastAPSResult, raw)))
        binding.swipeRefresh.isRefreshing = false
    }

    private fun resetGUI(text: String) {
        if (_binding == null) return
        binding.lastrun.text = ""
        binding.summaryMain.text = text
        binding.traceStages.text = ""
        binding.rawTrace.text = ""
        binding.swipeRefresh.isRefreshing = false
    }

    private fun buildDecisionSummary(lastAPSResult: APSResult, raw: RT?): String {
        val bg = raw?.bg ?: lastAPSResult.glucoseStatus?.glucose
        val predicted = raw?.predictedBG
        val eventual = raw?.eventualBG
        val minGuard = raw?.minGuardBG
        val threshold = raw?.hypoThreshold
        val decisive = detectDecisiveSignal(bg, predicted, eventual, minGuard)
        val smbMismatch = detectSmbRecommendationMismatch(lastAPSResult, raw)

        return buildString {
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
