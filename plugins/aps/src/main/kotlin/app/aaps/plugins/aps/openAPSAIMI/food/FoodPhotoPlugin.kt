package app.aaps.plugins.aps.openAPSAIMI.food

import android.content.Context
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.StringKey
import app.aaps.core.validators.preferences.AdaptiveStringPreference
import app.aaps.core.data.plugin.PluginType
import app.aaps.plugins.aps.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Еда по фото»: the owner photographs the plate, the server (the same Claude that reads the
 * Telegram bot's photos) says how many carbohydrates are on it, and the owner either eats it - the
 * grams go straight into the bolus wizard, with the photo's id in the note - corrects the number,
 * or declines. A photo is never a meal by itself. See [FoodPhotoFragment] for the screen,
 * [FoodPhotoStore] for the record, [FoodUploadWorker] for the network.
 */
@Singleton
class FoodPhotoPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .fragmentClass(FoodPhotoFragment::class.java.name)
        .pluginIcon(app.aaps.core.objects.R.drawable.ic_food)
        .pluginName(R.string.food_photo)
        .shortName(R.string.food_photo_short)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .enableByDefault(true)
        .visibleByDefault(true)
        .description(R.string.food_photo_description),
    aapsLogger, rh
) {

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null) return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "food_photo_settings"
            title = rh.gs(R.string.food_photo)
            initialExpandedChildrenCount = Int.MAX_VALUE
            addPreference(AdaptiveStringPreference(context, null, StringKey.FoodApiUrl, null, R.string.food_api_url_summary, R.string.food_api_url_title))
            addPreference(AdaptiveStringPreference(context, null, StringKey.FoodApiToken, null, R.string.food_api_token_summary, R.string.food_api_token_title))
        }
    }
}
