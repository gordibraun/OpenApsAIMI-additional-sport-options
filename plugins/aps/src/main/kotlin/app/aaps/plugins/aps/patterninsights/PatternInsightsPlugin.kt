package app.aaps.plugins.aps.patterninsights

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.plugins.aps.R
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PatternInsightsPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    config: Config
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .fragmentClass(PatternInsightsFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_generic_icon)
        .pluginName(R.string.pattern_insights_tab_title)
        .shortName(R.string.pattern_insights_shortname)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .preferencesVisibleInSimpleMode(false)
        .showInList({ config.APS })
        .alwaysEnabled(true)
        .alwaysVisible(true)
        .simpleModePosition(PluginDescription.Position.TAB)
        .description(R.string.pattern_insights_description)
        .setDefault(),
    aapsLogger,
    rh
)
