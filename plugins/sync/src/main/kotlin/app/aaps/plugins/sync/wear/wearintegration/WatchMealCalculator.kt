package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.AimiMealAssist
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.activity.ActivityBolusContextCalculator
import app.aaps.core.objects.aps.ApsDecisionSnapshot
import app.aaps.core.objects.extensions.valueToUnits
import app.aaps.core.objects.wizard.BolusWizard
import javax.inject.Inject
import javax.inject.Provider

class WatchMealCalculator @Inject constructor(
    private val profileFunction: ProfileFunction,
    private val iobCobCalculator: IobCobCalculator,
    private val persistence: PersistenceLayer,
    private val loop: Loop,
    private val mealAssist: AimiMealAssist,
    private val preferences: Preferences,
    private val date: DateUtil,
    private val config: Config,
    private val wizardProvider: Provider<BolusWizard>
) {
    fun calculate(carbs: Int, foodType: String): BolusWizard {
        require(carbs > 0 && foodType in setOf("fast", "balanced", "slow"))
        val now = date.now()
        val profile = profileFunction.getProfile() ?: error("Нет активного профиля.")
        val bg = iobCobCalculator.ads.actualBg() ?: error("Нет свежей глюкозы. Только углеводы можно записать отдельно.")
        require(now - bg.timestamp in 0..T.mins(6).msecs()) { "Глюкоза устарела. Проверьте телефон." }
        val cob = iobCobCalculator.getCobInfo("Watch meal wizard").displayCob ?: error("Расчет углеводов еще обновляется.")
        require(cob.isFinite() && cob >= 0) { "Некорректные данные углеводов." }
        val activity = ActivityBolusContextCalculator.calculate(
            persistence.getTherapyEventDataFromToTime(now - T.hours(12).msecs(), now + T.hours(6).msecs()).blockingGet(), now)
        val snapshot = ApsDecisionSnapshot.fromLoop(loop, mealAssist, persistence, now)
        val tempTarget = persistence.getTemporaryTargetActiveAt(now)
        // Match the phone wizard's default switches and saved COB/trend preferences.
        val wizard = wizardProvider.get().doCalc(
            profile = profile, profileName = profileFunction.getProfileName(), tempTarget = tempTarget,
            carbs = carbs, cob = cob, bg = bg.valueToUnits(profileFunction.getUnits()), correction = 0.0,
            percentageCorrection = preferences.get(IntKey.OverviewBolusPercentage),
            useBg = true, useCob = preferences.get(BooleanKey.WizardIncludeCob),
            includeBolusIOB = true, includeBasalIOB = true, useSuperBolus = false, useTT = tempTarget != null,
            useTrend = preferences.get(BooleanKey.WizardIncludeTrend), useAlarm = false,
            notes = "С часов", selectedFoodType = foodType,
            forecastRequiredCarbs = snapshot.carbs.takeIf { config.APS },
            forecastRequiredCarbsSource = if (config.APS) "APS.carbsReq" else "AIMI_FINAL",
            activityNewInsulinFactor = activity?.factor ?: 1.0, activityDescription = activity?.description
        )
        require(wizard.insulinAfterConstraints.isFinite() && wizard.insulinAfterConstraints >= 0) { "Некорректный расчет инсулина." }
        if (wizard.insulinAfterConstraints > 0) require(wizard.hasConfirmationInputs) { "Расчет еще обновляется. Повторите после обновления." }
        return wizard
    }
}
