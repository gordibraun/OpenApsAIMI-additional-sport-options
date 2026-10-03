package app.aaps.pump.combowatch

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A tab on the main screen with one switch: is the Accu-Chek Combo driven through the watch, or
 * by the phone directly. The two are two pump drivers in the Config Builder, where a pump cannot
 * be "unticked", only replaced by another, and that is not where somebody wants to be with a
 * pump in hand. The tab does the replacing and says what has to happen next: the pump pairs
 * with one device at a time, so after the switch it must be paired anew - with the phone, or
 * with the watch.
 */
@Singleton
class ComboModePlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .fragmentClass(ComboModeFragment::class.qualifiedName)
        .enableByDefault(true)
        .visibleByDefault(true)
        .simpleModePosition(PluginDescription.Position.TAB)
        .pluginIcon(app.aaps.core.objects.R.drawable.ic_virtual_pump)
        .pluginName(R.string.combomode_plugin_name)
        .shortName(R.string.combomode_plugin_shortname)
        .description(R.string.combomode_plugin_description),
    aapsLogger, rh
)
