package app.aaps.pump.combowatch

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.combowatch.protocol.ComboResult
import app.aaps.pump.combowatch.protocol.ComboWatchProtocol
import app.aaps.pump.combowatch.protocol.WatchHeartbeat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dagger.android.AndroidInjection
import org.json.JSONObject
import javax.inject.Inject

/**
 * Receives the watch's answers and heartbeats.
 *
 * Declared with its own paths so it runs alongside the existing AAPS wear listener rather than
 * replacing it: the companion's own traffic keeps flowing through the service it always used.
 */
class ComboWatchListenerService : WearableListenerService() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var link: ComboWatchLink

    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            ComboWatchProtocol.PATH_RESULT    -> parse(messageEvent) { link.onResult(ComboResult.fromJson(it)) }
            ComboWatchProtocol.PATH_HEARTBEAT -> parse(messageEvent) { link.onHeartbeat(WatchHeartbeat.fromJson(it)) }
            else                              -> super.onMessageReceived(messageEvent)
        }
    }

    private fun parse(messageEvent: MessageEvent, handle: (JSONObject) -> Unit) {
        runCatching { handle(JSONObject(String(messageEvent.data))) }
            .onFailure { aapsLogger.error(LTag.PUMP, "combowatch: cannot read ${messageEvent.path}: ${it.message}") }
    }
}
