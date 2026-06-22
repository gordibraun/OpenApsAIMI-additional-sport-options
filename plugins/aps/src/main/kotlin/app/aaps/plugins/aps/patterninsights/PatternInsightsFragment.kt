package app.aaps.plugins.aps.patterninsights

import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.utils.HtmlHelper
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.databinding.PatternInsightsFragmentBinding
import dagger.android.support.DaggerFragment
import java.util.Locale
import javax.inject.Inject

class PatternInsightsFragment : DaggerFragment() {

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var persistenceLayer: PersistenceLayer

    private var _binding: PatternInsightsFragmentBinding? = null
    private val binding get() = _binding!!
    private var handler = Handler(
        HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        PatternInsightsFragmentBinding.inflate(inflater, container, false).also {
            _binding = it
        }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.swipeRefresh.setColorSchemeColors(
            rh.gac(context, android.R.attr.colorPrimaryDark),
            rh.gac(context, android.R.attr.colorPrimary),
            rh.gac(context, com.google.android.material.R.attr.colorSecondary)
        )
        binding.swipeRefresh.setOnRefreshListener { loadReport() }
        loadReport()
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

    private fun loadReport() {
        if (_binding == null) return
        binding.swipeRefresh.isRefreshing = true
        handler.post {
            val result = runCatching {
                val now = dateUtil.now()
                val start = now - T.days(8).msecs()
                NegativePatternDetector.analyze(
                    now = now,
                    glucose = persistenceLayer.getBgReadingsDataFromTimeToTime(start, now, true),
                    boluses = persistenceLayer.getBolusesFromTime(start, true).blockingGet(),
                    carbs = persistenceLayer.getCarbsFromTime(start, true).blockingGet(),
                    // APS results contain full prediction arrays; loading days of them can exhaust the app heap.
                    apsResults = emptyList()
                )
            }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                result.fold(::renderReport) { renderError(it) }
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun renderReport(report: NegativePatternDetector.PatternReport) {
        binding.generatedAt.text = dateUtil.dateAndTimeString(report.generatedAt)
        binding.summary.text = report.summary
        renderPatterns(report)
        renderStandaloneSections(report, report.patterns.isEmpty())
    }

    private fun renderPatterns(report: NegativePatternDetector.PatternReport) {
        val patterns = report.patterns
        binding.patternsList.removeAllViews()
        if (patterns.isEmpty()) {
            binding.patternsList.addView(patternText("Повторяющихся негативных паттернов по текущей базе не найдено."))
            return
        }
        patterns.forEachIndexed { index, pattern ->
            val details = patternText(expandedPatternHtml(report, pattern)).apply {
                visibility = View.GONE
            }
            val button = Button(requireContext()).apply {
                isAllCaps = false
                text = buttonTitle(pattern, details.visibility == View.VISIBLE)
                setOnClickListener {
                    details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    text = buttonTitle(pattern, details.visibility == View.VISIBLE)
                }
            }
            binding.patternsList.addView(button, blockParams(topMarginDp = if (index == 0) 0 else 6))
            binding.patternsList.addView(details, blockParams(topMarginDp = 2))
        }
    }

    private fun renderStandaloneSections(report: NegativePatternDetector.PatternReport, visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        binding.findingsTitle.visibility = visibility
        binding.findings.visibility = visibility
        binding.daysTitle.visibility = visibility
        binding.days.visibility = visibility
        binding.suggestionsTitle.visibility = visibility
        binding.suggestions.visibility = visibility
        if (!visible) return

        binding.findings.text = HtmlHelper.fromHtml(findingsHtml(report.findings))
        binding.days.text = HtmlHelper.fromHtml(daysHtml(report.days))
        binding.suggestions.text = HtmlHelper.fromHtml(suggestionsHtml(report.suggestions))
    }

    private fun expandedPatternHtml(
        report: NegativePatternDetector.PatternReport,
        pattern: NegativePatternDetector.NegativePattern
    ): String =
        "<b>Что найдено</b><br>${findingsHtml(report.findings.ifEmpty { listOf(pattern.detail) })}" +
            "<br><br><b>Эпизоды</b><br>${daysHtml(report.days)}" +
            "<br><br><b>Что проверить</b><br>${suggestionsHtml(report.suggestions.ifEmpty { listOf(pattern.suggestion) })}"

    private fun findingsHtml(findings: List<String>): String =
        findings.joinToString("<br><br>") { "• ${escape(it)}" }

    private fun suggestionsHtml(suggestions: List<String>): String =
        suggestions.joinToString("<br><br>") { "• ${escape(it)}" }

    private fun daysHtml(days: List<NegativePatternDetector.DailyPattern>): String =
        days.joinToString("<br><br>") { day ->
            "<b>${escape(day.label)} 18:00-03:00</b><br>" +
                "ГК ${fmtMgdl(day.bgMin)}-${fmtMgdl(day.bgMax)}, SMB ${fmtU(day.smbUnits)}, обычный болюс ${fmtU(day.normalBolusUnits)}, углеводы после риска ${fmtG(day.rescueCarbs)}.<br>" +
                escape(day.note)
        }

    private fun patternText(html: String): TextView =
        TextView(requireContext()).apply {
            text = HtmlHelper.fromHtml(html)
            textSize = 14f
            setLineSpacing(dp(2).toFloat(), 1.0f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

    private fun renderError(error: Throwable) {
        binding.generatedAt.text = dateUtil.dateAndTimeString(dateUtil.now())
        binding.summary.text = rh.gs(R.string.pattern_insights_error)
        binding.patternsList.removeAllViews()
        binding.findingsTitle.visibility = View.VISIBLE
        binding.findings.visibility = View.VISIBLE
        binding.daysTitle.visibility = View.GONE
        binding.days.visibility = View.GONE
        binding.suggestionsTitle.visibility = View.GONE
        binding.suggestions.visibility = View.GONE
        binding.findings.text = error.message ?: error.javaClass.simpleName
        binding.days.text = ""
        binding.suggestions.text = ""
    }

    private fun buttonTitle(pattern: NegativePatternDetector.NegativePattern, expanded: Boolean): String {
        val marker = if (expanded) "v" else ">"
        return "$marker ${pattern.occurrences} раз  ${pattern.title}"
    }

    private fun blockParams(topMarginDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(topMarginDp)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun fmtMgdl(value: Int?): String = value?.toString() ?: "?"
    private fun fmtU(value: Double): String = String.format(Locale.US, "%.1fU", value)
    private fun fmtG(value: Double): String = String.format(Locale.US, "%.0fg", value)

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

}
