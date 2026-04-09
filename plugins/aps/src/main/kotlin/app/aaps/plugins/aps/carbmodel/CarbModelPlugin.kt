package app.aaps.plugins.aps.carbmodel

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CarbModelPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    config: Config
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .fragmentClass(CarbModelFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_generic_icon)
        .pluginName(app.aaps.plugins.aps.R.string.carb_model_title)
        .shortName(app.aaps.plugins.aps.R.string.carb_model_shortname)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .preferencesVisibleInSimpleMode(false)
        .showInList { config.APS }
        .alwaysEnabled(true)
        .alwaysVisible(true)
        .simpleModePosition(PluginDescription.Position.TAB)
        .description(app.aaps.plugins.aps.R.string.carb_model_description),
    aapsLogger,
    rh
)
