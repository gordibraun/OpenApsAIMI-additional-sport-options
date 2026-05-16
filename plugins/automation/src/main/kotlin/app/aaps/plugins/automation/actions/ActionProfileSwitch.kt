package app.aaps.plugins.automation.actions

import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.elements.InputProfileName
import app.aaps.plugins.automation.elements.LabelWithElement
import app.aaps.plugins.automation.elements.LayoutBuilder
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import javax.inject.Inject

class ActionProfileSwitch(injector: HasAndroidInjector) : Action(injector) {

    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var dateUtil: DateUtil

    var inputProfileName: InputProfileName = InputProfileName(rh, activePlugin, "")

    override fun friendlyName(): Int = R.string.profilename
    override fun shortDescription(): String = rh.gs(R.string.changengetoprofilename, inputProfileName.value)
    @DrawableRes override fun icon(): Int = app.aaps.core.ui.R.drawable.ic_actions_profileswitch_24dp

    override fun doAction(callback: Callback) {
        val profileStore = activePlugin.activeProfileSource.profile ?: return
        val requestedProfileName = inputProfileName.value
        val profileName = resolveProfileName()
        if (profileName == null) {
            aapsLogger.error(LTag.AUTOMATION, "Selected profile does not exist! - $requestedProfileName")
            callback.result(pumpEnactResultProvider.get().success(false).comment(app.aaps.core.ui.R.string.notexists)).run()
            return
        }
        if (requestedProfileName != profileName) {
            aapsLogger.warn(
                LTag.AUTOMATION,
                "Selected profile '$requestedProfileName' does not exist, falling back to '$profileName'"
            )
        }
        val activeProfileName = profileFunction.getProfileName()
        val activeProfile = profileFunction.getProfile()
        //Check for uninitialized profileName
        if (profileName == "") {
            aapsLogger.error(LTag.AUTOMATION, "Selected profile not initialized")
            callback.result(pumpEnactResultProvider.get().success(false).comment(app.aaps.core.validators.R.string.error_field_must_not_be_empty)).run()
            return
        }
        if (activeProfile == null) {
            aapsLogger.error(LTag.AUTOMATION, "ProfileFunctions not initialized")
            callback.result(pumpEnactResultProvider.get().success(false).comment(app.aaps.core.ui.R.string.noprofile)).run()
            return
        }
        if (profileName == activeProfileName && activeProfile.percentage == 100) {
            aapsLogger.debug(LTag.AUTOMATION, "Profile is already switched")
            callback.result(pumpEnactResultProvider.get().success(true).comment(R.string.alreadyset)).run()
            return
        }
        val result = profileFunction.createProfileSwitch(
            profileStore = profileStore,
            profileName = profileName,
            durationInMinutes = 0,
            percentage = 100,
            timeShiftInHours = 0,
            timestamp = dateUtil.now(), action = app.aaps.core.data.ue.Action.PROFILE_SWITCH,
            source = Sources.Automation,
            note = title,
            listValues = listOf(
                ValueWithUnit.SimpleString(profileName),
                ValueWithUnit.Percent(100)
            )
        )
        callback.result(pumpEnactResultProvider.get().success(result).comment(app.aaps.core.ui.R.string.ok)).run()
    }

    private fun resolveProfileName(): String? {
        val profileStore = activePlugin.activeProfileSource.profile ?: return null
        val requested = inputProfileName.value
        if (requested.isNotBlank() && profileStore.getSpecificProfile(requested) != null) return requested

        val original = profileFunction.getOriginalProfileName()
        if (!original.isNullOrBlank() && profileStore.getSpecificProfile(original) != null) return original

        val active = profileFunction.getProfileName()
        return active.takeIf { profileStore.getSpecificProfile(it) != null }
    }

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(LabelWithElement(rh, rh.gs(R.string.profilename), "", inputProfileName))
            .build(root)
    }

    override fun hasDialog(): Boolean = true

    override fun toJSON(): String {
        val data = JSONObject().put("profileToSwitchTo", inputProfileName.value)
        return JSONObject()
            .put("type", this.javaClass.simpleName)
            .put("data", data)
            .toString()
    }

    override fun fromJSON(data: String): Action {
        val o = JSONObject(data)
        inputProfileName.value = JsonHelper.safeGetString(o, "profileToSwitchTo", "")
        return this
    }

    override fun isValid(): Boolean = resolveProfileName() != null
}
