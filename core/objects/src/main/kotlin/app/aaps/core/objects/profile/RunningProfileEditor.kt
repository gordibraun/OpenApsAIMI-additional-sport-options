package app.aaps.core.objects.profile

import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Changes named hours of the local profile that is running - basal, carb ratio, target - then saves it and
 * activates it: the same two steps as «Сохранить» and «Активировать профиль» on the profile screen, so the pump
 * is written the usual way (and, with the watch as the pump's controller, through the watch).
 *
 * It refuses when the profile shown in the editor is not the one running: what would be saved is the editor's
 * profile, and activating another one under the running one's name is not what anybody asked for.
 */
@Singleton
class RunningProfileEditor @Inject constructor(
    private val activePlugin: ActivePlugin,
    private val profileFunction: ProfileFunction
) {

    /** Hour from midnight to the new value; a line left empty is not touched. The target moves low and high together. */
    class Edit(val basal: Map<Int, Double> = emptyMap(), val carbRatio: Map<Int, Double> = emptyMap(), val target: Map<Int, Double> = emptyMap()) {

        val isEmpty get() = basal.isEmpty() && carbRatio.isEmpty() && target.isEmpty()
    }

    /** What an edit would do: every line by the hour, before and after. */
    class Plan(
        val profileName: String, val mgdl: Boolean,
        val basalBefore: List<Double>, val basalAfter: List<Double>,
        val carbRatioBefore: List<Double>, val carbRatioAfter: List<Double>,
        val targetLowBefore: List<Double>, val targetLowAfter: List<Double>,
        val targetHighBefore: List<Double>, val targetHighAfter: List<Double>
    ) {

        val changesBasal get() = basalBefore != basalAfter
        val changesCarbRatio get() = carbRatioBefore != carbRatioAfter
        val changesTarget get() = targetLowBefore != targetLowAfter || targetHighBefore != targetHighAfter
    }

    /**
     * What [edit] would do to the running profile.
     * @param onPumpGrid whether the pump can hold this basal rate as it is; asked of the hours being changed only -
     * the others stay as the owner wrote them
     * @throws IllegalStateException with the reason, when the edit cannot or may not be made
     */
    fun plan(edit: Edit, onPumpGrid: (Double) -> Boolean): Plan {
        check(!edit.isEmpty) { "nothing to change" }
        val current = checkNotNull(activePlugin.activeProfileSource.currentProfile()) { "no local profile is open in the editor" }
        val running = profileFunction.getOriginalProfileName()
        check(current.name == running) { "the editor shows «${current.name}», the running profile is «$running»" }
        val basal = checkNotNull(ProfileHours.hourly(current.basal)) { "the profile's basal blocks cannot be read: ${current.basal}" }
        val carbRatio = checkNotNull(ProfileHours.hourly(current.ic)) { "the profile's carb ratio blocks cannot be read: ${current.ic}" }
        val low = checkNotNull(ProfileHours.hourly(current.targetLow)) { "the profile's target blocks cannot be read: ${current.targetLow}" }
        val high = checkNotNull(ProfileHours.hourly(current.targetHigh)) { "the profile's target blocks cannot be read: ${current.targetHigh}" }
        ProfileHours.Limits.basal(basal, edit.basal, onPumpGrid)?.let { error(it) }
        ProfileHours.Limits.carbRatio(carbRatio, edit.carbRatio)?.let { error(it) }
        ProfileHours.Limits.target(low, high, edit.target, current.mgdl)?.let { error(it) }
        return Plan(
            profileName = current.name, mgdl = current.mgdl,
            basalBefore = basal, basalAfter = ProfileHours.apply(basal, edit.basal),
            carbRatioBefore = carbRatio, carbRatioAfter = ProfileHours.apply(carbRatio, edit.carbRatio),
            targetLowBefore = low, targetLowAfter = ProfileHours.apply(low, edit.target),
            targetHighBefore = high, targetHighAfter = ProfileHours.apply(high, edit.target)
        )
    }

    /**
     * Saves the planned lines into the editor's profile and activates it. The plan must be fresh: it is checked
     * once more against the profile as it stands, so that a plan made before somebody edited the profile by
     * hand is not written over that edit.
     * @return whether the profile switch was created
     */
    fun apply(plan: Plan, note: String, nowEpochMs: Long): Boolean {
        val source = activePlugin.activeProfileSource
        val current = checkNotNull(source.currentProfile()) { "no local profile is open in the editor" }
        check(current.name == plan.profileName && current.name == profileFunction.getOriginalProfileName()) { "the running profile is no longer «${plan.profileName}»" }
        check(
            ProfileHours.hourly(current.basal) == plan.basalBefore && ProfileHours.hourly(current.ic) == plan.carbRatioBefore &&
                ProfileHours.hourly(current.targetLow) == plan.targetLowBefore && ProfileHours.hourly(current.targetHigh) == plan.targetHighBefore
        ) { "the profile has been changed since this was worked out" }
        if (plan.changesBasal) current.basal = ProfileHours.blocks(plan.basalAfter)
        if (plan.changesCarbRatio) current.ic = ProfileHours.blocks(plan.carbRatioAfter)
        if (plan.changesTarget) {
            current.targetLow = ProfileHours.blocks(plan.targetLowAfter)
            current.targetHigh = ProfileHours.blocks(plan.targetHighAfter)
        }
        source.storeSettings(null, nowEpochMs)
        val store = checkNotNull(source.profile) { "the profile store is empty after saving" }
        return profileFunction.createProfileSwitch(
            profileStore = store, profileName = current.name, durationInMinutes = 0, percentage = 100, timeShiftInHours = 0, timestamp = nowEpochMs,
            action = Action.PROFILE_SWITCH, source = Sources.Maintenance, note = note,
            listValues = listOf(ValueWithUnit.SimpleString(current.name), ValueWithUnit.Percent(100))
        )
    }
}
