package app.aaps.plugins.aps.openAPSAIMI.bridge

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.data.plugin.PluginType
import app.aaps.plugins.aps.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Связь»: the owner writes to Claude what is wrong - text, photo, screenshot - and the phone adds
 * its data pack and the watch's; without a network it waits; the reply comes back into the same
 * thread and to Telegram. See [BridgeFragment] (screen), [BridgeStore] (record), [BridgePack]
 * (what is attached), [BridgeUploadWorker] (network).
 */
@Singleton
class BridgePlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val store: BridgeStore
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .fragmentClass(BridgeFragment::class.java.name)
        .pluginIcon(app.aaps.core.objects.R.drawable.ic_cp_announcement)
        .pluginName(R.string.bridge_tab)
        .shortName(R.string.bridge_tab_short)
        .enableByDefault(true)
        .visibleByDefault(true)
        .description(R.string.bridge_description),
    aapsLogger, rh
) {

    override fun onStart() {
        super.onStart()
        runCatching { store.schedulePoll() }
    }
}
