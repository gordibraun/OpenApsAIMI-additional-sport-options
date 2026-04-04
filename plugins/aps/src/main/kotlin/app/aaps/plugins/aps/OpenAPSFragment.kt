// Alexey added поменял файл так чтобы отображение на вкладке AIMI 3.3 было более удобоваримым
// Файл для редактирования отображения AIMI вкладки для анализа рзультатов работы системы

package app.aaps.plugins.aps

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.text.Spanned
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
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
import app.aaps.core.utils.HtmlHelper
import app.aaps.plugins.aps.databinding.OpenapsFragmentBinding
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import org.apache.commons.lang3.ClassUtils
import javax.inject.Inject
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
            binding.lastrun.text = rh.gs(R.string.executing)
            handler.post { activePlugin.activeAPS.invoke("OpenAPS swipe refresh", false) }
        }
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        menu.add(Menu.FIRST, ID_MENU_RUN, 0, rh.gs(R.string.openapsma_run))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        MenuCompat.setGroupDividerEnabled(menu, true)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            ID_MENU_RUN -> {
                binding.lastrun.text = rh.gs(R.string.executing)
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

            // ---------- Result ----------
            // каждое поле Result с новой строки,
            // внутри reason — разбивка по пунктам
            binding.result.text = lastAPSResult.rawData().resultDataToHtml()

            // ---------- Request / Reason ----------
            val rawRequest = lastAPSResult.resultAsSpanned().toString()
            binding.request.text = HtmlHelper.fromHtml(
                "<br>" + rawRequest.formatAimiLineBreaks()   // новая строка после "Request :"
            )

            binding.glucosestatus.text =
                lastAPSResult.glucoseStatus?.dataClassToHtml(
                    listOf("glucose", "delta", "shortAvgDelta", "longAvgDelta")
                )
            binding.currenttemp.text = lastAPSResult.currentTemp?.dataClassToHtml()
            binding.iobdata.text =
                rh.gs(R.string.array_of_elements, lastAPSResult.iobData?.size) +
                    "\n" + lastAPSResult.iob?.dataClassToHtml()
            binding.profile.text =
                lastAPSResult.oapsProfile?.dataClassToHtml()
                    ?: lastAPSResult.oapsProfileAutoIsf?.dataClassToHtml()
            binding.mealdata.text = lastAPSResult.mealData?.dataClassToHtml()

            // ---------- Script debug ----------
            val scriptHtml = lastAPSResult.scriptDebug
                ?.joinToString(separator = "<br><br>") { line ->
                    line.formatAimiLineBreaks()
                }
                ?: ""

            binding.scriptdebugdata.text = HtmlHelper.fromHtml(
                "<br>$scriptHtml"          // новая строка после "Script debug :"
            )

            // ---------- Constraints ----------
            binding.constraints.text = lastAPSResult.inputConstraints
                ?.getReasons()
                ?.let { HtmlHelper.fromHtml(it.formatAimiLineBreaks()) }

            binding.autosensdata.text = lastAPSResult.autosensResult?.dataClassToHtml()
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
        binding.swipeRefresh.isRefreshing = false
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
