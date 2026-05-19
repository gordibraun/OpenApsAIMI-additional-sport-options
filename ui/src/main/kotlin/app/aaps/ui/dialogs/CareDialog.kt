package app.aaps.ui.dialogs

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.annotation.StringRes
import androidx.fragment.app.FragmentManager
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.configuration.Constants.DEF_TT_EXERCISE_DURATION
import app.aaps.core.data.configuration.Constants.MIN_TT_EXERCISE_DURATION
import app.aaps.core.data.configuration.Constants.NO_SPORT_PERCENTAGE
import app.aaps.core.data.configuration.Constants.SPORT_PERCENTAGE_LIGHT
import app.aaps.core.data.configuration.Constants.SPORT_PERCENTAGE_MIDDLE
import app.aaps.core.data.configuration.Constants.SPORT_PERCENTAGE_HEAVY
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Translator
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.extensions.toVisibility
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.utils.HtmlHelper
import app.aaps.ui.R
import app.aaps.ui.databinding.DialogCareBinding
import com.google.common.base.Joiner
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.text.DecimalFormat
import java.util.LinkedList
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

class CareDialog(val fm: FragmentManager) : DialogFragmentWithDate() {

    private val TAG = "CareDialog"

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var translator: Translator
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Inject lateinit var profileUtil: ProfileUtil

    // sargius from ProfileSwitchDialog
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var config: Config
    @Inject lateinit var hardLimits: HardLimits
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var ctx: Context
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var loop: Loop
    @Inject lateinit var commandQueue: CommandQueue

    private var queryingProtection = false
    private var profileName: String? = null
    ////

    private val disposable = CompositeDisposable()
    private var activityRequiredCarbs = 0
    private var activityCarbsWithinMinutes = 0

    private var options: UiInteraction.EventType = UiInteraction.EventType.BGCHECK
    //private var valuesWithUnit = mutableListOf<XXXValueWithUnit?>()
    private var valuesWithUnit = mutableListOf<ValueWithUnit?>()

    @StringRes
    private var event: Int = app.aaps.core.ui.R.string.none

    private var _binding: DialogCareBinding? = null
    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!


    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        savedInstanceState.putInt("event", event)
        savedInstanceState.putInt("options", options.ordinal)

        // from ProfileSwitchDialog
        savedInstanceState.putDouble("duration", binding.duration.value)
        savedInstanceState.putDouble("percentage", binding.percentage.value)
        savedInstanceState.putDouble("timeshift", binding.timeshift.value)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        onCreateViewGeneral()
        _binding = DialogCareBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (savedInstanceState ?: arguments)?.let {
            event = it.getInt("event", app.aaps.core.ui.R.string.error)
            options = UiInteraction.EventType.entries.toTypedArray()[it.getInt("options", 0)]
        }

        binding.icon.setImageResource(
            when (options) {
                UiInteraction.EventType.BGCHECK        -> app.aaps.core.objects.R.drawable.ic_cp_bgcheck
                UiInteraction.EventType.SENSOR_INSERT  -> app.aaps.core.objects.R.drawable.ic_cp_cgm_insert
                UiInteraction.EventType.BATTERY_CHANGE -> app.aaps.core.objects.R.drawable.ic_cp_pump_battery
                UiInteraction.EventType.NOTE           -> app.aaps.core.objects.R.drawable.ic_cp_note
                UiInteraction.EventType.EXERCISE       -> app.aaps.core.objects.R.drawable.ic_cp_exercise
                UiInteraction.EventType.QUESTION       -> app.aaps.core.objects.R.drawable.ic_cp_question
                UiInteraction.EventType.ANNOUNCEMENT   -> app.aaps.core.objects.R.drawable.ic_cp_announcement
            }
        )

        binding.title.text = rh.gs(
            when (options) {
                UiInteraction.EventType.BGCHECK        -> app.aaps.core.ui.R.string.careportal_bgcheck
                UiInteraction.EventType.SENSOR_INSERT  -> app.aaps.core.ui.R.string.cgm_sensor_insert
                UiInteraction.EventType.BATTERY_CHANGE -> app.aaps.core.ui.R.string.pump_battery_change
                UiInteraction.EventType.NOTE           -> app.aaps.core.ui.R.string.careportal_note
                UiInteraction.EventType.EXERCISE       -> app.aaps.core.ui.R.string.careportal_exercise
                UiInteraction.EventType.QUESTION       -> app.aaps.core.ui.R.string.careportal_question
                UiInteraction.EventType.ANNOUNCEMENT   -> app.aaps.core.ui.R.string.careportal_announcement
            }
        )

        when (options) {
            UiInteraction.EventType.QUESTION,
            UiInteraction.EventType.ANNOUNCEMENT,
            UiInteraction.EventType.BGCHECK        -> {
                binding.durationLayout.visibility = View.GONE
            }

            UiInteraction.EventType.SENSOR_INSERT,
            UiInteraction.EventType.BATTERY_CHANGE -> {
                binding.bgLayout.visibility = View.GONE
                binding.bgsource.visibility = View.GONE
                binding.durationLayout.visibility = View.GONE
            }

            UiInteraction.EventType.NOTE           -> {
                binding.bgLayout.visibility = View.GONE
                binding.bgsource.visibility = View.GONE
            }

            UiInteraction.EventType.EXERCISE       -> {
                binding.bgLayout.visibility = View.GONE
                binding.bgsource.visibility = View.GONE
                binding.sportDutyLayout.visibility = View.VISIBLE
            }
        }

        // sargius added
        val lastTherapyEvent = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.EXERCISE)
        val lastExerciseDuty = lastTherapyEvent?.exerciseDuty
        Log.d(TAG, "id = ${lastTherapyEvent?.id} lastTherapyEvent = $lastTherapyEvent, lastExerciseDuty = $lastExerciseDuty")
        if (lastExerciseDuty == null || lastExerciseDuty == TE.ExerciseDuty.NONE) {
            binding.switchDutyOptions.isChecked = false
            binding.tt.isChecked = false

            binding.dutyLight.isChecked = false
            binding.dutyMiddle.isChecked = false
            binding.dutyHeavy.isChecked = false

        } else {
            binding.switchDutyOptions.isChecked = true
            binding.tt.isChecked = true

            when (lastExerciseDuty) {
                TE.ExerciseDuty.LIGHT -> binding.dutyLight.isChecked = true
                TE.ExerciseDuty.MIDDLE -> binding.dutyMiddle.isChecked = true
                TE.ExerciseDuty.HEAVY -> binding.dutyHeavy.isChecked = true

                else -> {
                    binding.dutyLight.isChecked = false
                    binding.dutyMiddle.isChecked = false
                    binding.dutyHeavy.isChecked = false
                }
            }
        }

        binding.switchDutyOptions.setOnClickListener {
            if (binding.switchDutyOptions.isChecked) {
                Log.d(TAG, "Sport options checked")
                binding.dutyLight.setChecked(true)
                binding.percentage.value = SPORT_PERCENTAGE_LIGHT
                binding.tt.isChecked = true

            } else {
                Log.d(TAG, "Sport options NOT checked")
                binding.sportDuty.clearCheck()
                binding.percentage.value = NO_SPORT_PERCENTAGE
                binding.tt.isChecked = false
            }
        }

        // radio buttons sport duty options
        binding.sportDuty.setOnClickListener {
            Log.d(TAG, "sportDuty clicked")
            if (binding.dutyLight.isSelected || binding.dutyMiddle.isSelected || binding.dutyHeavy.isSelected) {
                binding.switchDutyOptions.setChecked(true)
                binding.tt.isChecked = true
            }
        }

        binding.dutyLight.setOnClickListener {
            Log.d(TAG, "dutyLight clicked")
            binding.switchDutyOptions.setChecked(true)
            binding.percentage.value = SPORT_PERCENTAGE_LIGHT
            binding.tt.isChecked = true

            if (binding.dutyLight.isChecked) {
                Log.d(TAG, "dutyLight isChecked")
            }
        }

        binding.dutyMiddle.setOnClickListener {
            Log.d(TAG, "dutyMiddle clicked")
            binding.switchDutyOptions.setChecked(true)
            binding.percentage.value = SPORT_PERCENTAGE_MIDDLE
            binding.tt.isChecked = true
        }

        binding.dutyHeavy.setOnClickListener {
            Log.d(TAG, "dutyHeavy clicked")
            binding.switchDutyOptions.setChecked(true)
            binding.percentage.value = SPORT_PERCENTAGE_HEAVY
            binding.tt.isChecked = true
        }
        ////

        // radio buttons duration presets
        binding.duration30.setOnClickListener {
            binding.duration.value = 30.0
        }

        binding.duration50.setOnClickListener {
            binding.duration.value = 50.0
        }

        binding.duration80.setOnClickListener {
            binding.duration.value = 80.0
        }
        ////

        //
        binding.tt.setOnClickListener {
            val isTT = binding.duration.value > 0 && binding.percentage.value < 100 && binding.switchDutyOptions.isChecked
            binding.tt.isChecked = isTT
            Log.d(TAG, "isTT checked = ${binding.tt.isChecked}")
        }
        ////

        // from ProfileSwitchDialog
        binding.percentage.setParams(
            savedInstanceState?.getDouble("percentage") ?: SPORT_PERCENTAGE_LIGHT, // 100
            Constants.CPP_MIN_PERCENTAGE.toDouble(),
            Constants.CPP_MAX_PERCENTAGE.toDouble(),
            5.0,
            DecimalFormat("0"),
            false,
            binding.okcancel.ok,
            textWatcher
        )

        binding.timeshift.setParams(
            savedInstanceState?.getDouble("timeshift") ?: 0.0,
            Constants.CPP_MIN_TIMESHIFT.toDouble(),
            Constants.CPP_MAX_TIMESHIFT.toDouble(),
            1.0, DecimalFormat("0"),
            false,
            binding.okcancel.ok
        )
        ////


        val bg = profileUtil.fromMgdlToUnits(glucoseStatusProvider.glucoseStatusData?.glucose ?: 0.0)
        val bgTextWatcher: TextWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable) {}
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (binding.sensor.isChecked) binding.meter.isChecked = true
            }
        }

        // from ProfileSwitchDialog
        // profile
        context?.let { context ->
            val profileStore = activePlugin.activeProfileSource.profile ?: return
            val profileListToCheck = profileStore.getProfileList()
            val profileList = ArrayList<CharSequence>()

            for (profileName in profileListToCheck) {
                val profileToCheck =
                    activePlugin.activeProfileSource.profile?.getSpecificProfile(profileName.toString())

                if (profileToCheck != null &&
                    ProfileSealed.Pure(profileToCheck, activePlugin)
                        .isValid(
                            "ProfileSwitch",
                            activePlugin.activePump,
                            config,
                            rh,
                            rxBus,
                            hardLimits,
                            false
                        ).isValid
                ) {
                    profileList.add(profileName)
                }
            }

            if (profileList.isEmpty()) {
                dismiss()
                return
            }

            binding.profileList.setAdapter(
                ArrayAdapter(
                    context,
                    app.aaps.core.ui.R.layout.spinner_centered,
                    profileList
                )
            )

            // set selected to actual profile
            if (profileName != null) {
                binding.profileList.setText(profileName, false)

            } else {
                binding.profileList.setText(profileList[0], false)

                for (p in profileList.indices) {
                    if (profileList[p] == profileFunction.getOriginalProfileName()) {
                        binding.profileList.setText(profileList[p], false)
                    }
                }
            }
        }

        profileFunction.getProfile()?.let { profile ->
            if (profile is ProfileSealed.EPS) {
                if (profile.value.originalPercentage != 100 || profile.value.originalTimeshift != 0L) {
                    binding.reuselayout.visibility = View.VISIBLE
                    binding.reusebutton.text = rh.gs(
                        R.string.reuse_profile_pct_hours,
                        profile.value.originalPercentage,
                        T.msecs(profile.value.originalTimeshift).hours().toInt()
                    )
                    binding.reusebutton.setOnClickListener {
                        binding.percentage.value = profile.value.originalPercentage.toDouble()
                        binding.timeshift.value = T.msecs(profile.value.originalTimeshift).mins().toDouble() // hours, sargius changed
                    }
                }
            }
        }
        ////

        if (profileFunction.getUnits() == GlucoseUnit.MMOL) {
            binding.bgUnits.text = rh.gs(app.aaps.core.ui.R.string.mmol)
            binding.bg.setParams(
                savedInstanceState?.getDouble("bg")
                    ?: bg,
                2.0,
                30.0,
                0.1,
                DecimalFormat("0.0"),
                false,
                binding.okcancel.ok,
                bgTextWatcher
            )
        } else {
            binding.bgUnits.text = rh.gs(app.aaps.core.ui.R.string.mgdl)
            binding.bg.setParams(
                savedInstanceState?.getDouble("bg")
                    ?: bg,
                36.0,
                500.0,
                1.0,
                DecimalFormat("0"),
                false,
                binding.okcancel.ok,
                bgTextWatcher
            )
        }

        binding.duration.setParams(
            savedInstanceState?.getDouble("duration") ?: DEF_TT_EXERCISE_DURATION,
            MIN_TT_EXERCISE_DURATION,
            Constants.MAX_TT_EXERCISE_DURATION,
            10.0, DecimalFormat("0"),
            false,
            binding.okcancel.ok,
            textWatcher
        ) // DEF_TT_EXERCISE_DURATION

        if (options == UiInteraction.EventType.NOTE || options == UiInteraction.EventType.QUESTION
                || options == UiInteraction.EventType.ANNOUNCEMENT || options == UiInteraction.EventType.EXERCISE) {

            binding.notesLayout.root.visibility = View.VISIBLE
        }

        // independent to preferences
        binding.bgLabel.labelFor = binding.bg.editTextId
        binding.durationLabel.labelFor = binding.duration.editTextId

        // from ProfileSwitchDialog
        // binding.ttLayout.visibility = View.GONE
        binding.percentageLabel.labelFor = binding.percentage.editTextId
        binding.timeshiftLabel.labelFor = binding.timeshift.editTextId

        if (options == UiInteraction.EventType.EXERCISE) {
            setupActivityV2Ui()
        }
    }

    // from ProfileSwitchDialog
    override fun onResume() {
        super.onResume()
        if (!queryingProtection) {
            queryingProtection = true

            activity?.let { activity ->
                val cancelFail = {
                    queryingProtection = false
                    aapsLogger.debug(
                        LTag.APS,
                        "Dialog canceled on resume protection: ${this.javaClass.simpleName}"
                    )
                    ToastUtils.warnToast(ctx, R.string.dialog_canceled)
                    dismiss()
                }

                protectionCheck.queryProtection(
                    activity,
                    ProtectionCheck.Protection.BOLUS,
                    { queryingProtection = false },
                    cancelFail,
                    cancelFail
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private data class ActivityPlan(
        val mode: String,
        val effectPercent: Int,
        val startOffsetMinutes: Int,
        val durationMinutes: Int,
        val tailMinutes: Int,
        val activityWindowEndMinutes: Int,
        val requiredCarbs: Int,
        val baseRequiredCarbs: Int,
        val activityRequiredCarbs: Int,
        val carbsWithinMinutes: Int,
        val carbType: String?,
        val forecastMin: Double,
        val forecastMinMinute: Int,
        val lateForecastMin: Double?,
        val lateForecastMinMinute: Int?,
        val glucoseUseMgdlPer5m: Double,
        val carbSensitivityMgdlPerGram: Double,
        val activityEquivalentCarbs: Double,
        val activityCarbFloorMgdl: Double,
        val baseDeficitMgdl: Double,
        val activityDeficitMgdl: Double,
        val totalDeficitMgdl: Double,
        val firstRiskMinute: Int?,
        val firstActivityImpactMinute: Int?,
        val carbLeadMinutes: Int
    )

    private data class ActivityWatchSnapshot(
        val steps5min: Int?,
        val steps10min: Int?,
        val avgHr5min: Double?,
        val avgHr60min: Double?,
        val assessment: String
    )

    private data class ActiveActivity(
        val event: TE,
        val mode: String,
        val effectPercent: Int,
        val startMs: Long,
        val durationMinutes: Int,
        val tailMinutes: Int,
        val requiredCarbs: Int,
        val carbType: String?,
        val plannedDurationMinutes: Int
    ) {

        val activeEndMs: Long = startMs + TimeUnit.MINUTES.toMillis(durationMinutes.toLong())
        val tailEndMs: Long = activeEndMs + TimeUnit.MINUTES.toMillis(tailMinutes.toLong())
    }

    private fun setupActivityV2Ui() {
        binding.activityV2Layout.visibility = View.VISIBLE
        binding.sportDutyLayout.visibility = View.GONE
        binding.sportDurationPresets.visibility = View.GONE
        binding.durationLayout.visibility = View.GONE
        binding.percentageLayout.visibility = View.GONE
        binding.timeshiftLayout.visibility = View.GONE
        binding.ttLayout.visibility = View.GONE
        binding.reuselayout.visibility = View.GONE
        binding.notesLayout.root.visibility = View.GONE
        binding.datetime.root.visibility = View.GONE
        (binding.profileList.parent as? View)?.visibility = View.GONE

        binding.activityMode.setOnCheckedChangeListener { _, _ -> updateActivityV2Ui() }
        binding.activityDuration.setOnCheckedChangeListener { _, _ -> updateActivityV2Ui() }
        binding.activityStart.setOnCheckedChangeListener { _, _ -> updateActivityV2Ui() }
        binding.activityCarbType.setOnCheckedChangeListener { _, _ -> updateActivityV2Ui() }
        binding.activityConfirmCarbs.setOnCheckedChangeListener { _, _ -> updateActivityV2Ui() }
        binding.activityEndNow.setOnClickListener { endCurrentActivity() }
        binding.activityCancel.setOnClickListener { cancelCurrentActivity() }
        updateActivityV2Ui()
    }

    private fun selectedActivityMode(): String =
        if (binding.activityModeSport.isChecked) "SPORT" else "WALK"

    private fun selectedActivityDuration(): Int =
        when {
            binding.activityDuration90.isChecked -> 90
            binding.activityDuration50.isChecked -> 50
            else                                 -> 30
        }

    private fun selectedActivityStartOffset(): Int =
        when {
            binding.activityStart60.isChecked -> 60
            binding.activityStart50.isChecked -> 50
            binding.activityStart30.isChecked -> 30
            binding.activityStart20.isChecked -> 20
            else                              -> 0
        }

    private fun selectedActivityCarbType(): String? =
        when {
            binding.activityCarbsFast.isChecked   -> "fast"
            binding.activityCarbsNormal.isChecked -> "balanced"
            else                                  -> null
        }

    private fun activityEffectPercent(mode: String): Int =
        if (mode == "SPORT") 30 else 20

    private fun activityTailMinutes(mode: String, durationMinutes: Int): Int =
        when (mode) {
            "SPORT" -> when {
                durationMinutes >= 90 -> 180
                durationMinutes >= 50 -> 120
                else                  -> 60
            }

            else -> when {
                durationMinutes >= 90 -> 30
                else                  -> 0
            }
        }

    private fun qualifiedActivityDuration(durationMinutes: Int): Int =
        when {
            durationMinutes <= 40 -> 30
            durationMinutes <= 70 -> 50
            else                  -> 90
        }

    private fun activityNoteTokens(note: String?): Map<String, String> =
        note
            ?.split(' ')
            ?.mapNotNull { part ->
                val splitAt = part.indexOf('=')
                if (splitAt <= 0 || splitAt >= part.lastIndex) null else part.substring(0, splitAt) to part.substring(splitAt + 1)
            }
            ?.toMap()
            ?: emptyMap()

    private fun activityNote(
        mode: String,
        effectPercent: Int,
        startOffsetMinutes: Int,
        durationMinutes: Int,
        tailMinutes: Int,
        requiredCarbs: Int,
        carbType: String?
    ): String =
        "AIMI_ACTIVITY_V2 mode=$mode effect=$effectPercent startOffset=$startOffsetMinutes " +
            "duration=$durationMinutes tail=$tailMinutes requiredCarbs=$requiredCarbs " +
            "carbType=${carbType ?: "none"}"

    private fun parseActiveActivity(event: TE): ActiveActivity? {
        if (!event.isValid || event.type != TE.Type.EXERCISE || event.note?.contains("AIMI_ACTIVITY_V2") != true) return null
        val tokens = activityNoteTokens(event.note)
        val mode = tokens["mode"]?.uppercase()?.takeIf { it == "WALK" || it == "SPORT" } ?: return null
        val duration = (tokens["duration"]?.toIntOrNull() ?: (event.duration / 60_000L).toInt()).coerceAtLeast(5)
        val tail = (tokens["tail"]?.toIntOrNull() ?: activityTailMinutes(mode, qualifiedActivityDuration(duration))).coerceAtLeast(0)
        return ActiveActivity(
            event = event,
            mode = mode,
            effectPercent = tokens["effect"]?.toIntOrNull() ?: activityEffectPercent(mode),
            startMs = event.timestamp,
            durationMinutes = duration,
            tailMinutes = tail,
            requiredCarbs = tokens["requiredCarbs"]?.toIntOrNull() ?: 0,
            carbType = tokens["carbType"]?.takeUnless { it == "none" },
            plannedDurationMinutes = tokens["plannedDuration"]?.toIntOrNull() ?: duration
        )
    }

    private fun currentActivity(now: Long = System.currentTimeMillis()): ActiveActivity? =
        try {
            persistenceLayer.getTherapyEventDataFromToTime(
                now - TimeUnit.HOURS.toMillis(12),
                now + TimeUnit.HOURS.toMillis(6)
            ).blockingGet()
                .asSequence()
                .mapNotNull { parseActiveActivity(it) }
                .filter { activity ->
                    activity.startMs <= now + TimeUnit.HOURS.toMillis(6) &&
                        activity.tailEndMs >= now
                }
                .maxByOrNull { it.startMs }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Cannot read current AIMI activity", e)
            null
        }

    private fun activityStatusText(activity: ActiveActivity, now: Long = System.currentTimeMillis()): String {
        val startIn = ((activity.startMs - now) / 60_000.0).roundToInt()
        val activeLeft = ((activity.activeEndMs - now) / 60_000.0).roundToInt().coerceAtLeast(0)
        val tailLeft = ((activity.tailEndMs - now) / 60_000.0).roundToInt().coerceAtLeast(0)
        val elapsed = ((now - activity.startMs) / 60_000.0).roundToInt().coerceAtLeast(0)
        val phase = when {
            now < activity.startMs       -> "запланирована, старт через ${startIn.coerceAtLeast(0)} мин"
            now <= activity.activeEndMs  -> "идет ${elapsed} мин, активно еще $activeLeft мин"
            now <= activity.tailEndMs    -> "активная часть завершена, хвост еще $tailLeft мин"
            else                         -> "завершена"
        }
        return "Текущая нагрузка: ${activity.mode}, $phase. Хвост: ${activity.tailMinutes} мин."
    }

    private fun activityGlucoseUseMgdlPer5m(mode: String, effectPercent: Int): Double {
        val profile = profileFunction.getProfile()
        val basal = profile?.getBasal() ?: 0.0
        val isf = profile?.getIsfMgdl("Activity v2") ?: 0.0
        val insulinEquivalent = if (basal > 0.0 && isf > 0.0) basal * isf * (effectPercent / 100.0) / 12.0 else 0.0
        val movementUse = if (mode == "SPORT") 1.2 else 0.8
        val cap = if (mode == "SPORT") 5.0 else 3.5
        return (insulinEquivalent + movementUse).coerceIn(0.5, cap)
    }

    private fun activityPhaseAtMinute(minute: Int, startOffset: Int, duration: Int, tail: Int): Double =
        when {
            minute < startOffset -> 0.0
            minute <= startOffset + duration -> 1.0
            tail > 0 && minute <= startOffset + duration + tail ->
                (1.0 - (minute - startOffset - duration).toDouble() / tail.toDouble()).coerceIn(0.0, 1.0)
            else -> 0.0
        }

    private fun activityTotalUseMgdl(startOffset: Int, duration: Int, tail: Int, glucoseUseMgdlPer5m: Double): Double {
        val windowEnd = startOffset + duration + tail
        var total = 0.0
        var minute = 5
        while (minute <= windowEnd) {
            total += glucoseUseMgdlPer5m * activityPhaseAtMinute(minute, startOffset, duration, tail)
            minute += 5
        }
        return total
    }

    private fun buildActivityPlan(): ActivityPlan {
        val mode = selectedActivityMode()
        val effectPercent = activityEffectPercent(mode)
        val startOffset = selectedActivityStartOffset()
        val duration = selectedActivityDuration()
        val tail = activityTailMinutes(mode, duration)
        val windowEnd = startOffset + duration + tail
        val glucoseUse = activityGlucoseUseMgdlPer5m(mode, effectPercent)
        val baseForecast = activityBaseForecast(minSteps = (windowEnd / 5).coerceAtLeast(48))
        val adjustedForecast = activityAdjustedForecast(baseForecast, startOffset, duration, tail, glucoseUse)
        val forecastPoints = adjustedForecast.mapIndexed { index, value -> ((index + 1) * 5) to value }
        val minPoint = forecastPoints
            .filter { (minute, _) -> minute in max(5, startOffset)..windowEnd }
            .minByOrNull { (_, value) -> value }
        val lateMinPoint = forecastPoints
            .filter { (minute, _) -> minute > windowEnd }
            .minByOrNull { (_, value) -> value }
        val forecastMin = minPoint?.second ?: (glucoseStatusProvider.glucoseStatusData?.glucose ?: 0.0)
        val forecastMinMinute = minPoint?.first ?: 0
        val profile = profileFunction.getProfile()
        val target = profile?.getTargetMgdl() ?: forecastMin
        val overviewLowMark = profileUtil.convertToMgdl(preferences.get(UnitDoubleKey.OverviewLowMark), profileFunction.getUnits())
        val profileLowTarget = profile?.getTargetLowMgdl()?.takeIf { it.isFinite() && it > 0.0 } ?: overviewLowMark
        val activityCarbFloor = max(overviewLowMark + 10.0, profileLowTarget)
            .coerceAtMost(target)
        val isf = profile?.getIsfMgdl("Activity v2 carbs") ?: 0.0
        val ic = profile?.getIc() ?: 0.0
        val csf = if (isf > 0.0 && ic > 0.0) isf / ic else 0.0
        val baseDeficit = activityBaseDeficitMgdl(
            baseForecast = baseForecast,
            windowEnd = windowEnd,
            target = activityCarbFloor
        )
        val activityDeficit = activityAddedDeficitMgdl(
            baseForecast = baseForecast,
            adjustedForecast = adjustedForecast,
            startOffset = startOffset,
            windowEnd = windowEnd,
            target = activityCarbFloor
        )
        val totalDeficit = (baseDeficit + activityDeficit).coerceAtLeast(
            activityTotalDeficitMgdl(
                adjustedForecast = adjustedForecast,
                windowEnd = windowEnd,
                target = activityCarbFloor
            )
        )
        val baseRequiredCarbs = carbsForDeficitMgdl(baseDeficit, csf)
        val requiredCarbs = carbsForDeficitMgdl(totalDeficit, csf)
        val activityRequiredCarbs = (requiredCarbs - baseRequiredCarbs).coerceAtLeast(0)
        val firstBaseRiskMinute = firstDeficitMinute(
            baseForecast = baseForecast,
            windowEnd = windowEnd,
            target = activityCarbFloor
        )
        val firstActivityImpactMinute = firstActivityImpactMinute(
            baseForecast = baseForecast,
            adjustedForecast = adjustedForecast,
            startOffset = startOffset,
            windowEnd = windowEnd,
            target = activityCarbFloor
        )
        val firstRiskMinute = listOfNotNull(
            firstBaseRiskMinute.takeIf { baseRequiredCarbs > 0 },
            firstActivityImpactMinute.takeIf { activityRequiredCarbs > 0 }
        ).minOrNull()
        val carbType = selectedActivityCarbType()
        val carbLead = when (carbType) {
            "fast" -> 10
            "balanced" -> 25
            else -> 15
        }
        val within = if (requiredCarbs > 0) ((firstRiskMinute ?: forecastMinMinute) - carbLead).coerceIn(0, windowEnd) else 0
        val activityEquivalentCarbs = if (csf > 0.0) activityTotalUseMgdl(startOffset, duration, tail, glucoseUse) / csf else 0.0

        return ActivityPlan(
            mode = mode,
            effectPercent = effectPercent,
            startOffsetMinutes = startOffset,
            durationMinutes = duration,
            tailMinutes = tail,
            activityWindowEndMinutes = windowEnd,
            requiredCarbs = requiredCarbs,
            baseRequiredCarbs = baseRequiredCarbs,
            activityRequiredCarbs = activityRequiredCarbs,
            carbsWithinMinutes = within,
            carbType = carbType,
            forecastMin = forecastMin,
            forecastMinMinute = forecastMinMinute,
            lateForecastMin = lateMinPoint?.second,
            lateForecastMinMinute = lateMinPoint?.first,
            glucoseUseMgdlPer5m = glucoseUse,
            carbSensitivityMgdlPerGram = csf,
            activityEquivalentCarbs = activityEquivalentCarbs,
            activityCarbFloorMgdl = activityCarbFloor,
            baseDeficitMgdl = baseDeficit,
            activityDeficitMgdl = activityDeficit,
            totalDeficitMgdl = totalDeficit,
            firstRiskMinute = firstRiskMinute,
            firstActivityImpactMinute = firstActivityImpactMinute,
            carbLeadMinutes = carbLead
        )
    }

    private fun activityBaseForecast(minSteps: Int): List<Double> {
        val currentBg = glucoseStatusProvider.glucoseStatusData?.glucose ?: 0.0
        val base = loop.lastRun?.constraintsProcessed?.predictions()?.AIMI_FINAL
            ?.takeIf { it.isNotEmpty() }
            ?.map { it.toDouble() }
            ?: List(48) { currentBg }
        if (base.size >= minSteps) return base
        val lastValue = base.lastOrNull() ?: currentBg
        return base + List(minSteps - base.size) { lastValue }
    }

    private fun activityAdjustedForecast(base: List<Double>, startOffset: Int, duration: Int, tail: Int, glucoseUseMgdlPer5m: Double): List<Double> {
        var accumulatedUse = 0.0
        return base.mapIndexed { index, predicted ->
            val minute = (index + 1) * 5
            val phase = activityPhaseAtMinute(minute, startOffset, duration, tail)
            accumulatedUse += glucoseUseMgdlPer5m * phase
            predicted - accumulatedUse
        }
    }

    private fun activityBaseDeficitMgdl(
        baseForecast: List<Double>,
        windowEnd: Int,
        target: Double
    ): Double =
        baseForecast
            .mapIndexedNotNull { index, base ->
                val minute = (index + 1) * 5
                if (minute !in 5..windowEnd) null else (target - base).coerceAtLeast(0.0)
            }
            .maxOrNull()
            ?: 0.0

    private fun activityTotalDeficitMgdl(
        adjustedForecast: List<Double>,
        windowEnd: Int,
        target: Double
    ): Double =
        adjustedForecast
            .mapIndexedNotNull { index, adjusted ->
                val minute = (index + 1) * 5
                if (minute !in 5..windowEnd) null else (target - adjusted).coerceAtLeast(0.0)
            }
            .maxOrNull()
            ?: 0.0

    private fun activityAddedDeficitMgdl(
        baseForecast: List<Double>,
        adjustedForecast: List<Double>,
        startOffset: Int,
        windowEnd: Int,
        target: Double
    ): Double =
        baseForecast
            .zip(adjustedForecast)
            .mapIndexedNotNull { index, (base, adjusted) ->
                val minute = (index + 1) * 5
                if (minute !in max(5, startOffset)..windowEnd) {
                    null
                } else {
                    val baseDeficit = (target - base).coerceAtLeast(0.0)
                    val adjustedDeficit = (target - adjusted).coerceAtLeast(0.0)
                    (adjustedDeficit - baseDeficit).coerceAtLeast(0.0)
                }
            }
            .maxOrNull()
            ?: 0.0

    private fun carbsForDeficitMgdl(deficitMgdl: Double, csf: Double): Int =
        if (csf > 0.0 && deficitMgdl >= 3.0) ceil(deficitMgdl / csf).toInt().coerceAtMost(60) else 0

    private fun firstDeficitMinute(baseForecast: List<Double>, windowEnd: Int, target: Double): Int? =
        baseForecast
            .mapIndexedNotNull { index, base ->
                val minute = (index + 1) * 5
                if (minute in 5..windowEnd && target - base >= 3.0) minute else null
            }
            .minOrNull()

    private fun firstActivityImpactMinute(
        baseForecast: List<Double>,
        adjustedForecast: List<Double>,
        startOffset: Int,
        windowEnd: Int,
        target: Double
    ): Int? =
        baseForecast
            .zip(adjustedForecast)
            .mapIndexedNotNull { index, (base, adjusted) ->
                val minute = (index + 1) * 5
                val activeWindow = minute in max(5, startOffset)..windowEnd
                val addedDrop = base - adjusted
                val addedDeficit = (target - adjusted).coerceAtLeast(0.0) - (target - base).coerceAtLeast(0.0)
                if (activeWindow && (addedDeficit >= 3.0 || addedDrop >= 3.0)) minute else null
            }
            .minOrNull()

    private fun activityWatchSnapshot(): ActivityWatchSnapshot {
        val now = System.currentTimeMillis()
        val time5 = now - TimeUnit.MINUTES.toMillis(5)
        val time10 = now - TimeUnit.MINUTES.toMillis(10)
        val time60 = now - TimeUnit.MINUTES.toMillis(60)
        val steps = try {
            persistenceLayer.getStepsCountFromTimeToTime(time10, now).maxByOrNull { it.timestamp }
        } catch (_: Exception) {
            null
        }
        val hr5 = try {
            persistenceLayer.getHeartRatesFromTimeToTime(time5, now)
                .map { it.beatsPerMinute.toInt() }
                .takeIf { it.isNotEmpty() }
                ?.average()
        } catch (_: Exception) {
            null
        }
        val hr60 = try {
            persistenceLayer.getHeartRatesFromTimeToTime(time60, now)
                .map { it.beatsPerMinute.toInt() }
                .takeIf { it.isNotEmpty() }
                ?.average()
        } catch (_: Exception) {
            null
        }
        val steps5 = steps?.steps5min
        val steps10 = steps?.steps10min
        val hrLift = if (hr5 != null && hr60 != null) hr5 - hr60 else null
        val assessment = when {
            steps5 == null && hr5 == null -> "нет свежих данных часов"
            hrLift != null && hrLift > 15.0 && (steps10 ?: 0) < 100 -> "пульс высокий без шагов: только наблюдение"
            hrLift != null && hrLift > 10.0 && (steps10 ?: 0) >= 100 -> "пульс подтверждает нагрузку, но расчет не меняет"
            (steps5 ?: 0) >= 100 || (steps10 ?: 0) >= 200 -> "движение подтверждено, но расчет не меняет"
            else -> "только наблюдение"
        }
        return ActivityWatchSnapshot(
            steps5min = steps5,
            steps10min = steps10,
            avgHr5min = hr5,
            avgHr60min = hr60,
            assessment = assessment
        )
    }

    private fun formatOptionalInt(value: Int?): String = value?.toString() ?: "--"

    private fun formatOptionalHr(value: Double?): String =
        value?.takeIf { it.isFinite() }?.roundToInt()?.toString() ?: "--"

    private fun updateActivityV2Ui() {
        if (_binding == null || options != UiInteraction.EventType.EXERCISE) return
        val plan = buildActivityPlan()
        val watch = activityWatchSnapshot()
        val activeActivity = currentActivity()
        activityRequiredCarbs = plan.requiredCarbs
        activityCarbsWithinMinutes = plan.carbsWithinMinutes

        binding.activityCurrentLayout.visibility = (activeActivity != null).toVisibility()
        activeActivity?.let { activity ->
            val now = System.currentTimeMillis()
            binding.activityCurrentStatus.text = activityStatusText(activity, now)
            binding.activityEndNow.isEnabled = now >= activity.startMs && now <= activity.activeEndMs
            binding.activityCancel.isEnabled = true
        }

        val needsCarbs = plan.requiredCarbs > 0
        binding.activityCarbsLayout.visibility = needsCarbs.toVisibility()
        if (needsCarbs) {
            binding.activityCarbsNeeded.text =
                "Защитные углеводы: ${plan.requiredCarbs} г, принять через ${plan.carbsWithinMinutes} мин"
        } else {
            binding.activityConfirmCarbs.isChecked = false
        }

        val carbsConfirmed = !needsCarbs || (plan.carbType != null && binding.activityConfirmCarbs.isChecked)
        binding.okcancel.ok.isEnabled = carbsConfirmed
        val lateForecastMin = plan.lateForecastMin
        val lateTarget = profileFunction.getProfile()?.getTargetMgdl() ?: lateForecastMin
        val lateRiskText = if (!needsCarbs && lateForecastMin != null && lateTarget != null && lateForecastMin < lateTarget) {
            "Поздний минимум ${profileUtil.fromMgdlToStringInUnits(lateForecastMin)} через ${plan.lateForecastMinMinute} мин уже после хвоста; меню нагрузки его не блокирует.\n"
        } else {
            ""
        }
        binding.activityLog.text =
            "${plan.mode}: чувствительность к инсулину +${plan.effectPercent}%, базал/новая подача x${"%.2f".format(1.0 - plan.effectPercent / 100.0)}\n" +
                "Старт: ${if (plan.startOffsetMinutes == 0) "сейчас" else "через ${plan.startOffsetMinutes} мин"}, " +
                "длительность ${plan.durationMinutes} мин, хвост ${plan.tailMinutes} мин, окно до ${plan.activityWindowEndMinutes} мин\n" +
                "Расход глюкозы: ${"%.1f".format(plan.glucoseUseMgdlPer5m)} mg/dL каждые 5 мин в активной фазе\n" +
                "Углеводы: 1 г ≈ ${"%.1f".format(plan.carbSensitivityMgdlPerGram)} mg/dL, нагрузка съедает ≈ ${"%.1f".format(plan.activityEquivalentCarbs)} г за окно\n" +
                "Защитный пол для углеводов: ${profileUtil.fromMgdlToStringInUnits(plan.activityCarbFloorMgdl)}; цель APS остается ${profileUtil.fromMgdlToStringInUnits(profileFunction.getProfile()?.getTargetMgdl() ?: plan.activityCarbFloorMgdl)}\n" +
                "Базовый риск ниже пола: ${plan.baseRequiredCarbs} г (${"%.1f".format(plan.baseDeficitMgdl)} mg/dL)\n" +
                "Добавка от выбранной нагрузки ниже пола: ${plan.activityRequiredCarbs} г (${"%.1f".format(plan.activityDeficitMgdl)} mg/dL)\n" +
                "Итого защитные углеводы: ${plan.requiredCarbs} г (${"%.1f".format(plan.totalDeficitMgdl)} mg/dL), первый риск ${plan.firstRiskMinute?.let { "через $it мин" } ?: "не найден"}\n" +
                "Тип углеводов влияет только на время приема: быстрые раньше риска на 10 мин, обычные на 25 мин\n" +
                "Цвет прогноза: нагрузка желтая, хвост темно-желтый; ниже низкого порога остается красным\n" +
                "Часы (только наблюдение): шаги 5/10м ${formatOptionalInt(watch.steps5min)}/${formatOptionalInt(watch.steps10min)}, " +
                "пульс 5м ${formatOptionalHr(watch.avgHr5min)}, базовый ${formatOptionalHr(watch.avgHr60min)}; ${watch.assessment}\n" +
                "Прогноз в окне нагрузки: минимум ${profileUtil.fromMgdlToStringInUnits(plan.forecastMin)} через ${plan.forecastMinMinute} мин\n" +
                lateRiskText +
                if (needsCarbs) {
                    "ОК станет доступен после выбора типа углеводов и подтверждения."
                } else {
                    "Дополнительные углеводы по текущему прогнозу не нужны."
                }
    }

    private fun activityNote(plan: ActivityPlan): String =
        activityNote(
            mode = plan.mode,
            effectPercent = plan.effectPercent,
            startOffsetMinutes = plan.startOffsetMinutes,
            durationMinutes = plan.durationMinutes,
            tailMinutes = plan.tailMinutes,
            requiredCarbs = plan.requiredCarbs,
            carbType = plan.carbType
        )

    private fun endCurrentActivity() {
        val current = currentActivity()
        if (current == null) {
            ToastUtils.warnToast(ctx, "Нет активной нагрузки")
            updateActivityV2Ui()
            return
        }
        val now = System.currentTimeMillis()
        if (now < current.startMs) {
            ToastUtils.warnToast(ctx, "Нагрузка еще не началась, можно только отменить")
            updateActivityV2Ui()
            return
        }
        val actualDuration = ceil((now - current.startMs).toDouble() / 60_000.0).toInt().coerceAtLeast(5)
        val qualifiedDuration = qualifiedActivityDuration(actualDuration)
        val recalculatedTail = activityTailMinutes(current.mode, qualifiedDuration)
        val updatedNote = activityNote(
            mode = current.mode,
            effectPercent = current.effectPercent,
            startOffsetMinutes = 0,
            durationMinutes = actualDuration,
            tailMinutes = recalculatedTail,
            requiredCarbs = current.requiredCarbs,
            carbType = current.carbType
        ) + " status=ended plannedDuration=${current.plannedDurationMinutes} actualDuration=$actualDuration " +
            "qualifiedDuration=$qualifiedDuration endedAt=$now"
        val updatedEvent = current.event.copy(
            duration = T.mins(actualDuration.toLong()).msecs(),
            note = updatedNote
        )
        val actions = LinkedList<String>().apply {
            add("Завершить текущую нагрузку ${current.mode}")
            add("Фактически: $actualDuration мин")
            add("Классификация для хвоста: $qualifiedDuration мин")
            add("Новый хвост: $recalculatedTail мин")
        }
        activity?.let { activity ->
            OKDialog.showConfirmation(activity, rh.gs(app.aaps.core.ui.R.string.careportal_exercise), HtmlHelper.fromHtml(Joiner.on("<br/>").join(actions)), {
                disposable += persistenceLayer.insertOrUpdateTherapyEvent(updatedEvent)
                    .subscribe({
                        ToastUtils.okToast(ctx, "Нагрузка завершена")
                        loop.invoke("AIMI activity ended", allowNotification = false)
                        updateActivityV2Ui()
                    }, {
                        ToastUtils.warnToast(ctx, it.message ?: "Не удалось завершить нагрузку")
                    })
            }, null)
        }
    }

    private fun cancelCurrentActivity() {
        val current = currentActivity()
        if (current == null) {
            ToastUtils.warnToast(ctx, "Нет активной или запланированной нагрузки")
            updateActivityV2Ui()
            return
        }
        val actions = LinkedList<String>().apply {
            add("Отменить текущую нагрузку ${current.mode}")
            add("Событие будет исключено из расчета и прогноза")
            add("Желтые участки прогноза исчезнут после пересчета")
        }
        activity?.let { activity ->
            OKDialog.showConfirmation(activity, rh.gs(app.aaps.core.ui.R.string.careportal_exercise), HtmlHelper.fromHtml(Joiner.on("<br/>").join(actions)), {
                disposable += persistenceLayer.invalidateTherapyEvent(
                    id = current.event.id,
                    action = Action.CAREPORTAL,
                    source = Sources.Exercise,
                    note = "AIMI_ACTIVITY_V2 cancelled",
                    listValues = listOf(
                        ValueWithUnit.Timestamp(current.event.timestamp),
                        ValueWithUnit.TEType(current.event.type)
                    )
                ).subscribe({
                    ToastUtils.okToast(ctx, "Нагрузка отменена")
                    loop.invoke("AIMI activity cancelled", allowNotification = false)
                    updateActivityV2Ui()
                }, {
                    ToastUtils.warnToast(ctx, it.message ?: "Не удалось отменить нагрузку")
                })
            }, null)
        }
    }

    private fun submitActivityV2(): Boolean {
        val plan = buildActivityPlan()
        if (plan.requiredCarbs > 0 && (plan.carbType == null || !binding.activityConfirmCarbs.isChecked)) {
            updateActivityV2Ui()
            return false
        }

        val startTime = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(plan.startOffsetMinutes.toLong())
        val note = activityNote(plan)
        val therapyEvent = TE(
            timestamp = startTime - startTime % 1000,
            type = TE.Type.EXERCISE,
            glucoseUnit = profileFunction.getUnits(),
            duration = T.mins(plan.durationMinutes.toLong()).msecs(),
            exerciseDuty = if (plan.mode == "SPORT") TE.ExerciseDuty.MIDDLE else TE.ExerciseDuty.LIGHT,
            note = note,
            enteredBy = "AndroidAPS"
        )
        val actions = LinkedList<String>().apply {
            add("Нагрузка: ${plan.mode}")
            add("Старт: ${if (plan.startOffsetMinutes == 0) "сейчас" else "через ${plan.startOffsetMinutes} мин"}")
            add("Длительность: ${plan.durationMinutes} мин")
            add("Эффект: ISF +${plan.effectPercent}%, basal/new insulin x${"%.2f".format(1.0 - plan.effectPercent / 100.0)}")
            add("Хвост: ${plan.tailMinutes} мин")
            add("На графике: нагрузка желтая, хвост темно-желтый")
            add("Расход: ${"%.1f".format(plan.glucoseUseMgdlPer5m)} mg/dL/5 мин")
            if (plan.requiredCarbs > 0) {
                add("Защитные углеводы: ${plan.requiredCarbs} г, ${if (plan.carbType == "fast") "быстрые" else "обычные"}, через ${plan.carbsWithinMinutes} мин")
            }
            add(rh.gs(R.string.confirm_treatment))
        }

        activity?.let { activity ->
            OKDialog.showConfirmation(activity, rh.gs(app.aaps.core.ui.R.string.careportal_exercise), HtmlHelper.fromHtml(Joiner.on("<br/>").join(actions)), {
                valuesWithUnit.add(ValueWithUnit.Timestamp(therapyEvent.timestamp))
                valuesWithUnit.add(ValueWithUnit.TEType(therapyEvent.type))
                valuesWithUnit.add(ValueWithUnit.Minute(plan.durationMinutes))
                disposable += persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                    therapyEvent = therapyEvent,
                    action = Action.CAREPORTAL,
                    source = Sources.Exercise,
                    note = note,
                    listValues = valuesWithUnit.filterNotNull()
                ).subscribe({
                    ToastUtils.okToast(ctx, "Нагрузка учтена")
                    loop.invoke("AIMI activity started", allowNotification = false)
                    rxBus.send(EventRefreshOverview("AIMI activity started", now = true))
                }, {
                    ToastUtils.warnToast(ctx, it.message ?: "Не удалось записать нагрузку")
                })

                if (plan.requiredCarbs > 0) {
                    val carbsTimestamp = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(plan.carbsWithinMinutes.toLong())
                    val detailedBolusInfo = DetailedBolusInfo().also {
                        it.eventType = TE.Type.CARBS_CORRECTION
                        it.carbs = plan.requiredCarbs.toDouble()
                        it.context = context
                        it.notes = "AIMI_ACTIVITY_V2_CARBS type=${plan.carbType} for=${plan.mode} activity"
                        it.carbsTimestamp = carbsTimestamp
                        it.timestamp = carbsTimestamp
                    }
                    commandQueue.bolus(detailedBolusInfo, object : Callback() {
                        override fun run() {
                            if (!result.success) {
                                ToastUtils.warnToast(ctx, result.comment)
                            }
                        }
                    })
                }
            }, null)
        }
        return true
    }

    override fun submit(): Boolean {
        // from ProfileSwitchDialog
        if (_binding == null) return false

        if (options == UiInteraction.EventType.EXERCISE) {
            return submitActivityV2()
        }

        val profileStore = activePlugin.activeProfileSource.profile ?: return false

        val actions: LinkedList<String> = LinkedList()

        val duration = binding.duration.value.toInt()

        val profileName = binding.profileList.text.toString()
        actions.add(rh.gs(app.aaps.core.ui.R.string.profile) + ": " + profileName)

        val percent = binding.percentage.value.toInt()
        if (percent != 100) {
            actions.add(rh.gs(app.aaps.core.ui.R.string.percent) + ": " + percent + "%")
        }

        val timeShift = binding.timeshift.value.toInt() // timeShift is given in Minutes here
        Log.d(TAG, "timeShift = $timeShift")
        if (timeShift != 0) {
            actions.add(
                rh.gs(R.string.timeshift_label) + ": " +
                    rh.gs(app.aaps.core.ui.R.string.format_hours, timeShift.toDouble())
            )
        }

        val isTT = binding.duration.value > 0 && binding.percentage.value < 100 && binding.tt.isChecked
        val target = preferences.get(UnitDoubleKey.OverviewActivityTarget)
        val units = profileFunction.getUnits()

        if (isTT) {
            actions.add(
                rh.gs(app.aaps.core.ui.R.string.temporary_target) + ": " +
                    rh.gs(app.aaps.core.ui.R.string.activity)
            )
        }
        ////

        val enteredBy = "AndroidAPS"
        val unitResId = if (profileFunction.getUnits() == GlucoseUnit.MGDL) {
            app.aaps.core.ui.R.string.mgdl
        } else {
            app.aaps.core.ui.R.string.mmol
        }

        eventTime -= eventTime % 1000

        // sargius added
        val exerciseDutyOption = if (binding.switchDutyOptions.isChecked) {
            if (binding.dutyLight.isChecked) {
                TE.ExerciseDuty.LIGHT
            } else if (binding.dutyMiddle.isChecked) {
                TE.ExerciseDuty.MIDDLE
            } else if (binding.dutyHeavy.isChecked) {
                TE.ExerciseDuty.HEAVY
            } else {
                TE.ExerciseDuty.NONE
            }

        } else {
            TE.ExerciseDuty.NONE
        }
        Log.d(TAG, "exerciseDutyOption = $exerciseDutyOption")

        val therapyEvent = TE(
            timestamp = eventTime,
            type = when (options) {
                UiInteraction.EventType.BGCHECK        -> TE.Type.FINGER_STICK_BG_VALUE
                UiInteraction.EventType.SENSOR_INSERT  -> TE.Type.SENSOR_CHANGE
                UiInteraction.EventType.BATTERY_CHANGE -> TE.Type.PUMP_BATTERY_CHANGE
                UiInteraction.EventType.NOTE           -> TE.Type.NOTE
                UiInteraction.EventType.EXERCISE       -> TE.Type.EXERCISE
                UiInteraction.EventType.QUESTION       -> TE.Type.QUESTION
                UiInteraction.EventType.ANNOUNCEMENT   -> TE.Type.ANNOUNCEMENT
            },
            glucoseUnit = profileFunction.getUnits(),
            exerciseDuty = exerciseDutyOption
        )

        // val actions: LinkedList<String> = LinkedList(), // moved up
        actions.add(rh.gs(R.string.confirm_treatment))

        if (options == UiInteraction.EventType.BGCHECK ||
            options == UiInteraction.EventType.QUESTION ||
            options == UiInteraction.EventType.ANNOUNCEMENT
        ) {
            val meterType =
                when {
                    binding.meter.isChecked  -> TE.MeterType.FINGER
                    binding.sensor.isChecked -> TE.MeterType.SENSOR
                    else                     -> TE.MeterType.MANUAL
                }

            actions.add(rh.gs(R.string.glucose_type) + ": " + translator.translate(meterType))
            actions.add(
                rh.gs(app.aaps.core.ui.R.string.bg_label) + ": " +
                    profileUtil.stringInCurrentUnitsDetect(binding.bg.value) + " " + rh.gs(unitResId)
            )
            therapyEvent.glucoseType = meterType
            therapyEvent.glucose = binding.bg.value
            valuesWithUnit.add(ValueWithUnit.fromGlucoseUnit(binding.bg.value, profileFunction.getUnits()))
            valuesWithUnit.add(ValueWithUnit.TEMeterType(meterType))
        }

        if (options == UiInteraction.EventType.NOTE || options == UiInteraction.EventType.EXERCISE) {
            if (duration > 0L) {
                actions.add(rh.gs(app.aaps.core.ui.R.string.duration_label) + ": " + rh.gs(app.aaps.core.ui.R.string.format_mins, binding.duration.value.toInt()))
                therapyEvent.duration = T.mins(binding.duration.value.toLong()).msecs()
                valuesWithUnit.add(ValueWithUnit.Minute(binding.duration.value.toInt()).takeIf { !binding.duration.value.equals(0.0) })
            }
        }

        // sargius added
        if (options == UiInteraction.EventType.EXERCISE) {
            val exerciseDuty = when {
                binding.dutyLight.isChecked  -> TE.ExerciseDuty.LIGHT
                binding.dutyMiddle.isChecked  -> TE.ExerciseDuty.MIDDLE
                binding.dutyHeavy.isChecked  -> TE.ExerciseDuty.HEAVY

                else -> TE.ExerciseDuty.NONE
            }

            actions.add(rh.gs(R.string.sport_duty_options_label) + ": " + translator.translate(exerciseDuty))
            therapyEvent.exerciseDuty = exerciseDuty
            Log.d(TAG, "1. exerciseDuty = $exerciseDuty")
        }

        val notes = binding.notesLayout.notes.text.toString()
        if (notes.isNotEmpty()) {
            actions.add(rh.gs(app.aaps.core.ui.R.string.notes_label) + ": " + notes)
            therapyEvent.note = notes
        }

        if (eventTimeChanged) {
            actions.add(rh.gs(app.aaps.core.ui.R.string.time) + ": " + dateUtil.dateAndTimeString(eventTime))
        }

        therapyEvent.enteredBy = enteredBy

        val source = when (options) {
            UiInteraction.EventType.BGCHECK        -> Sources.BgCheck
            UiInteraction.EventType.SENSOR_INSERT  -> Sources.SensorInsert
            UiInteraction.EventType.BATTERY_CHANGE -> Sources.BatteryChange
            UiInteraction.EventType.NOTE           -> Sources.Note
            UiInteraction.EventType.EXERCISE       -> Sources.Exercise
            UiInteraction.EventType.QUESTION       -> Sources.Question
            UiInteraction.EventType.ANNOUNCEMENT   -> Sources.Announcement
        }

        activity?.let { activity ->
            // from ProfileSwitchDialog, pack activity?.let {... into validity checking
            val ps = profileFunction.buildProfileSwitch2(profileStore, profileName, duration, percent, timeShift, eventTime) ?: return@let

            val validity = ProfileSealed.PS(ps, activePlugin).isValid(rh.gs(app.aaps.core.ui.R.string.careportal_profileswitch), activePlugin.activePump, config, rh, rxBus, hardLimits, false)
            if (validity.isValid) {
                OKDialog.showConfirmation(activity, rh.gs(event), HtmlHelper.fromHtml(Joiner.on("<br/>").join(actions)), {

                    // old method
                    valuesWithUnit.add(0, ValueWithUnit.Timestamp(eventTime).takeIf { eventTimeChanged })
                    valuesWithUnit.add(1, ValueWithUnit.TEType(therapyEvent.type))
                    Log.d(TAG, "insert therapyEvent by insertPumpTherapyEventIfNewByTimestamp(), therapyEvent = $therapyEvent")
                    disposable += persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                        therapyEvent = therapyEvent,
                        action = Action.CAREPORTAL,
                        source = source,
                        note = notes,
                        listValues = valuesWithUnit.filterNotNull()
                    ).subscribe()

                    // transfer from ProfileSwitchDialog, converted for timeshift in Minutes
                    if (profileFunction.createProfileSwitch2(
                            profileStore = profileStore,
                            profileName = profileName,
                            durationInMinutes = duration,
                            percentage = percent,
                            timeShiftInMinutes = timeShift,
                            timestamp = eventTime,
                            action = Action.PROFILE_SWITCH,
                            source = Sources.ProfileSwitchDialog,
                            note = notes,
                            listValues = listOf(
                                ValueWithUnit.Timestamp(eventTime).takeIf { eventTimeChanged },
                                ValueWithUnit.SimpleString(profileName),
                                ValueWithUnit.Percent(percent),
                                ValueWithUnit.Minute(timeShift).takeIf { timeShift != 0 },
                                ValueWithUnit.Minute(duration).takeIf { duration != 0 }
                            ).filterNotNull()
                        )
                    ) {
                        // флаг «объективка выполнена» в новом стиле
                        // Allexey added удалил флаг зачем то
                        // if (percent == 90 && duration == 10) {
                        //     preferences.put(BooleanKey.ObjectiveUseProfileSwitch, true)
                        // }

                        if (isTT) {
                            disposable += persistenceLayer.insertAndCancelCurrentTemporaryTarget(
                                TT(
                                    timestamp = eventTime,
                                    duration = TimeUnit.MINUTES.toMillis(duration.toLong()),
                                    reason = TT.Reason.ACTIVITY,
                                    lowTarget = profileUtil.convertToMgdl(target, profileFunction.getUnits()),
                                    highTarget = profileUtil.convertToMgdl(target, profileFunction.getUnits())
                                ),
                                action = Action.TT,
                                source = Sources.TTDialog,
                                note = null,
                                listValues = listOf(
                                    ValueWithUnit.Timestamp(eventTime).takeIf { eventTimeChanged },
                                    ValueWithUnit.TETTReason(TT.Reason.ACTIVITY),
                                    ValueWithUnit.fromGlucoseUnit(target, units),
                                    ValueWithUnit.Minute(duration)
                                ).filterNotNull()
                            ).subscribe()

                        } else { // cancel temporary target if "tt" (Activity checkbox) not checked
                            disposable += persistenceLayer.cancelCurrentTemporaryTargetIfAny(
                                timestamp = eventTime,
                                action = Action.TT,
                                source = Sources.TTDialog,
                                note = null,
                                listValues = listOf()
                            ).subscribe()
                        }
                    }

                }, null)

            } else {
                OKDialog.show(
                    activity,
                    rh.gs(app.aaps.core.ui.R.string.careportal_profileswitch),
                    HtmlHelper.fromHtml(Joiner.on("<br/>").join(validity.reasons))
                )

                return false
            }
        }

        return true
    }


    // from ProfileSwitchDialog
    private val textWatcher: TextWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable) {
            _binding?.let { binding ->
                val isDuration = binding.duration.value > 0
                val isLowerPercentage = binding.percentage.value < 100
                binding.ttLayout.visibility = (isDuration && isLowerPercentage).toVisibility()
                Log.d(TAG, "isDuration = $isDuration, isLowerPercentage = $isLowerPercentage")
            }
        }

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
    }
}
