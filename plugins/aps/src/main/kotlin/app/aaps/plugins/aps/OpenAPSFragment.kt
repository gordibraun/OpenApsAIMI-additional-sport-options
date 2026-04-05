// Alexey added поменял файл так чтобы отображение на вкладке AIMI 3.3 было более удобоваримым
// Файл для редактирования отображения AIMI вкладки для анализа рзультатов работы системы

package app.aaps.plugins.aps

import android.annotation.SuppressLint
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
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.utils.HtmlHelper
import app.aaps.plugins.aps.databinding.OpenapsFragmentBinding
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import org.apache.commons.lang3.ClassUtils
import javax.inject.Inject
import java.util.Locale
import kotlin.reflect.full.declaredMemberProperties

class OpenAPSFragment : DaggerFragment(), MenuProvider {

    private var disposable: CompositeDisposable = CompositeDisposable()

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var dateUtil: DateUtil

    @Suppress("PrivatePropertyName")
    private val ID_MENU_RUN = 503

    private var _binding: OpenapsFragmentBinding? = null
    private var handler = Handler(
        HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper
    )

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        OpenapsFragmentBinding.inflate(inflater, container, false).also {
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
            handler.post { activePlugin.activeAPS.invoke("OpenAPS swipe refresh", false) }
        }

        setupSectionDefinitionClicks()
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        menu.add(Menu.FIRST, ID_MENU_RUN, 0, rh.gs(R.string.openapsma_run))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        MenuCompat.setGroupDividerEnabled(menu, true)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            ID_MENU_RUN -> {
                handler.post { activePlugin.activeAPS.invoke("OpenAPS menu", false) }
                true
            }

            else -> false
        }

    @Synchronized
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

    @Synchronized
    override fun onPause() {
        super.onPause()
        disposable.clear()
        handler.removeCallbacksAndMessages(null)
    }

    @Synchronized
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        handler.looper.quitSafely()
    }

    @Synchronized
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    @SuppressLint("SetTextI18n")
    @Synchronized
    private fun updateGUI() {
        if (_binding == null) return
        val openAPSPlugin = activePlugin.activeAPS
        openAPSPlugin.lastAPSResult?.let { lastAPSResult ->
            setInteractiveText(binding.summaryMain, HtmlHelper.fromHtml(buildAimiSummaryMain(lastAPSResult)))
            setInteractiveText(binding.summaryDecision, HtmlHelper.fromHtml(buildAimiSummaryDecision(lastAPSResult)))

            // ---------- Result ----------
            // каждое поле Result с новой строки,
            // внутри reason — разбивка по пунктам
            setInteractiveText(binding.result, lastAPSResult.rawData().resultDataToHtml())

            // ---------- Request / Reason ----------
            val rawRequest = lastAPSResult.resultAsSpanned().toString()
            setInteractiveText(
                binding.request,
                HtmlHelper.fromHtml("<br>" + rawRequest.formatAimiLineBreaks())
            )

            setInteractiveText(
                binding.glucosestatus,
                lastAPSResult.glucoseStatus?.dataClassToHtml(
                    listOf("glucose", "delta", "shortAvgDelta", "longAvgDelta")
                ) ?: ""
            )
            setInteractiveText(binding.currenttemp, lastAPSResult.currentTemp?.dataClassToHtml() ?: "")
            setInteractiveText(
                binding.iobdata,
                SpannableStringBuilder()
                    .append(rh.gs(R.string.array_of_elements, lastAPSResult.iobData?.size))
                    .append("\n")
                    .append(lastAPSResult.iob?.dataClassToHtml() ?: "")
            )
            setInteractiveText(
                binding.profile,
                lastAPSResult.oapsProfile?.dataClassToHtml()
                    ?: lastAPSResult.oapsProfileAutoIsf?.dataClassToHtml()
                    ?: ""
            )
            setInteractiveText(binding.mealdata, lastAPSResult.mealData?.dataClassToHtml() ?: "")

            // ---------- Script debug ----------
            val scriptHtml = lastAPSResult.scriptDebug
                ?.joinToString(separator = "<br><br>") { line ->
                    line.formatAimiLineBreaks()
                }
                ?: ""

            setInteractiveText(
                binding.scriptdebugdata,
                HtmlHelper.fromHtml("<br>$scriptHtml")
            )

            // ---------- Constraints ----------
            setInteractiveText(
                binding.constraints,
                lastAPSResult.inputConstraints
                    ?.getReasons()
                    ?.let { HtmlHelper.fromHtml(it.formatAimiLineBreaks()) }
                    ?: ""
            )

            setInteractiveText(binding.autosensdata, lastAPSResult.autosensResult?.dataClassToHtml() ?: "")
            binding.lastrun.text = dateUtil.dateAndTimeString(lastAPSResult.date)
        }
        binding.swipeRefresh.isRefreshing = false
    }

    @Synchronized
    private fun resetGUI(text: String) {
        if (_binding == null) return
        binding.result.text = text
        binding.glucosestatus.text = ""
        binding.currenttemp.text = ""
        binding.iobdata.text = ""
        binding.profile.text = ""
        binding.mealdata.text = ""
        binding.autosensdata.text = ""
        binding.scriptdebugdata.text = ""
        binding.request.text = ""
        binding.lastrun.text = ""
        binding.summaryMain.text = ""
        binding.summaryDecision.text = ""
        binding.swipeRefresh.isRefreshing = false
    }

    private fun buildAimiSummaryMain(lastAPSResult: app.aaps.core.interfaces.aps.APSResult): String {
        val glucoseStatus = lastAPSResult.glucoseStatus
        val mealData = lastAPSResult.mealData
        val raw = lastAPSResult.rawData() as? app.aaps.core.interfaces.aps.RT

        val finalPrediction = raw?.eventualBG
        val predicted = raw?.predictedBG ?: finalPrediction
        val safetyMin = raw?.minGuardBG ?: listOfNotNull(glucoseStatus?.glucose, predicted, finalPrediction).minOrNull()

        return buildString {
            append("<b>Состояние прямо сейчас</b><br>")
            append("Глюкоза: ${formatMgdl(glucoseStatus?.glucose)} мг/дл")
            append(" (${formatDelta(glucoseStatus?.delta)} за 5 мин)")
            append("<br>")
            append("Короткий тренд: ${formatDelta(glucoseStatus?.shortAvgDelta)}")
            append(" | Длинный тренд: ${formatDelta(glucoseStatus?.longAvgDelta)}")
            append("<br><br>")
            append("<b>Что AIMI учитывает</b><br>")
            append("Активный инсулин (IOB): ${formatUnits(lastAPSResult.iob?.iob)} Е")
            append("<br>")
            append("Остаток углеводов (COB): ${formatNumber(mealData?.mealCOB)} г")
            append("<br>")
            append("Цель глюкозы: ${formatMgdl(lastAPSResult.targetBG)} мг/дл")
            append("<br><br>")
            append("<b>Фин. прогноз AIMI</b><br>")
            append("Конечная точка прогноза: ${formatMgdl(finalPrediction)} мг/дл")
            append("<br>")
            append("Predicted BG для safety: ${formatMgdl(predicted)} мг/дл")
            append("<br>")
            append("Минимум для safety: ${formatMgdl(safetyMin)} мг/дл")
            if (raw?.predBGs?.AIMI_FINAL?.isNotEmpty() == true) {
                append("<br>")
                append("Точек в прогнозе: ${raw.predBGs?.AIMI_FINAL?.size ?: 0}")
            }
        }
    }

    private fun buildAimiSummaryDecision(lastAPSResult: app.aaps.core.interfaces.aps.APSResult): String {
        val raw = lastAPSResult.rawData() as? app.aaps.core.interfaces.aps.RT
        val safetyLabel = detectSafetyLabel(lastAPSResult, raw)

        return buildString {
            append("<b>Решение по инсулину</b><br>")
            append("Запрошенный SMB: ${formatUnits(lastAPSResult.smb)} Е")
            append("<br>")
            append("Запрошено инсулина всего: ${formatUnits(raw?.insulinReq)} Е")
            append("<br>")
            append("Временная базальная: ${formatTempBasal(lastAPSResult.rate, lastAPSResult.duration)}")
            append("<br>")
            append("Что значит «Запрошено»: это то, что AIMI попросил сделать в этом расчёте до фактического исполнения помпой.")
            append("<br><br>")
            append("<b>Защита и ограничения</b><br>")
            append(safetyLabel)
            if (lastAPSResult.carbsReq > 0) {
                append("<br>")
                append("Требуются углеводы: ${lastAPSResult.carbsReq} г за ${lastAPSResult.carbsReqWithin} мин")
            }
        }
    }

    private fun detectSafetyLabel(
        lastAPSResult: app.aaps.core.interfaces.aps.APSResult,
        raw: app.aaps.core.interfaces.aps.RT?
    ): String {
        val reason = lastAPSResult.reason
        val constraints = lastAPSResult.inputConstraints?.getReasons()
        val combined = listOfNotNull(reason, constraints).joinToString("\n")
        val mechanism = raw?.safetyMechanism
        val minGuard = raw?.minGuardBG
        val threshold = raw?.hypoThreshold
        val bg = lastAPSResult.glucoseStatus?.glucose ?: raw?.bg
        val predicted = raw?.predictedBG ?: raw?.eventualBG
        val eventual = raw?.eventualBG

        return when {
            mechanism == "Hypo guard + safety margin" ||
                combined.contains("Hypo protection", ignoreCase = true) ||
                combined.contains("Hypo guard", ignoreCase = true) ->
                buildString {
                    append("Активный механизм: Hypo guard + safety margin")
                    append("<br>")
                    append("Минимум для решения: ${formatMgdl(minGuard)} мг/дл")
                    append(" | Порог остановки: ${formatMgdl(threshold)} мг/дл")
                    append("<br>")
                    append("Сравнение safety: BG=${formatMgdl(bg)}, predicted=${formatMgdl(predicted)}, eventual=${formatMgdl(eventual)}")
                    append("<br>")
                    append("Результат механизма: SMB блокируется или режется до безопасного уровня.")
                }
            mechanism?.contains("Hyper fallback", ignoreCase = true) == true ||
                combined.contains("Hyper fallback", ignoreCase = true) ->
                buildString {
                    append("Активный механизм: Hyper fallback")
                    append("<br>")
                    append("Что делает: разрешает SMB даже при спорном прогнозе, но с демпфированием.")
                    append("<br>")
                    append("Сравнение safety: BG=${formatMgdl(bg)}, predicted=${formatMgdl(predicted)}, eventual=${formatMgdl(eventual)}")
                }
            combined.contains("critical safety", ignoreCase = true) ->
                "Активный механизм: критическое ограничение безопасности.<br>Система дополнительно режет дозу поверх обычного прогноза."
            combined.contains("capped", ignoreCase = true) ||
                combined.contains("clamp", ignoreCase = true) ->
                "Активный механизм: ограничение дозы (cap/clamp).<br>Запрошенная доза была урезана safety-механизмом."
            constraints?.isNotBlank() == true ->
                "Активный механизм: явный safety override не найден.<br>" + constraints.replace("\n", "<br>")
            else ->
                "Активный механизм: явный safety override не найден.<br>По последнему расчёту система не сообщила об отдельной защите сверх обычного прогноза."
        }
    }

    private fun formatMgdl(value: Double?): String =
        value?.let { String.format(Locale.US, "%.0f", it) } ?: "—"

    private fun formatNumber(value: Double?): String =
        value?.let { String.format(Locale.US, "%.1f", it) } ?: "—"

    private fun formatUnits(value: Double?): String =
        value?.let { String.format(Locale.US, "%.2f", it) } ?: "0.00"

    private fun formatDelta(value: Double?): String =
        value?.let { String.format(Locale.US, "%+.1f", it) } ?: "—"

    private fun formatTempBasal(rate: Double, duration: Int): String =
        if (rate < 0 || duration < 0) "без запроса"
        else String.format(Locale.US, "%.2f Е/ч на %d мин", rate, duration)

    private data class GlossaryDefinition(
        val title: String,
        val body: String
    )

    private val glossary by lazy {
        linkedMapOf(
            "Глюкоза" to GlossaryDefinition(
                "Глюкоза",
                "Текущее значение сенсора в этот момент. Это исходная точка расчёта AIMI: от неё алгоритм оценивает тренд, риск гипо и необходимость инсулина."
            ),
            "glucose" to GlossaryDefinition(
                "glucose",
                "Текущее значение сенсора. Если оно быстро меняется, само число ещё не всё объясняет, поэтому ниже всегда смотрят и на delta, и на прогноз."
            ),
            "delta" to GlossaryDefinition(
                "delta",
                "Изменение глюкозы за последние 5 минут. Положительное delta означает рост, отрицательное — падение. Это один из самых быстрых индикаторов текущего направления."
            ),
            "shortAvgDelta" to GlossaryDefinition(
                "shortAvgDelta",
                "Короткий усреднённый тренд. Он сглаживает одиночный скачок и показывает, как сахар ведёт себя на коротком интервале, а не только в одной точке."
            ),
            "longAvgDelta" to GlossaryDefinition(
                "longAvgDelta",
                "Длинный усреднённый тренд. Он помогает понять, это краткий всплеск или уже устойчивое движение."
            ),
            "Короткий тренд" to GlossaryDefinition(
                "Короткий тренд",
                "Быстрый усреднённый тренд по глюкозе. Он полезен, чтобы не реагировать на один случайный шум, а видеть более устойчивое изменение."
            ),
            "Длинный тренд" to GlossaryDefinition(
                "Длинный тренд",
                "Более медленный усреднённый тренд. Он показывает инерцию движения и помогает отделить краткий шум от настоящего изменения."
            ),
            "IOB" to GlossaryDefinition(
                "IOB",
                "Insulin On Board — активный инсулин, который ещё не доработал. Чем он выше, тем сильнее алгоритм ожидает дальнейшее влияние вниз, если другие факторы не перевесят."
            ),
            "COB" to GlossaryDefinition(
                "COB",
                "Carbs On Board — углеводы, которые система считает ещё не полностью отыгравшими. Это не просто введённые углеводы, а остаток предполагаемого влияния еды."
            ),
            "Цель глюкозы" to GlossaryDefinition(
                "Цель глюкозы",
                "Тот уровень, к которому AIMI старается вести сахар с учётом профиля и временных целей."
            ),
            "Конечная точка прогноза" to GlossaryDefinition(
                "Конечная точка прогноза",
                "Это eventualBG — дальняя финальная точка траектории, которую сейчас ожидает AIMI по своей модели."
            ),
            "eventualBG" to GlossaryDefinition(
                "eventualBG",
                "Дальняя конечная точка прогноза. Это не решение помпы, а ожидаемый уровень глюкозы в конце текущей траектории модели."
            ),
            "Predicted BG для safety" to GlossaryDefinition(
                "Predicted BG для safety",
                "Операционный прогноз, который участвует в защитной логике. Сейчас в вашей реализации AIMI он часто совпадает с eventualBG, потому что код пока присваивает ему это же значение."
            ),
            "predictedBG" to GlossaryDefinition(
                "predictedBG",
                "Прогнозное значение BG, которое используется в safety-проверках. Сейчас в текущем AIMI-коде оно часто равно eventualBG, поэтому на экране они могут совпадать."
            ),
            "Predicted BG" to GlossaryDefinition(
                "Predicted BG",
                "Прогнозное значение глюкозы, используемое в правилах безопасности и части решений. В вашей текущей ветке оно часто совпадает с eventualBG."
            ),
            "Минимум для safety" to GlossaryDefinition(
                "Минимум для safety",
                "Это минимальное значение из текущего BG, predicted BG и eventualBG. Именно его safety-логика использует как самый опасный сигнал при проверке риска гипо."
            ),
            "minGuardBG" to GlossaryDefinition(
                "minGuardBG",
                "Число, которое safety-логика использует как минимальную и самую опасную точку среди нескольких оценок глюкозы."
            ),
            "Запрошенный SMB" to GlossaryDefinition(
                "Запрошенный SMB",
                "Размер микроболюса, который AIMI хотел подать в этом расчёте до фактического исполнения помпой и до возможных ограничений на уровне цикла."
            ),
            "SMB" to GlossaryDefinition(
                "SMB",
                "Super Micro Bolus — маленький болюс, который алгоритм может запросить автоматически. Это инструмент более быстрой коррекции по сравнению с одной лишь временной базальной."
            ),
            "Запрошено инсулина всего" to GlossaryDefinition(
                "Запрошено инсулина всего",
                "Общий расчётный запрос по инсулину для этого цикла. Это логическая потребность модели, а не обязательно ровно то количество, которое реально ушло в помпу."
            ),
            "insulinReq" to GlossaryDefinition(
                "insulinReq",
                "Сколько инсулина модель считает нужным запросить по текущей ситуации. Потом этот запрос может быть ограничен safety-логикой, clamp-правилами и ограничениями цикла."
            ),
            "Временная базальная" to GlossaryDefinition(
                "Временная базальная",
                "Запрошенная временная скорость базала и её длительность. Это второй основной инструмент воздействия вместе с SMB."
            ),
            "rate" to GlossaryDefinition(
                "rate",
                "Скорость временного базала в единицах в час."
            ),
            "duration" to GlossaryDefinition(
                "duration",
                "Длительность временной базальной скорости в минутах."
            ),
            "Что значит «Запрошено»" to GlossaryDefinition(
                "Что значит «Запрошено»",
                "Запрошено — это то, что AIMI предложил в рамках расчёта. Реально исполненное действие может отличаться после ограничений цикла, связи с помпой и safety-проверок."
            ),
            "Hypo guard" to GlossaryDefinition(
                "Hypo guard",
                "Hypo guard — это защита от подачи лишнего инсулина, когда система видит риск гипогликемии. Она смотрит не только на текущее BG, а на несколько сигналов сразу: текущее BG, predicted BG и eventualBG. Если самый опасный из них уже слишком низок или слишком близок к опасной зоне, SMB блокируется или резко урезается."
            ),
            "safety margin" to GlossaryDefinition(
                "safety margin",
                "Safety margin — это дополнительный запас безопасности поверх обычного порога. Идея в том, чтобы не ждать, пока система формально войдёт в гипо, а начать осторожничать заранее, если траектория уже идёт к опасной зоне."
            ),
            "Hypo guard + safety margin" to GlossaryDefinition(
                "Hypo guard + safety margin",
                "Это совместная защита от гипо. Сначала система находит самую опасную оценку глюкозы: минимум из BG, predicted BG и eventualBG. Затем сравнивает её с защитным порогом. Если минимум уже ниже или слишком близок к порогу, алгоритм блокирует SMB или режет инсулин до безопасного уровня, даже если часть остальных сигналов выглядит лучше."
            ),
            "Минимум для решения" to GlossaryDefinition(
                "Минимум для решения",
                "Самая низкая оценка глюкозы, которую система взяла для safety-проверки. Именно это число используется как главный аргумент при защите от гипо."
            ),
            "Порог остановки" to GlossaryDefinition(
                "Порог остановки",
                "Защитный уровень, ниже которого система считает подачу инсулина рискованной. Если минимум для safety оказывается ниже этого порога, включается защита."
            ),
            "hypoThreshold" to GlossaryDefinition(
                "hypoThreshold",
                "Защитный порог для Hypo guard. Это не просто пользовательская цель, а порог, по которому система решает, можно ли безопасно продолжать подачу инсулина."
            ),
            "Сравнение safety" to GlossaryDefinition(
                "Сравнение safety",
                "Показывает, какие именно три числа safety сравнивает между собой: текущее BG, predicted BG и eventualBG."
            ),
            "BG" to GlossaryDefinition(
                "BG",
                "Blood Glucose — глюкоза крови в терминах алгоритма. В интерфейсе обычно это текущее сенсорное значение."
            ),
            "predicted" to GlossaryDefinition(
                "predicted",
                "Прогнозное значение BG для защитной логики. В вашей текущей реализации AIMI оно часто совпадает с eventual."
            ),
            "eventual" to GlossaryDefinition(
                "eventual",
                "Дальняя конечная точка траектории, которую ждёт AIMI."
            ),
            "Hyper fallback" to GlossaryDefinition(
                "Hyper fallback",
                "Запасной режим на случай, когда прогноз спорный или недостаточно надёжен, но рост и контекст всё же требуют реакции. Он может разрешить SMB, но обычно с демпфированием, то есть осторожнее, чем основной расчёт."
            ),
            "Требуются углеводы" to GlossaryDefinition(
                "Требуются углеводы",
                "Это предупреждение, что система считает полезным принять углеводы в ближайшее время, чтобы не уйти слишком низко."
            ),
            "Точек в прогнозе" to GlossaryDefinition(
                "Точек в прогнозе",
                "Количество точек в линии Фин. прогноз AIMI. Обычно каждая точка соответствует шагу прогноза вперёд по времени."
            ),
            "constraints" to GlossaryDefinition(
                "Ограничения",
                "Это причины, по которым цикл или safety-слой ограничили запрос AIMI: лимиты, выключенные режимы, защитные условия и другие фильтры."
            ),
            "Состояние гликемии" to GlossaryDefinition(
                "Состояние гликемии",
                "Базовый блок о текущем сахаре и его направлении: текущее значение, delta и усреднённые тренды."
            ),
            "Текущий врем базал" to GlossaryDefinition(
                "Текущий временный базал",
                "То, какая временная базальная скорость сейчас уже активна на стороне системы."
            ),
            "Данные IOB (активн инс)" to GlossaryDefinition(
                "Данные IOB",
                "Подробности об активном инсулине: сколько его ещё действует и какой эффект от него ожидается."
            ),
            "Профиль" to GlossaryDefinition(
                "Профиль",
                "Набор параметров, на которых AIMI считает: ISF, CR, DIA, цели, базал и связанные настройки."
            ),
            "Данные приема пищи" to GlossaryDefinition(
                "Данные приема пищи",
                "Информация о еде в расчёте: COB, отклонения, признаки усвоения и всё, что связано с влиянием углеводов."
            ),
            "данные autosens" to GlossaryDefinition(
                "Данные autosens",
                "Как autosens сейчас оценивает чувствительность по сравнению с профилем. Это влияет на агрессивность расчётов, если соответствующие функции активны."
            ),
            "Отладка скрипта" to GlossaryDefinition(
                "Отладка скрипта",
                "Подробный внутренний лог AIMI. Он полезен для глубокого разбора, когда нужно понять, какие ветки логики реально сработали."
            ),
            "Результат" to GlossaryDefinition(
                "Результат",
                "Сырые итоговые поля последнего AIMI-расчёта. Это самый полный технический снимок решения."
            ),
            "запрос" to GlossaryDefinition(
                "Запрос",
                "Человеко-читаемое описание того, что AIMI попросил сделать: SMB, temp basal, carbsReq и текст причины."
            ),
            "reason" to GlossaryDefinition(
                "reason",
                "Текстовая причина решения. Здесь AIMI обычно кратко перечисляет, какие ветки логики и safety-условия сработали."
            ),
            "bg" to GlossaryDefinition(
                "bg",
                "Текущее значение глюкозы, на котором строился расчёт."
            ),
            "carbsReq" to GlossaryDefinition(
                "carbsReq",
                "Сколько углеводов система рекомендует принять для предотвращения гипо."
            ),
            "carbsReqWithin" to GlossaryDefinition(
                "carbsReqWithin",
                "За какой интервал времени система рекомендует принять эти углеводы."
            ),
            "deliverAt" to GlossaryDefinition(
                "deliverAt",
                "Время, к которому относится запрос на микроболюс."
            ),
            "isHypoRisk" to GlossaryDefinition(
                "isHypoRisk",
                "Внутренний флаг, что AIMI видит риск гипогликемии."
            ),
            "variable_sens" to GlossaryDefinition(
                "variable_sens",
                "Переменная чувствительность к инсулину, которую AIMI использовал в конкретном расчёте."
            ),
            "sensitivityRatio" to GlossaryDefinition(
                "sensitivityRatio",
                "Коэффициент изменения чувствительности относительно базового профиля."
            ),
            "PKPD" to GlossaryDefinition(
                "PKPD",
                "PKPD-модель — это часть AIMI, которая прогнозирует поведение глюкозы через модель действия инсулина и других факторов во времени: как эффект набирается, достигает пика и затухает."
            ),
            "DIA" to GlossaryDefinition(
                "DIA",
                "Duration of Insulin Action — длительность действия инсулина в модели."
            ),
            "Peak" to GlossaryDefinition(
                "Peak",
                "Момент максимального действия инсулина в модели."
            ),
            "Tail" to GlossaryDefinition(
                "Tail",
                "Хвост действия инсулина, то есть как долго после пика остаётся остаточный эффект."
            ),
            "Activity" to GlossaryDefinition(
                "Activity",
                "Оценка активности инсулина в текущий момент. Чем она выше, тем сильнее модель ожидает движение вниз от инсулина."
            ),
            "TDD" to GlossaryDefinition(
                "TDD",
                "Total Daily Dose — суточная доза инсулина. В AIMI она может использоваться для динамической настройки чувствительности."
            ),
            "UAM" to GlossaryDefinition(
                "UAM",
                "Unannounced Meal — логика реагирования на рост, похожий на еду, когда углеводы введены не полностью или не введены вообще."
            ),
            "MaxIOB" to GlossaryDefinition(
                "MaxIOB",
                "Максимум активного инсулина, который алгоритм считает допустимым для автоматической подачи."
            ),
            "MaxSMB" to GlossaryDefinition(
                "MaxSMB",
                "Максимальный размер одного микроболюса, который AIMI может запросить в данном контексте."
            ),
            "tick" to GlossaryDefinition(
                "tick",
                "Короткое текстовое представление текущего изменения BG, которое часто используется в логах и статусных строках."
            ),
            "aimilog" to GlossaryDefinition(
                "aimilog",
                "Технический внутренний лог AIMI. Обычно полезен, когда нужно глубоко разбирать логику ветвлений и ограничений."
            ),
            "algorithm" to GlossaryDefinition(
                "algorithm",
                "Какой APS-алгоритм сформировал текущий результат."
            )
        )
    }

    private fun setupSectionDefinitionClicks() {
        setSectionHelp(binding.summaryTitle, "Краткий итог AIMI", "Это верхний сжатый блок для быстрого понимания, что AIMI видит, что прогнозирует и что просит сделать по инсулину прямо сейчас.")
        setSectionHelp(binding.labelLastRun, "Предыдущее выполнение", "Время последнего завершённого AIMI-расчёта. На это время можно опираться как на последний реально готовый результат.")
        setSectionHelp(binding.labelInputParametersHeader, "Параметры ввода", "Ниже идут входные данные и подробные технические блоки, на которых AIMI основывал расчёт. Ничего не скрыто: можно сверять summary сверху с сырыми данными ниже.")
        setSectionHelp(binding.labelConstraints, "Ограничения", "Здесь собраны причины, по которым safety и общие ограничения системы могли ослабить или заблокировать решение AIMI.")
        setSectionHelp(binding.labelGlucoseStatus, "Состояние гликемии", "Текущее значение сахара и тренды, с которых AIMI начинает оценку ситуации.")
        setSectionHelp(binding.labelCurrentTemp, "Текущий временный базал", "Какой временный базал уже активен в системе на момент расчёта.")
        setSectionHelp(binding.labelIobData, "Данные IOB", "Подробности об активном инсулине и его ожидаемом остаточном эффекте.")
        setSectionHelp(binding.labelProfile, "Профиль", "Параметры профиля, которые использовались в расчёте: чувствительность, углеводный коэффициент, цели, базал и связанные настройки.")
        setSectionHelp(binding.labelMealData, "Данные приёма пищи", "Всё, что AIMI знает о еде и её остаточном влиянии: COB, отклонения и meal-сигналы.")
        setSectionHelp(binding.labelAutosensData, "Данные autosens", "Оценка текущей чувствительности по сравнению с профилем. Это может менять агрессивность расчёта.")
        setSectionHelp(binding.labelScriptDebug, "Отладка скрипта", "Самый подробный внутренний лог AIMI. Здесь удобно искать, какая ветка логики реально сработала.")
        setSectionHelp(binding.labelResultHeader, "Результат AIMI", "Ниже идут сырые поля результата. Это техническая версия того, что summary сверху уже пересказал простыми словами.")
        setSectionHelp(binding.labelResult, "Результат", "Сырый снимок последнего AIMI-расчёта: числовые поля и текстовая причина решения.")
        setSectionHelp(binding.labelRequest, "Запрос", "Человеко-читаемое описание того, что AIMI попросил сделать в этом расчёте. Это запрос алгоритма, а не обязательно факт исполнения помпой.")
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

    // ====== ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ ======

    // исходная версия dataClassToHtml() БЕЗ параметров — нужна для currentTemp, mealData и т.п.
    private fun Any.dataClassToHtml(): Spanned =
        HtmlHelper.fromHtml(
            StringBuilder().also { sb ->
                this::class.declaredMemberProperties.forEach { property ->
                    property.call(this)?.let { value ->
                        if (ClassUtils.isPrimitiveOrWrapper(value::class.java)) {
                            sb.append(property.name.bold(), ": ", value, br)
                        }
                        if (value is StringBuilder) {
                            sb.append(property.name.bold(), ": ", value.toString(), br)
                        }
                    }
                }
            }.toString()
        )

    private fun Any.dataClassToHtml(properties: List<String>): Spanned =
        HtmlHelper.fromHtml(
            StringBuilder().also { sb ->
                properties.forEach { property ->
                    this::class.declaredMemberProperties
                        .firstOrNull { it.name == property }
                        ?.call(this)
                        ?.let { value ->
                            if (ClassUtils.isPrimitiveOrWrapper(value::class.java)) {
                                sb.append(property.bold(), ": ", value, br)
                            }
                            if (value is StringBuilder) {
                                sb.append(property.bold(), ": ", value.toString(), br)
                            }
                        }
                }
            }.toString()
        )

    // Специально для блока Result:
    //  - каждое поле с новой строки;
    //  - поле "reason" разбивается на пункты через formatAimiLineBreaks().
    private fun Any.resultDataToHtml(): Spanned =
        HtmlHelper.fromHtml(
            StringBuilder().also { sb ->
                this::class.declaredMemberProperties.forEach { property ->
                    property.call(this)?.let { value ->
                        val rendered = if (property.name.equals("reason", ignoreCase = true)) {
                            value.toString().formatAimiLineBreaks()
                        } else {
                            value.toString()
                        }

                        if (ClassUtils.isPrimitiveOrWrapper(value::class.java) ||
                            value is StringBuilder ||
                            value is String
                        ) {
                            // Alexey: локализуем имя поля
                            sb.append(mapFieldName(property.name).bold(), ": ", rendered, br)
                        }
                    }
                }
            }.toString()
        )

    // Локализация названий полей Result
    // Локализация названий полей Result
    private fun mapFieldName(field: String): String =
        when (field) {
            "COB"            -> rh.gs(R.string.field_COB)
            "IOB"            -> rh.gs(R.string.field_IOB)
            "aimilog"        -> rh.gs(R.string.field_aimilog)
            "bg"             -> rh.gs(R.string.field_bg)
            "carbsReq"       -> rh.gs(R.string.field_carbsReq)
            "carbsReqWithin" -> rh.gs(R.string.field_carbsReqWithin)
            "deliverAt"      -> rh.gs(R.string.field_deliverAt)
            "duration"       -> rh.gs(R.string.field_duration)
            "eventualBG"     -> rh.gs(R.string.field_eventualBG)
            "insulinReq"     -> rh.gs(R.string.field_insulinReq)
            "isHypoRisk"     -> rh.gs(R.string.field_isHypoRisk)
            "rate"           -> rh.gs(R.string.field_rate)
            "reason"         -> rh.gs(R.string.field_reason)
            else             -> field   // всё, что не замапили, показываем как есть
        }

    private fun String.bold(): String = "<b>$this</b>"
    private val br = "<br>"

    // Локализация терминов внутри reason / script debug / request
    // Локализация терминов внутри reason / script debug / request
    private fun String.localizeAimiTerms(): String =
        this
            // общий заголовок
            .replace("Adjustments", rh.gs(R.string.aimi_term_adjustments))
            .replace("MaxIob", rh.gs(R.string.aimi_term_maxiob))
            .replace("MaxIOB", rh.gs(R.string.aimi_term_maxiob))
            .replace("MaxSMB", rh.gs(R.string.aimi_term_maxsmb))

            // UAM
            .replace("UAM model", rh.gs(R.string.aimi_term_uam_model))
            .replace("Model loaded", rh.gs(R.string.aimi_term_model_loaded))
            .replace("UAM executed", rh.gs(R.string.aimi_term_uam_executed))
            .replace("SMB (UAM)", rh.gs(R.string.aimi_term_smb_uam))

            // модели
            .replace("MPC predictive model", rh.gs(R.string.aimi_term_mpc_predictive_model))
            .replace("PI physiological model", rh.gs(R.string.aimi_term_pi_physiological_model))
            .replace("MPC utile", rh.gs(R.string.aimi_term_mpc_utile))

            // SMB / PKPD блок — только метки, чтобы не ломать формат
            .replace("Final SMB", rh.gs(R.string.aimi_term_final_smb))
            .replace("PKPD:", rh.gs(R.string.aimi_term_pkpd) + ":")
            .replace("DIA=", rh.gs(R.string.aimi_term_dia) + "=")
            .replace("Peak=", rh.gs(R.string.aimi_term_peak) + "=")
            .replace("Tail=", rh.gs(R.string.aimi_term_tail) + "=")
            .replace("Activity=", rh.gs(R.string.aimi_term_activity) + "=")
            .replace("anticip=", rh.gs(R.string.aimi_term_anticip) + "=")
            .replace("fresh=", rh.gs(R.string.aimi_term_fresh) + "=")
            .replace("ISF(fused)=", rh.gs(R.string.aimi_term_isf_fused) + "=")
            .replace("profile=", rh.gs(R.string.aimi_term_profile) + "=")
            .replace("TDD=", rh.gs(R.string.aimi_term_tdd) + "=")
            .replace("scale=", rh.gs(R.string.aimi_term_scale) + "=")
            .replace("SMB: proposed=", "SMB: " + rh.gs(R.string.aimi_term_proposed) + "=")
            .replace("damped=", rh.gs(R.string.aimi_term_damped) + "=")
            .replace("quantized=", rh.gs(R.string.aimi_term_quantized) + "=")

            // реактивность
            .replace("Reactivity factor", rh.gs(R.string.aimi_term_reactivity_factor))
            .replace("Reactivity", rh.gs(R.string.aimi_term_reactivity_factor))

            // автодрайв и режимы
            .replace("Autodrive conditions", rh.gs(R.string.aimi_term_autodrive_conditions))
            .replace("Autodrive", rh.gs(R.string.aimi_term_autodrive))
            .replace("Snack/prebolus mode", rh.gs(R.string.aimi_term_snack_prebolus_mode))

            // тренды и статистика
            .replace("BG Trend", rh.gs(R.string.aimi_term_bg_trend))
            .replace("Combined \u0394", rh.gs(R.string.aimi_term_combined_delta))
            .replace("Predicted BG", rh.gs(R.string.aimi_term_predicted_bg))
            .replace("Accel.", rh.gs(R.string.aimi_term_accel))
            .replace("Slope Min Dev.", rh.gs(R.string.aimi_term_slope_min_dev))
            .replace("TIR:", rh.gs(R.string.aimi_term_tir) + ":")
            .replace("Calculation Dynamic PeakTime", rh.gs(R.string.aimi_term_calc_dynamic_peaktime))
            .replace("Profile peak", rh.gs(R.string.aimi_term_profile_peak))

    /**
     * Разбивает длинные строки AIMI на пункты:
     *  - разделители: любой вариант "|" с пробелами/без
     *    и любой вариант "•" с пробелами/без;
     *  - каждый пункт с новой строки, с "• " в начале,
     *    между пунктами — пустая строка.
     */
    /**
     * Разбивает длинные строки AIMI на пункты:
     *  - разделители: любой вариант "|" с пробелами/без
     *    и любой вариант "•" с пробелами/без;
     *  - каждый пункт с новой строки, с "• " в начале,
     *    между пунктами — пустая строка;
     *  - для PKPD делает заголовок + подпункты без буллетов.
     */
    /**
     * Разбивает длинные строки AIMI на пункты:
     *  - сначала вставляет разделители " | " перед ключевыми фрагментами
     *    в исходной (английской) строке;
     *  - потом локализует термины;
     *  - затем режет по "•" и "|", каждый пункт с новой строки ("• " в начале);
     *  - для PKPD выводит заголовок + параметры отдельными строками.
     */
    private fun String.formatAimiLineBreaks(): String =
        this
            .trim()
            // ВСТАВЛЯЕМ РАЗДЕЛИТЕЛИ В СЫРОЙ (АНГЛИЙСКИЙ) ТЕКСТ
            // блок MaxSMB / UAM
            .replace(" UAM model", " | UAM model")
            .replace(" Model loaded", " | Model loaded")
            .replace(" UAM executed", " | UAM executed")
            .replace(" Hypo protection + safety margin", " | Hypo protection + safety margin")
            .replace(" MPC predictive model", " | MPC predictive model")

            // PI / MPC utile / safety / SMB / PKPD
            .replace(" PI physiological model", " | PI physiological model")
            .replace(" MPC utile", " | MPC utile")
            .replace(" Safety condition", " | Safety condition")
            .replace(" Final SMB", " | Final SMB")
            .replace(" PKPD", " | PKPD")

            // ЛОКАЛИЗУЕМ ВСЕ ТЕРМИНЫ (MPC, PI, PKPD, UAM и т.п.)
            .localizeAimiTerms()
            .trim()

            // нормализуем "•" и "|" к единому разделителю " | "
            .replace(Regex("\\s*•\\s*"), " | ")
            .replace(Regex("\\s*\\|\\s*"), " | ")

            // режем на части
            .split(" | ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

            // собираем HTML: пункты + спец-формат PKPD
            .joinToString(separator = "<br><br>") { part ->
                // Спец-формат для PKPD-блока:
                // "PKPD (фармакокинетика/...): DIA=..., Peak=..., Tail=..., ..."
                if (part.startsWith("PKPD")) {
                    val colonIndex = part.indexOf(':')
                    if (colonIndex != -1) {
                        val header = part.substring(0, colonIndex).trim()
                        val rest = part.substring(colonIndex + 1).trim()
                        val params = rest
                            .split(',')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }

                        if (params.isNotEmpty()) {
                            // • PKPD(...):
                            // Длительность...
                            // Пик...
                            "• $header:<br>" + params.joinToString("<br>") { it }
                        } else {
                            "• $header"
                        }
                    } else {
                        "• $part"
                    }
                } else {
                    // Обычные пункты
                    if (part.startsWith("•")) part else "• $part"
                }
            }
}
