package app.aaps.plugins.aps.loop

import android.graphics.Color
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
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventLoopUpdateGui
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.utils.HtmlHelper
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.databinding.LoopFragmentBinding
import app.aaps.plugins.aps.extensions.toHtml
import app.aaps.plugins.aps.loop.events.EventLoopSetLastRunGui
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject
import java.util.Locale

class LoopFragment : DaggerFragment(), MenuProvider {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var loop: Loop
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var decimalFormatter: DecimalFormatter

    @Suppress("PrivatePropertyName")
    private val ID_MENU_RUN = 501

    private var handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)
    private var disposable: CompositeDisposable = CompositeDisposable()

    private var _binding: LoopFragmentBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        LoopFragmentBinding.inflate(inflater, container, false).also {
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
            binding.lastrun.text = rh.gs(R.string.executing)
            handler.post {
                loop.invoke("Loop swipe refresh", true)
            }
        }
        setupSectionDefinitionClicks()
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        menu.add(Menu.FIRST, ID_MENU_RUN, 0, rh.gs(R.string.run_now)).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        MenuCompat.setGroupDividerEnabled(menu, true)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            ID_MENU_RUN -> {
                binding.lastrun.text = rh.gs(R.string.executing)
                handler.post { loop.invoke("Loop menu", true) }
                true
            }

            else        -> false
        }

    override fun onResume() {
        super.onResume()
        disposable += rxBus
            .toObservable(EventLoopUpdateGui::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({
                           updateGUI()
                       }, fabricPrivacy::logException)

        disposable += rxBus
            .toObservable(EventLoopSetLastRunGui::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({
                           clearGUI()
                           binding.lastrun.text = it.text
                       }, fabricPrivacy::logException)

        updateGUI()
        preferences.put(BooleanNonKey.ObjectivesLoopUsed, true)
    }

    override fun onPause() {
        super.onPause()
        disposable.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        handler.looper.quitSafely()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding?.swipeRefresh?.setOnRefreshListener(null)
        _binding = null
    }

    @Synchronized
    fun updateGUI() {
        if (_binding == null) return
        loop.lastRun?.let {
            setInteractiveText(binding.summaryDecision, HtmlHelper.fromHtml(buildLoopDecisionSummary(it)))
            setInteractiveText(binding.summaryExecution, HtmlHelper.fromHtml(buildLoopExecutionSummary(it)))
            setInteractiveText(binding.request, it.request?.resultAsSpanned() ?: "")
            setInteractiveText(binding.constraintsprocessed, it.constraintsProcessed?.resultAsSpanned() ?: "")
            setInteractiveText(binding.source, it.source ?: "")
            binding.lastrun.text = dateUtil.dateAndTimeString(it.lastAPSRun)
            setInteractiveText(binding.smbrequestTime, dateUtil.dateAndTimeAndSecondsString(it.lastSMBRequest))
            setInteractiveText(binding.smbexecutionTime, dateUtil.dateAndTimeAndSecondsString(it.lastSMBEnact))
            setInteractiveText(binding.tbrrequestTime, dateUtil.dateAndTimeAndSecondsString(it.lastTBRRequest))
            setInteractiveText(binding.tbrexecutionTime, dateUtil.dateAndTimeAndSecondsString(it.lastTBREnact))

            setInteractiveText(
                binding.tbrsetbypump,
                it.tbrSetByPump?.let { tbrSetByPump -> HtmlHelper.fromHtml(tbrSetByPump.toHtml(rh, decimalFormatter)) } ?: ""
            )
            setInteractiveText(
                binding.smbsetbypump,
                it.smbSetByPump?.let { smbSetByPump -> HtmlHelper.fromHtml(smbSetByPump.toHtml(rh, decimalFormatter)) } ?: ""
            )

            var constraints =
                it.constraintsProcessed?.let { constraintsProcessed ->
                    val allConstraints = ConstraintObject(0.0, aapsLogger)
                    constraintsProcessed.rateConstraint?.let { rateConstraint -> allConstraints.copyReasons(rateConstraint) }
                    constraintsProcessed.smbConstraint?.let { smbConstraint -> allConstraints.copyReasons(smbConstraint) }
                    allConstraints.getMostLimitedReasons()
                } ?: ""
            constraints += loop.closedLoopEnabled?.getReasons() ?: ""
            setInteractiveText(binding.constraints, constraints)
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun clearGUI() {
        binding.request.text = ""
        binding.constraints.text = ""
        binding.constraintsprocessed.text = ""
        binding.source.text = ""
        binding.lastrun.text = ""
        binding.smbrequestTime.text = ""
        binding.smbexecutionTime.text = ""
        binding.tbrrequestTime.text = ""
        binding.tbrexecutionTime.text = ""
        binding.tbrsetbypump.text = ""
        binding.smbsetbypump.text = ""
        binding.summaryDecision.text = ""
        binding.summaryExecution.text = ""
        binding.swipeRefresh.isRefreshing = false
    }

    private fun buildLoopDecisionSummary(lastRun: Loop.LastRun): String {
        val requested = lastRun.request
        val afterConstraints = lastRun.constraintsProcessed
        val keyConstraint = buildString {
            append(afterConstraints?.let { processed ->
                val allConstraints = ConstraintObject(0.0, aapsLogger)
                processed.rateConstraint?.let { rateConstraint -> allConstraints.copyReasons(rateConstraint) }
                processed.smbConstraint?.let { smbConstraint -> allConstraints.copyReasons(smbConstraint) }
                allConstraints.getMostLimitedReasons()
            }.orEmpty())
            append(loop.closedLoopEnabled?.getMostLimitedReasons().orEmpty())
        }.ifBlank { "Сильных ограничений не видно." }

        return buildString {
            append("<b>Что хотел алгоритм</b><br>")
            append("Источник APS: ${lastRun.source ?: "—"}")
            append("<br>")
            append("До ограничений: ${describeResult(requested)}")
            append("<br>")
            append("После ограничений: ${describeResult(afterConstraints)}")
            append("<br><br>")
            append("<b>Ключевое ограничение</b><br>")
            append(keyConstraint.replace("\n", "<br>"))
        }
    }

    private fun buildLoopExecutionSummary(lastRun: Loop.LastRun): String =
        buildString {
            append("<b>Что ушло в помпу</b><br>")
            append("SMB запрос: ${formatDateTime(lastRun.lastSMBRequest)}")
            append(" | исполнен: ${formatDateTime(lastRun.lastSMBEnact)}")
            append("<br>")
            append("Временная базальная запрос: ${formatDateTime(lastRun.lastTBRRequest)}")
            append(" | исполнена: ${formatDateTime(lastRun.lastTBREnact)}")
            append("<br><br>")
            append("<b>Фактически установлено</b><br>")
            append("SMB помпой: ")
            append(lastRun.smbSetByPump?.let { HtmlHelper.fromHtml(it.toHtml(rh, decimalFormatter)).toString() } ?: "нет данных")
            append("<br>")
            append("Временная базальная помпой: ")
            append(lastRun.tbrSetByPump?.let { HtmlHelper.fromHtml(it.toHtml(rh, decimalFormatter)).toString() } ?: "нет данных")
        }

    private fun describeResult(result: app.aaps.core.interfaces.aps.APSResult?): String {
        if (result == null) return "нет данных"
        val parts = mutableListOf<String>()
        if (result.isTempBasalRequested) {
            parts += String.format(Locale.US, "базал %.2f Е/ч на %d мин", result.rate, result.duration)
        } else {
            parts += "временная базальная не меняется"
        }
        if (result.smb > 0.0) parts += String.format(Locale.US, "SMB %.2f Е", result.smb)
        if (result.carbsReq > 0) parts += "углеводы ${result.carbsReq} г"
        return parts.joinToString(", ")
    }

    private fun formatDateTime(value: Long): String =
        if (value <= 0L) "не было"
        else dateUtil.dateAndTimeAndSecondsString(value)

    private data class GlossaryDefinition(
        val title: String,
        val body: String
    )

    private val glossary by lazy {
        linkedMapOf(
            "Что хотел алгоритм" to GlossaryDefinition(
                "Что хотел алгоритм",
                "Это краткий итог решения APS до и после ограничений. Здесь видно, что именно предлагал алгоритм и как это изменилось после safety и общих лимитов."
            ),
            "Источник APS" to GlossaryDefinition(
                "Источник APS",
                "Какой APS-плагин дал этот расчёт: AIMI, SMB, AutoISF и т.п. Это помогает понять, чья логика сейчас управляла циклом."
            ),
            "Источник" to GlossaryDefinition(
                "Источник APS",
                "Алгоритм, который сформировал предложение цикла."
            ),
            "До ограничений" to GlossaryDefinition(
                "До ограничений",
                "Что запросил алгоритм до применения ограничений и safety-фильтров цикла."
            ),
            "После ограничений" to GlossaryDefinition(
                "После ограничений",
                "Что осталось от исходного запроса после применения ограничений, safety-логики и лимитов closed loop."
            ),
            "Ключевое ограничение" to GlossaryDefinition(
                "Ключевое ограничение",
                "Самая важная причина, которая сейчас ограничила или изменила исходный запрос APS."
            ),
            "Что ушло в помпу" to GlossaryDefinition(
                "Что ушло в помпу",
                "Этот блок показывает не теорию, а фактический путь команды до помпы: когда запрос был создан и когда был реально исполнен."
            ),
            "SMB запрос" to GlossaryDefinition(
                "SMB запрос",
                "Время, когда цикл сформировал запрос на микроболюс."
            ),
            "исполнен" to GlossaryDefinition(
                "Исполнен",
                "Момент, когда соответствующая команда действительно была выполнена, а не только рассчитана."
            ),
            "Временная базальная запрос" to GlossaryDefinition(
                "Временная базальная запрос",
                "Время, когда цикл запросил установку временной базальной скорости."
            ),
            "исполнена" to GlossaryDefinition(
                "Исполнена",
                "Момент, когда временная базальная скорость была реально применена."
            ),
            "Фактически установлено" to GlossaryDefinition(
                "Фактически установлено",
                "Что помпа реально приняла и установила. Это самый важный блок для проверки, совпало ли предложение цикла с фактическим действием."
            ),
            "SMB помпой" to GlossaryDefinition(
                "SMB помпой",
                "Фактический SMB, который был установлен/подан помпой. Это уже не просто запрос цикла, а реальный результат исполнения."
            ),
            "Временная базальная помпой" to GlossaryDefinition(
                "Временная базальная помпой",
                "Фактическая временная базальная скорость, которую получила и установила помпа."
            ),
            "Запрос" to GlossaryDefinition(
                "Запрос",
                "Человеко-читаемое описание предложения цикла. Здесь видно, что хотел сделать алгоритм на этом шаге."
            ),
            "После наложенных ограничений" to GlossaryDefinition(
                "После наложенных ограничений",
                "Версия запроса после того, как цикл применил лимиты и safety-фильтры. Это уже ближе к тому, что реально могло уйти в помпу."
            ),
            "ограничения" to GlossaryDefinition(
                "Ограничения",
                "Список причин, по которым система ослабила, отменила или изменила исходный запрос."
            ),
            "Система ИПЖ" to GlossaryDefinition(
                "Система ИПЖ",
                "Активный APS-движок, который сейчас участвует в замкнутом цикле."
            ),
            "SMB" to GlossaryDefinition(
                "SMB",
                "Super Micro Bolus — маленький автоматический болюс, который цикл может запросить для более быстрой коррекции."
            ),
            "базал" to GlossaryDefinition(
                "Базал",
                "Базальная подача инсулина. Во временной форме цикл может ускорять или замедлять её на заданный интервал."
            ),
            "углеводы" to GlossaryDefinition(
                "углеводы",
                "Если цикл считает, что нужен приём углеводов, он может показать это как часть предложения."
            ),
            "не было" to GlossaryDefinition(
                "не было",
                "По этому действию в текущем цикле не было запроса или не было факта исполнения."
            ),
            "Предыдущее выполнение" to GlossaryDefinition(
                "Предыдущее выполнение",
                "Время последнего завершённого цикла, на которое сейчас опирается экран."
            ),
            "Краткий итог цикла" to GlossaryDefinition(
                "Краткий итог цикла",
                "Сжатое объяснение того, что хотел алгоритм, что ограничило это решение и что реально ушло в помпу."
            ),
            "OpenAPS SMB" to GlossaryDefinition(
                "OpenAPS SMB",
                "Алгоритм семейства SMB. Если это значение стоит в источнике APS, значит расчёт пришёл из соответствующего APS-плагина."
            )
        )
    }

    private fun setupSectionDefinitionClicks() {
        setSectionHelp(binding.summaryTitle, "Краткий итог цикла", "Это верхний блок для быстрого разбора: что запросил алгоритм, что ограничило запрос и что реально ушло в помпу.")
        setSectionHelp(binding.labelLastRun, "Предыдущее выполнение", "Время последнего завершённого цикла.")
        setSectionHelp(binding.labelSource, "Система ИПЖ", "Какой APS-движок сформировал текущий результат цикла.")
        setSectionHelp(binding.labelRequest, "Запрос", "Исходный запрос алгоритма до исполнения помпой.")
        setSectionHelp(binding.labelConstraintsProcessed, "После наложенных ограничений", "Та же команда, но уже после применения ограничений и safety-фильтров.")
        setSectionHelp(binding.labelConstraints, "Ограничения", "Причины, по которым цикл урезал или изменил запрос.")
        setSectionHelp(binding.labelTbrRequestTime, "Время запроса временной базальной скорости", "Когда цикл отправил запрос на временный базал.")
        setSectionHelp(binding.labelTbrExecutionTime, "Время выполнения временной базальной скорости", "Когда этот запрос реально был исполнен.")
        setSectionHelp(binding.labelTbrSetByPump, "Временный базал задан помпой", "Какой временный базал реально установила помпа.")
        setSectionHelp(binding.labelSmbRequestTime, "Время запроса микроболюса SMB", "Когда цикл запросил SMB.")
        setSectionHelp(binding.labelSmbExecutionTime, "Время выполнения микроболюса SMB", "Когда SMB реально был исполнен.")
        setSectionHelp(binding.labelSmbSetByPump, "Супер микро болюс SMB задан помпой", "Какой SMB реально приняла и выполнила помпа.")
    }

    private fun setSectionHelp(view: TextView, title: String, message: String) {
        view.setOnClickListener {
            if (context != null) OKDialog.show(requireContext(), title, message)
        }
        view.paintFlags = view.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
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
