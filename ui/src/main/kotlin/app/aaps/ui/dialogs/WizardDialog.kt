package app.aaps.ui.dialogs

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import androidx.fragment.app.FragmentManager
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.AimiMealAssist
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAutosensCalculationFinished
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.interfaces.utils.SafeParse
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.formatColor
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.extensions.valueToUnits
import app.aaps.core.objects.forecast.ForecastCarbsCalculator
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.objects.wizard.BolusWizard
import app.aaps.core.ui.extensions.runOnUiThread
import app.aaps.core.ui.extensions.toVisibility
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.utils.HtmlHelper
import app.aaps.ui.R
import app.aaps.ui.databinding.DialogWizardBinding
import dagger.android.support.DaggerDialogFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.text.DecimalFormat
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min


class WizardDialog : DaggerDialogFragment() {

    private val isfDecisionColor = Color.rgb(186, 104, 200)
    private val isfCarbsColor = Color.rgb(255, 214, 0)
    private val isfDecisionColorHtml = "#BA68C8"
    private val isfCarbsColorHtml = "#FFD600"

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var ctx: Context
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var decimalFormatter: DecimalFormatter
    @Inject lateinit var bolusWizardProvider: Provider<BolusWizard>
    @Inject lateinit var overviewData: OverviewData
    @Inject lateinit var config: Config
    @Inject lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Inject lateinit var loop: Loop
    @Inject lateinit var aimiMealAssist: AimiMealAssist
    private val handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)

    private var queryingProtection = false
    private var wizard: BolusWizard? = null
    private var calculatedPercentage = 100
    private var calculatedCorrection = 0.0
    private var usePercentage = false
    private var carbsPassedIntoWizard = 0.0
    private var notesPassedIntoWizard = ""
    private var okClicked: Boolean = false // one shot guards
    private var lastForecastRequiredCarbsOverviewRefresh: Int? = null
    private var disposable: CompositeDisposable = CompositeDisposable()
    private var bolusStep = 0.0
    private var _binding: DialogWizardBinding? = null
    private var suppressFoodTypeCallbacks = false
    private var aimiDetailsExpanded = false

    // This property is only valid between onCreateView and onDestroyView.
    private val binding get() = _binding!!

    private val textWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable) {}
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            calculateInsulin()
        }
    }

    private val timeTextWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable) {
            _binding?.let { binding ->
                binding.alarm.isChecked = binding.carbTimeInput.value > 0
            }
        }

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            calculateInsulin()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        aapsLogger.debug(LTag.APS, "Dialog opened: ${this.javaClass.simpleName}")
    }

    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        savedInstanceState.putDouble("bg_input", binding.bgInput.value)
        savedInstanceState.putDouble("carbs_input", binding.carbsInput.value)
        savedInstanceState.putDouble("correction_input", binding.correctionInput.value)
        savedInstanceState.putDouble("carb_time_input", binding.carbTimeInput.value)
        savedInstanceState.putString("food_type", currentSelectedFoodType())
        savedInstanceState.putBoolean("aimi_details_expanded", aimiDetailsExpanded)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        this.arguments?.let { bundle ->
            carbsPassedIntoWizard = bundle.getDouble("carbs_input")
            notesPassedIntoWizard = bundle.getString("notes_input") ?: ""
        }

        dialog?.window?.requestFeature(Window.FEATURE_NO_TITLE)
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        isCancelable = true
        dialog?.setCanceledOnTouchOutside(false)

        _binding = DialogWizardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        aimiDetailsExpanded = savedInstanceState?.getBoolean("aimi_details_expanded") ?: false
        loadCheckedStates()
        processCobCheckBox()
        val useSuperBolus = preferences.get(BooleanKey.OverviewUseSuperBolus)
        binding.sbCheckbox.visibility = useSuperBolus.toVisibility()
        binding.superBolusRow.visibility = useSuperBolus.toVisibility()
        binding.notesLayout.root.visibility = preferences.get(BooleanKey.OverviewShowNotesInDialogs).toVisibility()

        val maxCarbs = constraintChecker.getMaxCarbsAllowed().value()
        val maxCorrection = constraintChecker.getMaxBolusAllowed().value()
        bolusStep = activePlugin.activePump.pumpDescription.bolusStep

        if (profileFunction.getUnits() == GlucoseUnit.MGDL) {
            binding.bgInput.setParams(
                savedInstanceState?.getDouble("bg_input")
                    ?: 0.0, 0.0, 500.0, 1.0, DecimalFormat("0"), false, binding.okcancel.ok, timeTextWatcher
            )
        } else {
            binding.bgInput.setParams(
                savedInstanceState?.getDouble("bg_input")
                    ?: 0.0, 0.0, 30.0, 0.1, DecimalFormat("0.0"), false, binding.okcancel.ok, textWatcher
            )
        }
        binding.carbsInput.setParams(
            savedInstanceState?.getDouble("carbs_input")
                ?: 0.0, 0.0, maxCarbs.toDouble(), 1.0, DecimalFormat("0"), false, binding.okcancel.ok, textWatcher
        )

        // If there is no BG using % lower that 100% leads to high BGs
        // because loop doesn't add missing insulin
        var percentage = preferences.get(IntKey.OverviewBolusPercentage)
        val time = preferences.get(IntKey.OverviewResetBolusPercentageTime).toLong()
        persistenceLayer.getLastGlucoseValue().let {
            // if last value is older or there is no bg
            if (it != null) {
                if (it.timestamp < dateUtil.now() - T.mins(time).msecs())
                    percentage = 100
            } else percentage = 100
        }

        if (usePercentage) {
            calculatedPercentage = percentage
            binding.correctionInput.setParams(calculatedPercentage.toDouble(), 10.0, 200.0, 5.0, DecimalFormat("0"), false, binding.okcancel.ok, textWatcher)
            binding.correctionInput.value = calculatedPercentage.toDouble()
            binding.correctionUnit.text = "%"
        } else {
            binding.correctionInput.setParams(
                savedInstanceState?.getDouble("correction_input")
                    ?: 0.0,
                -maxCorrection,
                maxCorrection,
                bolusStep,
                decimalFormatter.pumpSupportedBolusFormat(activePlugin.activePump.pumpDescription.bolusStep),
                false,
                binding.okcancel.ok,
                textWatcher
            )
            binding.correctionUnit.text = rh.gs(app.aaps.core.ui.R.string.insulin_unit_shortname)
        }
        binding.carbTimeInput.setParams(
            savedInstanceState?.getDouble("carb_time_input")
                ?: 0.0, -1440.0, 1440.0, 5.0, DecimalFormat("0"), false, binding.okcancel.ok, timeTextWatcher
        )
        handler.post { initDialog() }
        calculatedPercentage = preferences.get(IntKey.OverviewBolusPercentage)
        binding.percentUsed.text = rh.gs(app.aaps.core.ui.R.string.format_percent, calculatedPercentage)
        binding.percentUsed.visibility = (calculatedPercentage != 100 || usePercentage).toVisibility()
        // ok button
        binding.okcancel.ok.setOnClickListener {
            if (carbsNeedFoodType()) {
                ToastUtils.warnToast(ctx, "Выбери тип углеводов")
                calculateInsulin()
                return@setOnClickListener
            }
            if (okClicked) {
                aapsLogger.debug(LTag.UI, "guarding: ok already clicked")
            } else {
                okClicked = true
                calculateInsulin()
                context?.let { context ->
                    wizard?.confirmAndExecute(context)
                }
                aapsLogger.debug(LTag.APS, "Dialog ok pressed: ${this.javaClass.simpleName}")
            }
            dismiss()
        }
        binding.bgCheckboxIcon.setOnClickListener { binding.bgCheckbox.isChecked = !binding.bgCheckbox.isChecked }
        binding.ttCheckboxIcon.setOnClickListener { binding.ttCheckbox.isChecked = !binding.ttCheckbox.isChecked }
        binding.trendCheckboxIcon.setOnClickListener { binding.bgTrendCheckbox.isChecked = !binding.bgTrendCheckbox.isChecked }
        binding.cobCheckboxIcon.setOnClickListener { binding.cobCheckbox.isChecked = !binding.cobCheckbox.isChecked; processCobCheckBox(); }
        binding.iobCheckboxIcon.setOnClickListener { binding.iobCheckbox.isChecked = !binding.iobCheckbox.isChecked; processIobCheckBox(); }
        // cancel button
        binding.okcancel.cancel.setOnClickListener {
            aapsLogger.debug(LTag.APS, "Dialog canceled: ${this.javaClass.simpleName}")
            dismiss()
        }
        // checkboxes
        binding.bgCheckbox.setOnCheckedChangeListener(::onCheckedChanged)
        binding.ttCheckbox.setOnCheckedChangeListener(::onCheckedChanged)
        binding.cobCheckbox.setOnCheckedChangeListener(::onCheckedChanged)
        binding.iobCheckbox.setOnCheckedChangeListener(::onCheckedChanged)
        binding.bgTrendCheckbox.setOnCheckedChangeListener(::onCheckedChanged)
        binding.sbCheckbox.setOnCheckedChangeListener(::onCheckedChanged)

        val showCalc = preferences.get(BooleanKey.WizardCalculationVisible)
        binding.delimiter.visibility = showCalc.toVisibility()
        binding.result.visibility = showCalc.toVisibility()
        binding.calculationCheckbox.isChecked = showCalc
        binding.calculationCheckbox.setOnCheckedChangeListener { _, isChecked ->
            run {
                preferences.put(BooleanKey.WizardCalculationVisible, isChecked)
                binding.delimiter.visibility = isChecked.toVisibility()
                binding.result.visibility = isChecked.toVisibility()
                processEnabledIcons()
            }
        }

        processEnabledIcons()

        binding.correctionPercent.setOnCheckedChangeListener { _, isChecked ->
            run {
                preferences.put(BooleanKey.WizardCorrectionPercent, isChecked)
                binding.correctionUnit.text = if (isChecked) "%" else rh.gs(app.aaps.core.ui.R.string.insulin_unit_shortname)
                usePercentage = binding.correctionPercent.isChecked
                if (usePercentage) {
                    binding.correctionInput.setParams(calculatedPercentage.toDouble(), 10.0, 200.0, 5.0, DecimalFormat("0"), false, binding.okcancel.ok, textWatcher)
                    binding.correctionInput.customContentDescription = rh.gs(R.string.a11_correction_percentage)
                } else {
                    binding.correctionInput.setParams(
                        savedInstanceState?.getDouble("correction_input")
                            ?: 0.0, -maxCorrection, maxCorrection, bolusStep, decimalFormatter.pumpSupportedBolusFormat(activePlugin.activePump.pumpDescription.bolusStep), false, binding.okcancel.ok,
                        textWatcher
                    )
                    binding.correctionInput.customContentDescription = rh.gs(R.string.a11_correction_units)
                }
                binding.correctionInput.updateA11yDescription()
                binding.correctionInput.value = if (usePercentage) calculatedPercentage.toDouble() else Round.roundTo(calculatedCorrection, bolusStep)
            }
        }
        // profile
        binding.profileList.onItemClickListener = AdapterView.OnItemClickListener { _, _, _, _ -> calculateInsulin() }
        // bus
        disposable += rxBus
            .toObservable(EventAutosensCalculationFinished::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ calculateInsulin() }, fabricPrivacy::logException)
        setA11yLabels()
        binding.wizardTitle.paintFlags = binding.wizardTitle.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        binding.foodTypeTitle.paintFlags = binding.foodTypeTitle.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        binding.foodTypeTitle.setOnClickListener {
            OKDialog.show(
                requireContext(),
                rh.gs(R.string.food_type_label),
                "Тип еды влияет на carb-модель AIMI. Быстрые углеводы всасываются рано и bolus считается осторожнее, обычная еда оставляет базовый режим, медленная растягивает вклад еды."
            )
        }
        binding.wizardAimiLogic.setOnClickListener {
            OKDialog.show(
                requireContext(),
                "AIMI в Мастере Болюса",
                "AIMI использует ГК, углеводы, время углеводов, target, IC, ISF, COB, IOB, тренд и выбранный тип еды. Тип еды влияет на bolus через carb factor, prebolus и прогнозную проверку, а также на форму carb-кривой."
            )
        }
        binding.wizardAimiLogicToggle.setOnClickListener {
            aimiDetailsExpanded = !aimiDetailsExpanded
            updateAimiDetailsVisibility()
        }
        updateAimiDetailsVisibility()
        setupFoodTypeCheckboxes(savedInstanceState?.getString("food_type"))
    }

    private fun setA11yLabels() {
        binding.bgInputLabel.labelFor = binding.bgInput.editTextId
        binding.carbsInputLabel.labelFor = binding.carbsInput.editTextId
        binding.correctionInputLabel.labelFor = binding.correctionInput.editTextId
        binding.carbTimeInputLabel.labelFor = binding.carbTimeInput.editTextId
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        disposable.clear()
        handler.removeCallbacksAndMessages(null)
        handler.looper.quitSafely()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun onCheckedChanged(buttonView: CompoundButton, @Suppress("unused") state: Boolean) {
        saveCheckedStates()
        binding.ttCheckbox.isEnabled = binding.bgCheckbox.isChecked && persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now()) != null
        binding.ttCheckboxIcon.visibility = binding.ttCheckbox.isEnabled.toVisibility()
        if (buttonView.id == binding.cobCheckbox.id)
            processCobCheckBox()
        if (buttonView.id == binding.iobCheckbox.id)
            processIobCheckBox()
        processEnabledIcons()
        calculateInsulin()
    }

    private fun processCobCheckBox() {
        if (binding.cobCheckbox.isChecked) {
            binding.iobCheckbox.isChecked = true
        }
    }

    private fun processIobCheckBox() {
        if (!binding.iobCheckbox.isChecked) {
            binding.cobCheckbox.isChecked = false
        }
    }

    private fun processEnabledIcons() {
        binding.bgCheckboxIcon.isChecked = binding.bgCheckbox.isChecked
        binding.ttCheckboxIcon.isChecked = binding.ttCheckbox.isChecked
        binding.trendCheckboxIcon.isChecked = binding.bgTrendCheckbox.isChecked
        binding.iobCheckboxIcon.isChecked = binding.iobCheckbox.isChecked
        binding.cobCheckboxIcon.isChecked = binding.cobCheckbox.isChecked

        binding.bgCheckboxIcon.alpha = if (binding.bgCheckbox.isChecked) 1.0f else 0.2f
        binding.ttCheckboxIcon.alpha = if (binding.ttCheckbox.isChecked) 1.0f else 0.2f
        binding.trendCheckboxIcon.alpha = if (binding.bgTrendCheckbox.isChecked) 1.0f else 0.2f
        binding.iobCheckboxIcon.alpha = if (binding.iobCheckbox.isChecked) 1.0f else 0.2f
        binding.cobCheckboxIcon.alpha = if (binding.cobCheckbox.isChecked) 1.0f else 0.2f

        binding.bgCheckboxIcon.visibility = binding.calculationCheckbox.isChecked.not().toVisibility()
        binding.ttCheckboxIcon.visibility = (binding.calculationCheckbox.isChecked.not() && binding.ttCheckbox.isEnabled).toVisibility()
        binding.trendCheckboxIcon.visibility = binding.calculationCheckbox.isChecked.not().toVisibility()
        binding.iobCheckboxIcon.visibility = binding.calculationCheckbox.isChecked.not().toVisibility()
        binding.cobCheckboxIcon.visibility = binding.calculationCheckbox.isChecked.not().toVisibility()
        binding.checkboxRow.visibility = binding.calculationCheckbox.isChecked.not().toVisibility()
    }

    private fun saveCheckedStates() {
        preferences.put(BooleanKey.WizardIncludeCob, binding.cobCheckbox.isChecked)
        preferences.put(BooleanKey.WizardIncludeTrend, binding.bgTrendCheckbox.isChecked)
        preferences.put(BooleanKey.WizardCorrectionPercent, binding.correctionPercent.isChecked)
    }

    private fun loadCheckedStates() {
        binding.bgTrendCheckbox.isChecked = preferences.get(BooleanKey.WizardIncludeTrend)
        binding.cobCheckbox.isChecked = preferences.get(BooleanKey.WizardIncludeCob)
        usePercentage = preferences.get(BooleanKey.WizardCorrectionPercent)
        binding.correctionPercent.isChecked = usePercentage
    }

    private fun valueToUnitsToString(value: Double, units: String): String =
        if (units == GlucoseUnit.MGDL.asText) decimalFormatter.to0Decimal(value)
        else decimalFormatter.to1Decimal(value * Constants.MGDL_TO_MMOLL)

    private fun setupFoodTypeCheckboxes(savedFoodType: String?) {
        val listener = CompoundButton.OnCheckedChangeListener { button, isChecked ->
            if (suppressFoodTypeCallbacks) return@OnCheckedChangeListener
            if (isChecked) selectOnlyFoodType(button.id) else calculateInsulin()
        }
        binding.foodTypeFast.setOnCheckedChangeListener(listener)
        binding.foodTypeBalanced.setOnCheckedChangeListener(listener)
        binding.foodTypeSlow.setOnCheckedChangeListener(listener)
        restoreFoodTypeSelection(savedFoodType)
    }

    private fun selectOnlyFoodType(checkedId: Int) {
        suppressFoodTypeCallbacks = true
        binding.foodTypeFast.isChecked = checkedId == binding.foodTypeFast.id
        binding.foodTypeBalanced.isChecked = checkedId == binding.foodTypeBalanced.id
        binding.foodTypeSlow.isChecked = checkedId == binding.foodTypeSlow.id
        suppressFoodTypeCallbacks = false
        calculateInsulin()
    }

    private fun restoreFoodTypeSelection(savedFoodType: String?) {
        suppressFoodTypeCallbacks = true
        binding.foodTypeFast.isChecked = savedFoodType == "fast"
        binding.foodTypeBalanced.isChecked = savedFoodType == "balanced"
        binding.foodTypeSlow.isChecked = savedFoodType == "slow"
        suppressFoodTypeCallbacks = false
    }

    private fun currentSelectedFoodType(): String? =
        when {
            binding.foodTypeFast.isChecked -> "fast"
            binding.foodTypeSlow.isChecked -> "slow"
            binding.foodTypeBalanced.isChecked -> "balanced"
            else -> null
        }

    private fun currentSelectedFoodTypeLabel(): String =
        when (currentSelectedFoodType()) {
            "fast" -> rh.gs(R.string.food_type_fast)
            "slow" -> rh.gs(R.string.food_type_slow)
            "balanced" -> rh.gs(R.string.food_type_balanced)
            else -> "не выбран"
        }

    private fun carbsNeedFoodType(): Boolean =
        SafeParse.stringToInt(binding.carbsInput.text) > 0 && currentSelectedFoodType() == null

    private fun updateAimiDetailsVisibility() {
        _binding ?: return
        binding.wizardAimiLogic.visibility = aimiDetailsExpanded.toVisibility()
        binding.wizardAimiLogicToggle.text = rh.gs(if (aimiDetailsExpanded) R.string.aimi_details_hide else R.string.aimi_details_show)
    }

    private fun initDialog() {
        val profile = profileFunction.getProfile()
        val profileStore = activePlugin.activeProfileSource.profile
        val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())

        if (profile == null || profileStore == null) {
            ToastUtils.errorToast(ctx, app.aaps.core.ui.R.string.noprofile)
            dismiss()
            return
        }

        // IOB calculation
        val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()

        runOnUiThread {
            _binding ?: return@runOnUiThread
            if (carbsPassedIntoWizard != 0.0) {
                binding.carbsInput.value = carbsPassedIntoWizard
            }
            if (notesPassedIntoWizard.isNotBlank()) {
                binding.notesLayout.notes.setText(notesPassedIntoWizard)
            }

            val profileList: ArrayList<CharSequence> = profileStore.getProfileList()
            profileList.add(0, rh.gs(app.aaps.core.ui.R.string.active))
            context?.let { context ->
                binding.profileList.setAdapter(ArrayAdapter(context, app.aaps.core.ui.R.layout.spinner_centered, profileList))
                binding.profileList.setText(profileList[0], false)
            }

            val units = profileFunction.getUnits()
            binding.bgUnits.text = units.asText
            binding.bgInput.step = if (units == GlucoseUnit.MGDL) 1.0 else 0.1

            // Set BG if not old
            binding.bgInput.value = iobCobCalculator.ads.actualBg()?.valueToUnits(units) ?: 0.0

            binding.ttCheckbox.isEnabled = binding.bgCheckbox.isChecked && tempTarget != null
            binding.ttCheckbox.isChecked = binding.ttCheckbox.isEnabled
            binding.ttCheckboxIcon.visibility = binding.ttCheckbox.isEnabled.toVisibility()
            binding.iobInsulin.text = rh.gs(app.aaps.core.ui.R.string.format_insulin_units, -bolusIob.iob - basalIob.basaliob)

            calculateInsulin()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun calculateInsulin() {
        val profileStore = activePlugin.activeProfileSource.profile ?: return // not initialized yet
        var profileName = binding.profileList.text.toString()
        val specificProfile: Profile?
        if (profileName == rh.gs(app.aaps.core.ui.R.string.active)) {
            specificProfile = profileFunction.getProfile()
            profileName = profileFunction.getProfileName()
        } else
            specificProfile = profileStore.getSpecificProfile(profileName)?.let { ProfileSealed.Pure(it, activePlugin) }

        if (specificProfile == null) return

        // Entered values
        val usePercentage = binding.correctionPercent.isChecked
        var bg = SafeParse.stringToDouble(binding.bgInput.text)
        val carbs = SafeParse.stringToInt(binding.carbsInput.text)
        val correction = if (!usePercentage) {
            if (Round.roundTo(calculatedCorrection, bolusStep) == SafeParse.stringToDouble(binding.correctionInput.text))
                calculatedCorrection
            else
                SafeParse.stringToDouble(binding.correctionInput.text)
        } else
            0.0
        val percentageCorrection = if (usePercentage) {
            if (calculatedPercentage == SafeParse.stringToInt(binding.correctionInput.text))
                calculatedPercentage
            else
                SafeParse.stringToInt(binding.correctionInput.text)
        } else
            preferences.get(IntKey.OverviewBolusPercentage).toDouble()
        val carbsAfterConstraint = constraintChecker.applyCarbsConstraints(ConstraintObject(carbs, aapsLogger)).value()
        if (abs(carbs - carbsAfterConstraint) > 0.01) {
            binding.carbsInput.value = 0.0
            ToastUtils.warnToast(ctx, R.string.carbs_constraint_applied)
            return
        }

        bg = if (binding.bgCheckbox.isChecked) bg else 0.0
        val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())

        // COB
        var cob = 0.0
        if (binding.cobCheckbox.isChecked) {
            val cobInfo = iobCobCalculator.getCobInfo("Wizard COB")
            cobInfo.displayCob?.let { cob = it }
        }

        val carbTime = SafeParse.stringToInt(binding.carbTimeInput.text)
        val selectedFoodType = currentSelectedFoodType()
        val foodTypeMissing = carbsAfterConstraint > 0.0 && selectedFoodType == null
        val forecastRequiredCarbs = forecastRequiredCarbsFromFinalLine(specificProfile, tempTarget, binding.ttCheckbox.isChecked)
        val activityBolusContext = futureActivityBolusContext(dateUtil.now())
        refreshOverviewIfForecastCarbsChanged(forecastRequiredCarbs)

        wizard = bolusWizardProvider.get().doCalc(
            specificProfile, profileName, tempTarget, carbsAfterConstraint, cob, bg, correction, preferences.get(IntKey.OverviewBolusPercentage),
            binding.bgCheckbox.isChecked,
            binding.cobCheckbox.isChecked,
            binding.iobCheckbox.isChecked,
            binding.iobCheckbox.isChecked,
            binding.sbCheckbox.isChecked,
            binding.ttCheckbox.isChecked,
            binding.bgTrendCheckbox.isChecked,
            binding.alarm.isChecked,
            binding.notesLayout.notes.text.toString(),
            carbTime,
            selectedFoodType ?: "balanced",
            usePercentage = usePercentage,
            totalPercentage = percentageCorrection.toDouble(),
            forecastRequiredCarbs = forecastRequiredCarbs,
            activityNewInsulinFactor = activityBolusContext?.factor ?: 1.0,
            activityDescription = activityBolusContext?.description
        )

        wizard?.let { wizard ->
            updateCarbTimingHint(wizard, specificProfile, bg, carbsAfterConstraint.toInt(), carbTime, selectedFoodType)
            updateFutureActivityNote()
            binding.wizardAimiLogic.text = makeInteractiveGlossary(buildAimiLogicSummary(wizard, specificProfile, bg, carbsAfterConstraint, cob, carbTime))
            binding.wizardAimiLogic.movementMethod = LinkMovementMethod.getInstance()
            binding.wizardAimiLogic.highlightColor = Color.TRANSPARENT
            updateAimiDetailsVisibility()
            val carbsIsf = profileUtil.fromMgdlToUnits(
                specificProfile.getIsfMgdlForCarbs(dateUtil.now(), "Wizard visible carbs ISF", config, processedDeviceStatusData),
                profileFunction.getUnits()
            )
            binding.bg.text = coloredIsfText(
                rh.gs(R.string.format_bg_carbs_isf, valueToUnitsToString(profileUtil.convertToMgdl(bg, profileFunction.getUnits()), profileFunction.getUnits().asText), carbsIsf),
                isfCarbsColor
            )
            binding.bgInsulin.text = rh.gs(app.aaps.core.ui.R.string.format_insulin_units, wizard.insulinFromBG)

            binding.carbs.text = rh.gs(R.string.format_carbs_ic, carbs.toDouble(), wizard.ic)
            binding.carbsInsulin.text = rh.gs(app.aaps.core.ui.R.string.format_insulin_units, wizard.insulinFromCarbs)

            binding.iobInsulin.text = rh.gs(app.aaps.core.ui.R.string.format_insulin_units, -wizard.insulinFromBolusIOB - wizard.insulinFromBasalIOB)

            binding.correctionInsulin.text = rh.gs(app.aaps.core.ui.R.string.format_insulin_units, wizard.insulinFromCorrection)

            // Superbolus
            binding.sb.text = if (binding.sbCheckbox.isChecked) rh.gs(R.string.two_hours) else ""
            binding.sbInsulin.text = rh.gs(app.aaps.core.ui.R.string.format_insulin_units, wizard.insulinFromSuperBolus)

            // Trend
            if (binding.bgTrendCheckbox.isChecked && wizard.glucoseStatus != null) {
                binding.bgTrend.text = ((if (wizard.trend > 0) "+" else "")
                    + profileUtil.fromMgdlToStringInUnits(wizard.trend * 3)
                    + " " + profileFunction.getUnits())
            } else {
                binding.bgTrend.text = ""
            }
            binding.bgTrendInsulin.text = rh.gs(app.aaps.core.ui.R.string.format_insulin_units, wizard.insulinFromTrend)

            // COB
            if (binding.cobCheckbox.isChecked) {
                binding.cob.text = if (wizard.cobAlreadyHandledByAimi > 0.0) {
                    val handledLabel = if (wizard.cobAlreadyHandledByActivity > 0.0) "AIMI/нагрузка уже учли " else "AIMI уже учел "
                    rh.gs(R.string.format_cob_ic, wizard.cobUsedForInsulin, wizard.ic) +
                        " (" + handledLabel + decimalFormatter.to0Decimal(wizard.cobAlreadyHandledByAimi) + "г)"
                } else {
                    rh.gs(R.string.format_cob_ic, cob, wizard.ic)
                }
                binding.cobInsulin.text = rh.gs(app.aaps.core.ui.R.string.format_insulin_units, wizard.insulinFromCOB)
            } else {
                binding.cob.text = ""
                binding.cobInsulin.text = ""
            }

            if (wizard.calculatedTotalInsulin > 0.0 || carbsAfterConstraint > 0.0) {
                val insulinText =
                    if (wizard.calculatedTotalInsulin > 0.0) rh.gs(app.aaps.core.ui.R.string.format_insulin_units, wizard.calculatedTotalInsulin)
                        .formatColor(context, rh, app.aaps.core.ui.R.attr.bolusColor) else ""
                val carbsText = if (carbsAfterConstraint > 0.0) rh.gs(app.aaps.core.objects.R.string.format_carbs, carbsAfterConstraint).formatColor(
                    context, rh, app.aaps.core.ui.R.attr
                        .carbsColor
                ) else ""
                // Работаем со следующей строкой чтобы вывести на главное активити результат
                binding.total.text = HtmlHelper.fromHtml(rh.gs(R.string.result_insulin_carbs, insulinText, carbsText))
                if (wizard.calculatedTotalInsulin <= 0.0 && carbsAfterConstraint > 0.0 && wizard.insulinFromCarbs > 0.0) {
                    binding.totalReason.visibility = View.VISIBLE
                    binding.totalReason.text = rh.gs(
                        R.string.wizard_zero_bolus_reason,
                        rh.gs(app.aaps.core.ui.R.string.format_insulin_units, wizard.insulinFromCarbs)
                    )
                } else {
                    binding.totalReason.visibility = View.GONE
                }
                binding.okcancel.ok.visibility = View.VISIBLE
                binding.okcancel.ok.isEnabled = !foodTypeMissing
            } else {
                // Здесь Не хватает углеводов выводится
                binding.total.text = HtmlHelper.fromHtml(rh.gs(R.string.missing_carbs, wizard.forecastRequiredCarbs).formatColor(context, rh, app.aaps.core.ui.R.attr.carbsColor))
                binding.totalReason.visibility = View.GONE
                binding.okcancel.ok.visibility = View.INVISIBLE
                binding.okcancel.ok.isEnabled = false
            }
            binding.percentUsed.text = rh.gs(app.aaps.core.ui.R.string.format_percent, wizard.percentageCorrection)
            calculatedPercentage = wizard.calculatedPercentage
            calculatedCorrection = wizard.calculatedCorrection
        }

    }

    private fun updateCarbTimingHint(
        wizard: BolusWizard,
        profile: Profile,
        bg: Double,
        carbs: Int,
        selectedCarbTime: Int,
        selectedFoodType: String?
    ) {
        if (carbs > 0 && selectedFoodType == null) {
            binding.carbTimingHint.visibility = View.VISIBLE
            binding.carbTimingHint.text = "Выбери тип углеводов: быстрые, обычные или медленные"
            aapsLogger.debug(LTag.APS, "Подсказка времени углеводов: тип углеводов не выбран, OK заблокирован")
            return
        }
        val suggestion = carbTimingSuggestion(wizard, profile, bg, carbs, selectedCarbTime, selectedFoodType ?: "balanced")
        if (suggestion == null) {
            binding.carbTimingHint.visibility = View.GONE
            return
        }
        binding.carbTimingHint.visibility = View.VISIBLE
        binding.carbTimingHint.text = buildString {
            append(suggestion.actionText)
            if (suggestion.reasonText.isNotBlank()) {
                append("\n")
                append(suggestion.reasonText)
            }
        }
        aapsLogger.debug(
            LTag.APS,
            "Подсказка времени углеводов: ${suggestion.actionText}; ${suggestion.reasonText}; score=${"%.1f".format(suggestion.score)} " +
                "min=${"%.0f".format(suggestion.minBgMgdl)} max=${"%.0f".format(suggestion.maxBgMgdl)}"
        )
    }

    private fun forecastRequiredCarbsFromFinalLine(profile: Profile, tempTarget: TT?, useTT: Boolean): Int {
        val now = dateUtil.now()
        if (finalForecastPendingTreatmentRecalculation(now, "Wizard forecast carbs")) return 0
        val targetMgdl = forecastCarbsTargetMgdl(profile, tempTarget, useTT) ?: return 0
        val result = ForecastCarbsCalculator.fromFinalForecast(
            predictions = overviewData.finalAimiPredictionValues,
            now = now,
            targetMgdl = targetMgdl,
            isfMgdl = profile.getIsfMgdlForCarbs(now, "Wizard forecast carbs", config, processedDeviceStatusData),
            ic = profile.getIc()
        )
        val apsCarbsReq = loopForecastCarbsReq(now, "Wizard forecast carbs")
        val carbsReq = result?.carbs ?: apsCarbsReq
        aapsLogger.debug(
            LTag.APS,
            "Wizard forecast carbs from AIMI_FINAL: carbs=$carbsReq treatmentAware=true " +
                "graph=${result?.carbs ?: 0} aps=$apsCarbsReq " +
                "min=${result?.minBgMgdl?.let { "%.0f".format(it) } ?: "n/a"} " +
                "at=${result?.minMinutes ?: 0}m target=${"%.0f".format(targetMgdl)} " +
                "isfCarbs=${"%.1f".format(profile.getIsfMgdlForCarbs(now, "Wizard forecast carbs log", config, processedDeviceStatusData))}"
        )
        return carbsReq
    }

    private fun loopForecastCarbsReq(now: Long, caller: String): Int {
        var carbs = 0
        var within = 0
        var source = ""
        loop.lastRun?.let { lastRun ->
            if (now - lastRun.lastAPSRun <= T.mins(15).msecs()) {
                val result = lastRun.constraintsProcessed ?: lastRun.request
                val candidate = result?.carbsReq ?: 0
                if (candidate > carbs) {
                    carbs = candidate
                    within = result?.carbsReqWithin ?: 0
                    source = "loop"
                }
            }
        }
        val deviceTime = processedDeviceStatusData.openAPSData.clockSuggested
        if (deviceTime > 0L && now - deviceTime <= T.mins(15).msecs()) {
            val result = processedDeviceStatusData.getAPSResult()
            val candidate = result?.carbsReq ?: 0
            if (candidate > carbs) {
                carbs = candidate
                within = result?.carbsReqWithin ?: 0
                source = "deviceStatus"
            }
        }
        if (carbs <= 0) return 0
        aapsLogger.debug(
            LTag.APS,
            "$caller uses APS carbsReq candidate: carbs=$carbs within=${within}m source=$source"
        )
        return carbs
    }

    private fun finalForecastPendingTreatmentRecalculation(now: Long, caller: String): Boolean {
        val lastRunTime = loop.lastRun?.lastAPSRun ?: return false
        val lastCarbsChangeTime = persistenceLayer.getNewestCarbs()?.let { maxOf(it.timestamp, it.dateCreated) } ?: 0L
        val lastBolusChangeTime = persistenceLayer.getNewestBolus()?.let { maxOf(it.timestamp, it.dateCreated) } ?: 0L
        val lastAcceptedTreatmentTime = aimiMealAssist.lastTreatmentAcceptedAt()
        val lastActivityChangeTime = latestAimiActivityChangeTime(now)
        val latestTreatmentChangeTime = maxOf(lastCarbsChangeTime, lastBolusChangeTime, lastAcceptedTreatmentTime, lastActivityChangeTime)
        if (latestTreatmentChangeTime <= lastRunTime) return false
        aapsLogger.debug(
            LTag.APS,
            "$caller waits for treatment-aware APS recalculation: latestTreatment=${dateUtil.dateAndTimeString(latestTreatmentChangeTime)} " +
                "lastAPS=${dateUtil.dateAndTimeString(lastRunTime)} now=${dateUtil.dateAndTimeString(now)}"
        )
        return true
    }

    private fun updateFutureActivityNote() {
        val now = dateUtil.now()
        val note = futureActivityNote(now)
        if (note == null) {
            binding.futureActivityNote.visibility = View.GONE
        } else {
            binding.futureActivityNote.visibility = View.VISIBLE
            binding.futureActivityNote.text = note
        }
    }

    private fun futureActivityNote(now: Long): String? {
        val event = aimiActivityEvents(now)
            .filter { it.timestamp > now }
            .minByOrNull { it.timestamp } ?: return null
        val mode = tokenFromNote(event.note, "mode") ?: "ACTIVITY"
        val startIn = ((event.timestamp - now) / T.mins(1).msecs()).toInt().coerceAtLeast(0)
        val createdAt = aimiActivityCreatedAt(event)
        val lastRunTime = loop.lastRun?.lastAPSRun ?: 0L
        return if (lastRunTime >= createdAt) {
            "Будущая нагрузка учтена: $mode через ${startIn} мин"
        } else {
            "Будущая нагрузка включена: $mode через ${startIn} мин. Жду пересчет прогноза"
        }
    }

    private data class ActivityBolusContext(
        val factor: Double,
        val description: String
    )

    private fun futureActivityBolusContext(now: Long): ActivityBolusContext? {
        val event = aimiActivityEvents(now)
            .mapNotNull { event ->
                val mode = tokenFromNote(event.note, "mode")?.uppercase() ?: return@mapNotNull null
                val effect = activityEffectFraction(event, mode)
                if (effect <= 0.0) return@mapNotNull null
                val duration = (tokenFromNote(event.note, "duration")?.toLongOrNull() ?: (event.duration / T.mins(1).msecs()))
                    .coerceAtLeast(0L)
                val tail = (tokenFromNote(event.note, "tail")?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                val start = event.timestamp
                val activeEnd = start + T.mins(duration).msecs()
                val tailEnd = activeEnd + T.mins(tail).msecs()
                if (start > now + T.hours(2).msecs() || tailEnd < now - T.mins(5).msecs()) return@mapNotNull null
                event to ActivityWindowForBolus(mode, effect, start, activeEnd, tailEnd, tail)
            }
            .minByOrNull { abs(it.first.timestamp - now) }
            ?: return null

        val window = event.second
        val startOffset = ((window.start - now) / T.mins(1).msecs()).toInt()
        val overlap = when {
            now in window.start..window.activeEnd -> 1.0
            now in (window.activeEnd + 1)..window.tailEnd && window.tailMinutes > 0L ->
                ((window.tailEnd - now).toDouble() / T.mins(window.tailMinutes).msecs().toDouble()).coerceIn(0.0, 1.0)
            startOffset in 1..75 -> 1.0
            startOffset in 76..120 -> ((120 - startOffset).toDouble() / 45.0).coerceIn(0.0, 1.0)
            else -> 0.0
        }
        if (overlap <= 0.0) return null

        val factor = (1.0 - window.effectFraction * overlap).coerceIn(0.55, 1.0)
        val phase = when {
            now < window.start -> "старт через ${startOffset.coerceAtLeast(0)} мин"
            now <= window.activeEnd -> "активна"
            else -> "хвост"
        }
        return ActivityBolusContext(
            factor = factor,
            description = "${window.mode} $phase, новый инсулин x${"%.2f".format(factor)}"
        )
    }

    private data class ActivityWindowForBolus(
        val mode: String,
        val effectFraction: Double,
        val start: Long,
        val activeEnd: Long,
        val tailEnd: Long,
        val tailMinutes: Long
    )

    private fun activityEffectFraction(event: TE, mode: String): Double {
        tokenFromNote(event.note, "effect")?.toDoubleOrNull()?.let { return (it / 100.0).coerceIn(0.0, 0.45) }
        return when (mode) {
            "WALK"  -> 0.20
            "SPORT" -> 0.30
            else    -> 0.0
        }
    }

    private fun latestAimiActivityChangeTime(now: Long): Long =
        aimiActivityEvents(now)
            .maxOfOrNull { aimiActivityCreatedAt(it) }
            ?: 0L

    private fun aimiActivityEvents(now: Long): List<TE> =
        try {
            persistenceLayer.getTherapyEventDataFromToTime(
                now - T.hours(12).msecs(),
                now + T.hours(6).msecs()
            ).blockingGet()
                .filter { it.isValid && it.type == TE.Type.EXERCISE && it.note?.contains("AIMI_ACTIVITY_V2") == true }
        } catch (_: Throwable) {
            emptyList()
        }

    private fun aimiActivityCreatedAt(event: TE): Long {
        if (event.dateCreated > 0) return event.dateCreated
        val startOffset = tokenFromNote(event.note, "startOffset")?.toLongOrNull()
        return if (startOffset != null && startOffset > 0) {
            event.timestamp - T.mins(startOffset).msecs()
        } else {
            event.timestamp
        }
    }

    private fun tokenFromNote(note: String?, key: String): String? =
        note
            ?.split(' ')
            ?.firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }

    private fun forecastCarbsTargetMgdl(profile: Profile, tempTarget: TT?, useTT: Boolean): Double? {
        val apsTarget = when {
            config.APS        -> loop.lastRun?.constraintsProcessed?.targetBG
            config.AAPSCLIENT -> processedDeviceStatusData.getAPSResult()?.targetBG
            else              -> null
        }?.takeIf { it.isFinite() && it > 0.0 }

        val wizardTarget = if (useTT && tempTarget != null) {
            (tempTarget.lowTarget + tempTarget.highTarget) / 2.0
        } else {
            profile.getTargetMgdl()
        }.takeIf { it.isFinite() && it > 0.0 }

        return apsTarget ?: wizardTarget
    }

    private fun refreshOverviewIfForecastCarbsChanged(forecastRequiredCarbs: Int) {
        if (lastForecastRequiredCarbsOverviewRefresh == forecastRequiredCarbs) return
        lastForecastRequiredCarbsOverviewRefresh = forecastRequiredCarbs
        rxBus.send(EventRefreshOverview("WizardDialog forecast carbs", now = true))
    }

    private data class CarbTimingSuggestion(
        val actionText: String,
        val reasonText: String,
        val score: Double,
        val minBgMgdl: Double,
        val maxBgMgdl: Double
    )

    private data class CarbTimingCandidate(
        val offsetMinutes: Int,
        val score: Double,
        val minBgMgdl: Double,
        val maxBgMgdl: Double
    )

    private data class CarbTimingUrgency(
        val maxOffsetMinutes: Int?,
        val reasonText: String?
    )

    private data class TimingForecastPoint(val minutes: Int, val bgMgdl: Double)

    private fun carbTimingSuggestion(
        wizard: BolusWizard,
        profile: Profile,
        bg: Double,
        carbs: Int,
        selectedCarbTime: Int,
        selectedFoodType: String
    ): CarbTimingSuggestion? {
        if (carbs <= 0) return null

        val now = dateUtil.now()
        val finalForecast = finalAimiTimingForecastPoints(now)

        val forecast = finalForecast.ifEmpty { timingForecastPoints(now, bg) }
        if (forecast.isEmpty()) return null

        val targetLowMgdl = profile.getTargetLowMgdl()
        val targetHighMgdl = profile.getTargetHighMgdl()
        val targetMgdl = (targetLowMgdl + targetHighMgdl) / 2.0
        val decisionIsfMgdl = wizard.sensToMgdl()
        val carbsIsfMgdl = profile.getIsfMgdlForCarbs(now, "Wizard carb timing", config, processedDeviceStatusData)
        val ic = wizard.ic.coerceAtLeast(1.0)
        val carbEffectMgdl = carbs * carbsIsfMgdl / ic * carbGlucoseEffectScale(selectedFoodType)
        val plannedBolusEffectMgdl = wizard.insulinAfterConstraints.coerceAtLeast(0.0) * decisionIsfMgdl

        val urgency = carbTimingUrgency(
            forecast = forecast,
            targetLowMgdl = targetLowMgdl,
            targetHighMgdl = targetHighMgdl,
            selectedFoodType = selectedFoodType
        )
        val candidateOffsets = timingCandidateOffsets(
            forecast = forecast,
            selectedFoodType = selectedFoodType,
            plannedBolusEffectMgdl = plannedBolusEffectMgdl
        )
            .let { offsets ->
                urgency.maxOffsetMinutes?.let { maxOffset ->
                    offsets.filter { it <= maxOffset }
                        .plus(0)
                        .distinct()
                        .sorted()
                } ?: offsets
            }
        val candidates = candidateOffsets.map { offsetMinutes ->
            scoreCarbTiming(
                forecast = forecast,
                offsetMinutes = offsetMinutes,
                carbEffectMgdl = carbEffectMgdl,
                plannedBolusEffectMgdl = plannedBolusEffectMgdl,
                targetLowMgdl = targetLowMgdl,
                targetHighMgdl = targetHighMgdl,
                targetMgdl = targetMgdl,
                selectedFoodType = selectedFoodType
            )
        }
        val currentBgMgdl = forecast.firstOrNull()?.bgMgdl ?: 0.0
        val comfortableLowMgdl = targetLowMgdl - 5.0
        val comfortableHighMgdl = max(targetHighMgdl + 35.0, currentBgMgdl + 25.0)
        val comfortableCandidates = candidates
            .filter { it.minBgMgdl >= comfortableLowMgdl && it.maxBgMgdl <= comfortableHighMgdl }
        val chosen = comfortableCandidates.minByOrNull { it.offsetMinutes }
            ?: candidates.minWithOrNull(compareBy<CarbTimingCandidate> { it.score }.thenBy { it.offsetMinutes })
            ?: return null
        val noComfortableTime = comfortableCandidates.isEmpty()
        val nowCandidate = candidates.firstOrNull { it.offsetMinutes == 0 }
        val details = mutableListOf<String>()
        if (selectedCarbTime != chosen.offsetMinutes) {
            details += "Ты указал: ${formatSelectedCarbTiming(selectedCarbTime)}"
        }
        urgency.reasonText?.let { details += it }
        if (nowCandidate != null && chosen.offsetMinutes > 0 && nowCandidate.score > chosen.score * 1.12) {
            details += "Если съесть сейчас: прогноз хуже"
        }
        val baselineMaxMgdl = timingScoringPoints(forecast)
            .maxOfOrNull { it.bgMgdl } ?: currentBgMgdl
        val carbAddedPeakMgdl = chosen.maxBgMgdl - baselineMaxMgdl
        if (chosen.offsetMinutes > 0 && carbAddedPeakMgdl <= 5.0 && baselineMaxMgdl > targetHighMgdl + 25.0) {
            details += "Пик уже есть в текущем прогнозе; время еды не должно лечить этот пик"
        }
        val mainReason = when {
            noComfortableTime && chosen.maxBgMgdl > comfortableHighMgdl ->
                "лучшее время в ближайшем окне все еще дает пик до ${profileUtil.fromMgdlToStringInUnits(chosen.maxBgMgdl)}"

            chosen.minBgMgdl < targetLowMgdl - 5.0 ->
                "мин ${profileUtil.fromMgdlToStringInUnits(chosen.minBgMgdl)} ниже цели"

            chosen.maxBgMgdl > max(targetHighMgdl + 25.0, currentBgMgdl + 10.0) && carbAddedPeakMgdl > 5.0 ->
                "углеводы добавляют пик до ${profileUtil.fromMgdlToStringInUnits(chosen.maxBgMgdl)}"

            baselineMaxMgdl > max(targetHighMgdl + 25.0, currentBgMgdl + 10.0) ->
                "текущий прогноз уже с пиком ${profileUtil.fromMgdlToStringInUnits(baselineMaxMgdl)}"

            else ->
                "минимальное отклонение от цели ${profileUtil.fromMgdlToStringInUnits(targetMgdl)}"
        }
        return CarbTimingSuggestion(
            actionText = "Углеводы: ${formatRecommendedCarbTiming(chosen.offsetMinutes)}",
            reasonText = buildString {
                append("Причина: ")
                append(mainReason)
                if (details.isNotEmpty()) {
                    append("\n")
                    append(details.joinToString("\n"))
                }
            },
            score = chosen.score,
            minBgMgdl = chosen.minBgMgdl,
            maxBgMgdl = chosen.maxBgMgdl
        )
    }

    private fun timingCandidateOffsets(
        forecast: List<TimingForecastPoint>,
        selectedFoodType: String,
        plannedBolusEffectMgdl: Double
    ): List<Int> {
        val latestForecastMinute = timingScoringPoints(forecast).maxOfOrNull { it.minutes } ?: return listOf(0)
        val latestOffsetWithVisibleAbsorption = (latestForecastMinute - carbAbsorptionMinutes(selectedFoodType)).coerceAtLeast(0)
        val actionableOffset = carbTimingActionableWindowMinutes(selectedFoodType, plannedBolusEffectMgdl)
        val maxOffset = min(latestOffsetWithVisibleAbsorption, actionableOffset)
        return (0..maxOffset step 5).toList().ifEmpty { listOf(0) }
    }

    private fun carbTimingUrgency(
        forecast: List<TimingForecastPoint>,
        targetLowMgdl: Double,
        targetHighMgdl: Double,
        selectedFoodType: String
    ): CarbTimingUrgency {
        val currentBgMgdl = forecast.firstOrNull()?.bgMgdl ?: return CarbTimingUrgency(null, null)
        val shortDelta = wizard?.glucoseStatus?.shortAvgDelta ?: 0.0
        val earlyPoints = forecast.filter { it.minutes in 0..60 }
        val earlyLowPoint = earlyPoints
            .filter { it.bgMgdl <= targetLowMgdl + 5.0 }
            .minByOrNull { it.minutes }
        val point15 = earlyPoints.minByOrNull { abs(it.minutes - 15) }
        val forecastFallingSoon = point15 != null && point15.bgMgdl <= currentBgMgdl - 5.0
        val fallingNow = shortDelta <= -2.0 || forecastFallingSoon
        val alreadyNearLow = currentBgMgdl <= targetLowMgdl + 5.0
        val fallingNearTarget = fallingNow && currentBgMgdl <= targetHighMgdl + 15.0

        if (!alreadyNearLow && !fallingNearTarget && earlyLowPoint == null) {
            return CarbTimingUrgency(null, null)
        }

        val riskMinute = when {
            alreadyNearLow       -> 0
            earlyLowPoint != null -> earlyLowPoint.minutes
            else                 -> 20
        }
        val usefulLead = carbUsefulLeadMinutes(selectedFoodType)
        val maxOffset = (riskMinute - usefulLead).coerceAtLeast(0)
        val reason = when {
            alreadyNearLow ->
                "Глюкоза уже около нижней границы, дальнюю отсрочку не используем"

            fallingNearTarget ->
                "Глюкоза падает сейчас, ближайший риск важнее дальнего пика"

            else ->
                "В ближайшем прогнозе есть низкая зона, углеводы нужны до нее"
        }
        return CarbTimingUrgency(maxOffset, reason)
    }

    private fun scoreCarbTiming(
        forecast: List<TimingForecastPoint>,
        offsetMinutes: Int,
        carbEffectMgdl: Double,
        plannedBolusEffectMgdl: Double,
        targetLowMgdl: Double,
        targetHighMgdl: Double,
        targetMgdl: Double,
        selectedFoodType: String
    ): CarbTimingCandidate {
        var score = 0.0
        var weightSum = 0.0
        var minBg = Double.POSITIVE_INFINITY
        var maxBg = Double.NEGATIVE_INFINITY
        timingScoringPoints(forecast)
            .forEach { point ->
                val minutesSinceCarb = point.minutes - offsetMinutes
                val carbFraction = carbEffectFraction(minutesSinceCarb, selectedFoodType)
                val bolusFraction = insulinActionFraction(point.minutes)
                val adjustedBg = point.bgMgdl + carbEffectMgdl * carbFraction - plannedBolusEffectMgdl * bolusFraction
                minBg = min(minBg, adjustedBg)
                maxBg = max(maxBg, adjustedBg)

                val distance = abs(adjustedBg - targetMgdl)
                val lowPenaltyFactor = if (selectedFoodType == "fast" && minutesSinceCarb >= 45) 8.0 else 5.0
                val highPenaltyFactor = if (selectedFoodType == "fast" && minutesSinceCarb in 0..45) 0.9 else 1.8
                val lowPenalty = if (adjustedBg < targetLowMgdl) (targetLowMgdl - adjustedBg) * lowPenaltyFactor else 0.0
                val highPenalty = if (adjustedBg > targetHighMgdl) (adjustedBg - targetHighMgdl) * highPenaltyFactor else 0.0
                val timeWeight = forecastPointWeight(point.minutes) * if (selectedFoodType == "fast" && minutesSinceCarb >= 45) 1.25 else 1.0
                score += (distance + lowPenalty + highPenalty) * timeWeight
                weightSum += timeWeight
            }
        val baseScore = if (weightSum > 0.0) score / weightSum else Double.MAX_VALUE
        return CarbTimingCandidate(
            offsetMinutes = offsetMinutes,
            score = baseScore,
            minBgMgdl = if (minBg.isFinite()) minBg else forecast.first().bgMgdl,
            maxBgMgdl = if (maxBg.isFinite()) maxBg else forecast.first().bgMgdl
        )
    }

    private fun timingScoringPoints(forecast: List<TimingForecastPoint>): List<TimingForecastPoint> =
        forecast.filter { it.minutes >= 10 }

    private fun forecastPointWeight(minutes: Int): Double =
        if (minutes < 20) 0.55 else 1.0

    private fun timingForecastPoints(now: Long, bg: Double): List<TimingForecastPoint> {
        val predicted = (finalAimiTimingForecastPoints(now).ifEmpty {
            overviewData.predictionValues
                .filter { it.timestamp >= now - T.mins(2).msecs() }
                .sortedBy { it.timestamp }
                .map {
                    TimingForecastPoint(
                        minutes = max(0, ((it.timestamp - now) / T.mins(1).msecs()).toInt()),
                        bgMgdl = it.value
                    )
                }
        })
        if (predicted.isNotEmpty()) return predicted

        val units = profileFunction.getUnits()
        val currentBgMgdl = when {
            bg > 0.0 -> profileUtil.convertToMgdl(bg, units)
            else     -> persistenceLayer.getLastGlucoseValue()?.value ?: return emptyList()
        }
        val delta = wizard?.glucoseStatus?.shortAvgDelta ?: 0.0
        return (0..36).map { step ->
            val minutes = step * 5
            val decayedTrend = delta * step.coerceAtMost(6) * 0.7
            TimingForecastPoint(minutes = minutes, bgMgdl = currentBgMgdl + decayedTrend)
        }
    }

    private fun finalAimiTimingForecastPoints(now: Long): List<TimingForecastPoint> =
        overviewData.finalAimiPredictionValues
            .filter { it.timestamp >= now - T.mins(2).msecs() }
            .sortedBy { it.timestamp }
            .map {
                TimingForecastPoint(
                    minutes = max(0, ((it.timestamp - now) / T.mins(1).msecs()).toInt()),
                    bgMgdl = it.value
                )
            }

    private fun carbAbsorbedFraction(minutesSinceCarb: Int, selectedFoodType: String): Double {
        val (peakMinutes, absorptionMinutes) = when (selectedFoodType) {
            "fast" -> 15.0 to 45.0
            "slow" -> 80.0 to 240.0
            else -> 50.0 to 165.0
        }
        return cumulativeGaussianFraction(minutesSinceCarb.toDouble(), peakMinutes, absorptionMinutes)
    }

    private fun carbTimingPeakMinutes(selectedFoodType: String): Int =
        when (selectedFoodType) {
            "fast" -> 15
            "slow" -> 80
            else -> 50
        }

    private fun carbAbsorptionMinutes(selectedFoodType: String): Int =
        when (selectedFoodType) {
            "fast" -> 45
            "slow" -> 240
            else -> 165
        }

    private fun carbTimingActionableWindowMinutes(selectedFoodType: String, plannedBolusEffectMgdl: Double): Int {
        val hasMeaningfulBolus = plannedBolusEffectMgdl >= 5.0
        return when (selectedFoodType) {
            "fast" -> if (hasMeaningfulBolus) 60 else 45
            "slow" -> if (hasMeaningfulBolus) 180 else 120
            else -> if (hasMeaningfulBolus) 120 else 90
        }
    }

    private fun carbUsefulLeadMinutes(selectedFoodType: String): Int =
        when (selectedFoodType) {
            "fast" -> 5
            "slow" -> 45
            else -> 20
        }

    private fun carbEffectFraction(minutesSinceCarb: Int, selectedFoodType: String): Double {
        if (selectedFoodType != "fast") return carbAbsorbedFraction(minutesSinceCarb, selectedFoodType)
        if (minutesSinceCarb <= 0) return 0.0
        return when {
            minutesSinceCarb <= 15 -> 0.85 * minutesSinceCarb / 15.0
            minutesSinceCarb <= 45 -> 0.85 + 0.15 * (minutesSinceCarb - 15) / 30.0
            else -> 1.0
        }.coerceIn(0.0, 1.0)
    }

    private fun carbGlucoseEffectScale(selectedFoodType: String): Double =
        when (selectedFoodType) {
            "fast", "slow" -> 1.0
            else           -> 1.0
        }

    private fun insulinActionFraction(minutesSinceBolus: Int): Double =
        cumulativeGaussianFraction(minutesSinceBolus.toDouble() - 10.0, 65.0, 300.0)

    private fun cumulativeGaussianFraction(minutes: Double, peakMinutes: Double, durationMinutes: Double): Double {
        if (minutes <= 0.0) return 0.0
        if (minutes >= durationMinutes) return 1.0
        val steps = max(1, (durationMinutes / 5.0).toInt())
        val sigma = (durationMinutes / 3.2).coerceAtLeast(20.0)
        var total = 0.0
        var absorbed = 0.0
        for (idx in 0 until steps) {
            val stepMinute = (idx + 1) * 5.0
            val z = (stepMinute - peakMinutes) / sigma
            val weight = exp(-0.5 * z * z)
            total += weight
            if (stepMinute <= minutes) absorbed += weight
        }
        return if (total <= 0.0) 0.0 else (absorbed / total).coerceIn(0.0, 1.0)
    }

    private fun BolusWizard.sensToMgdl(): Double =
        if (profileFunction.getUnits() == GlucoseUnit.MGDL) sens else sens / Constants.MGDL_TO_MMOLL

    private fun coloredIsfText(text: String, color: Int): SpannableStringBuilder {
        val spannable = SpannableStringBuilder(text)
        val start = text.indexOf("ISF")
        if (start >= 0) spannable.setSpan(ForegroundColorSpan(color), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return spannable
    }

    private fun colorWizardIsfLabels(text: CharSequence): SpannableStringBuilder =
        SpannableStringBuilder(text).also { spannable ->
            colorAllOccurrences(spannable, "ISF решения", isfDecisionColor)
            colorAllOccurrences(spannable, "ISF еды/COB", isfCarbsColor)
        }

    private fun colorAllOccurrences(spannable: SpannableStringBuilder, label: String, color: Int) {
        var start = spannable.indexOf(label)
        while (start >= 0) {
            val end = start + label.length
            spannable.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            start = spannable.indexOf(label, end)
        }
    }

    private fun formatRecommendedCarbTiming(minutes: Int): String =
        when {
            minutes < 0 -> "сейчас (окно было ${-minutes} мин назад)"
            minutes == 0 -> "сейчас"
            else -> "через $minutes мин"
        }

    private fun formatSelectedCarbTiming(minutes: Int): String =
        when {
            minutes < 0 -> "${-minutes} мин назад"
            minutes == 0 -> "сейчас"
            else -> "через $minutes мин"
        }

    private fun buildAimiLogicSummary(
        wizard: BolusWizard,
        profile: Profile,
        bg: Double,
        carbs: Int,
        cob: Double,
        carbTime: Int
    ): CharSequence {
        val decision = wizard.aimiMealDecision
        val targetText = profileUtil.toTargetRangeString(
            profileUtil.convertToMgdl(profile.getTargetLowMgdl(), GlucoseUnit.MGDL),
            profileUtil.convertToMgdl(profile.getTargetHighMgdl(), GlucoseUnit.MGDL),
            GlucoseUnit.MGDL,
            profileFunction.getUnits()
        )
        val summary = buildString {
            append("<b>AIMI встроен в расчёт Мастера Болюса.</b><br/>")
            append("Входы: ")
            append("ГК ")
            append(valueToUnitsToString(profileUtil.convertToMgdl(bg, profileFunction.getUnits()), profileFunction.getUnits().asText))
            append(", углеводы ")
            append(carbs)
            append(" г, время углеводов ")
            append(carbTime)
            append(" мин, target ")
            append(targetText)
            append(", IC ")
            append(decimalFormatter.to1Decimal(wizard.ic))
            append(", <font color=\"")
            append(isfDecisionColorHtml)
            append("\">ISF решения ")
            append(decimalFormatter.to1Decimal(wizard.sens))
            append("</font>")
            append(", <font color=\"")
            append(isfCarbsColorHtml)
            append("\">ISF еды/COB ")
            append(
                decimalFormatter.to1Decimal(
                    profileUtil.fromMgdlToUnits(
                        profile.getIsfMgdlForCarbs(dateUtil.now(), "Wizard summary", config, processedDeviceStatusData),
                        profileFunction.getUnits()
                    )
                )
            )
            append("</font>")
            append(", COB ")
            append(decimalFormatter.to1Decimal(cob))
            append(", IOB ")
            append(decimalFormatter.to2Decimal(-(wizard.insulinFromBolusIOB + wizard.insulinFromBasalIOB)))
            append(" ед, тренд ")
            append(decimalFormatter.to2Decimal(wizard.insulinFromTrend))
            append(" ед, тип еды ")
            append(currentSelectedFoodTypeLabel())
            append(".<br/>")
            if (decision != null) {
                append("AIMI meal mode: <b>")
                append(decision.mealMode)
                append("</b>, factor ")
                append(decimalFormatter.to2Decimal(decision.modeFactor))
                append(", prebolus ")
                append(decimalFormatter.to2Decimal(decision.prebolusBonus))
                append(" ед.<br/>")
                append("Логика: базовая часть без carb-компонента + carb-компонент × factor + prebolus, затем ограничения болюса. Быстрые углеводы считаются как быстрое всасывание с осторожным покрытием bolus, а не как исчезающий до нуля всплеск.<br/>")
                append("Результат AIMI: <b>")
                append(rh.gs(app.aaps.core.ui.R.string.format_insulin_units, decision.recommendedBolus))
                append("</b>. Объяснение: ")
                append(decision.explanation)
            } else {
                append("AIMI decision ещё не рассчитан.")
            }
        }
        return colorWizardIsfLabels(HtmlHelper.fromHtml(summary))
    }

    private data class GlossaryDefinition(val title: String, val body: String)

    private val glossary by lazy {
        linkedMapOf(
            "COB" to GlossaryDefinition("COB", "Carbs On Board — ещё не отыгравшие углеводы, которые система считает остающимися в усвоении."),
            "IOB" to GlossaryDefinition("IOB", "Insulin On Board — ещё действующий инсулин, который продолжает влиять на глюкозу."),
            "ISF" to GlossaryDefinition("ISF", "Insulin Sensitivity Factor — насколько 1 единица инсулина, по оценке системы, снижает глюкозу."),
            "IC" to GlossaryDefinition("IC", "Insulin to Carb ratio — сколько граммов углеводов покрывает 1 единица инсулина."),
            "prebolus" to GlossaryDefinition("prebolus", "Часть стратегии до еды. Для быстрых углеводов он выключается, для медленной еды ослабляется."),
            "factor" to GlossaryDefinition("factor", "Множитель carb-компонента. На него влияет meal mode AIMI и выбранный тип еды."),
            "тип еды" to GlossaryDefinition("Тип еды", "Переключатель carb-модели. Быстрые углеводы всасываются рано и покрываются осторожнее, обычная еда оставляет базовую форму, медленная растягивает влияние углеводов во времени."),
            "meal mode" to GlossaryDefinition("meal mode", "Контекст еды, который AIMI определяет по размеру приёма пищи и времени суток: snack, breakfast, lunch, dinner, meal, highcarb."),
            "target" to GlossaryDefinition("Target", "Целевая зона глюкозы, к которой AIMI старается вести расчёт.")
        )
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
                            OKDialog.show(requireContext(), definition.title, definition.body)
                        }

                        override fun updateDrawState(ds: TextPaint) {
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

    override fun show(manager: FragmentManager, tag: String?) {
        try {
            manager.beginTransaction().let {
                it.add(this, tag)
                it.commitAllowingStateLoss()
            }
        } catch (e: IllegalStateException) {
            aapsLogger.debug(e.localizedMessage ?: "")
        }
    }

    override fun onResume() {
        super.onResume()
        if (!queryingProtection) {
            queryingProtection = true
            activity?.let { activity ->
                val cancelFail = {
                    queryingProtection = false
                    aapsLogger.debug(LTag.APS, "Dialog canceled on resume protection: ${this.javaClass.simpleName}")
                    ToastUtils.warnToast(ctx, R.string.dialog_canceled)
                    dismiss()
                }
                protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, { queryingProtection = false }, cancelFail, cancelFail)
            }
        }
    }
}
