package app.aaps.wear.combo

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/**
 * Carries messages between AAPS on the phone and the pump controller app on this watch.
 *
 * The Wear data layer only connects apps of the same package, so the phone's AAPS can reach this
 * app and nothing else on the watch - while the pump pairing, and everything that decides what
 * may be done with it, lives in the controller app. This relay is the bridge between the two and
 * deliberately nothing more: it does not read the messages, keeps no state, and has no pump code,
 * Bluetooth permission or keys. If the controller is missing, messages simply go unanswered.
 *
 * Both directions are guarded by a signature-level permission, so only an app signed with the
 * same key as this one can be on the other end.
 */
internal object ComboRelay {

    const val PATH_PREFIX = "/combowatch/"

    const val ACTION_FROM_PHONE = "app.aaps.combo.action.FROM_PHONE"
    const val PERMISSION_RELAY = "app.aaps.combo.permission.RELAY"
    const val EXTRA_PATH = "path"
    const val EXTRA_PAYLOAD = "payload"

    const val CONTROLLER_PACKAGE = "app.aaps.combobench.manual"
    const val CONTROLLER_RECEIVER = "app.aaps.combobench.controller.ControllerInboundReceiver"

    /** Carbohydrates entered on this watch, for the controller to keep; see [ComboWatchMode]. */
    const val ACTION_CARBS = "app.aaps.combo.action.CARBS"
    const val CONTROLLER_CARBS_RECEIVER = "app.aaps.combobench.controller.ControllerCarbsReceiver"

    /** A walk or a sport session entered on this watch, for the controller to keep and the phone to record later. */
    const val ACTION_ACTIVITY = "app.aaps.combo.action.ACTIVITY"
    const val CONTROLLER_ACTIVITY_RECEIVER = "app.aaps.combobench.controller.ControllerActivityReceiver"
    const val PATH_LEASE = "/combowatch/lease"

    const val TAG = "ComboRelay"
}

/** Phone -> controller. Its own paths, next to the companion's existing listener, not in it. */
class ComboRelayListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        if (!path.startsWith(ComboRelay.PATH_PREFIX)) return
        if (path == ComboRelay.PATH_LEASE) ComboWatchMode.remember(this, String(messageEvent.data))
        runCatching {
            sendBroadcast(
                Intent(ComboRelay.ACTION_FROM_PHONE)
                    .setComponent(ComponentName(ComboRelay.CONTROLLER_PACKAGE, ComboRelay.CONTROLLER_RECEIVER))
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(ComboRelay.EXTRA_PATH, path)
                    .putExtra(ComboRelay.EXTRA_PAYLOAD, String(messageEvent.data)),
                ComboRelay.PERMISSION_RELAY
            )
        }.onFailure { Log.w(ComboRelay.TAG, "could not hand $path to the controller: ${it.javaClass.simpleName}") }
    }
}

/** Controller -> phone. */
class ComboRelayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra(ComboRelay.EXTRA_PATH) ?: return
        val payload = intent.getStringExtra(ComboRelay.EXTRA_PAYLOAD) ?: return
        if (!path.startsWith(ComboRelay.PATH_PREFIX)) return
        val pending = goAsync()
        val appContext = context.applicationContext
        Wearable.getNodeClient(appContext).connectedNodes
            .addOnSuccessListener { nodes ->
                val bytes = payload.toByteArray()
                nodes.forEach { node -> Wearable.getMessageClient(appContext).sendMessage(node.id, path, bytes) }
                pending.finish()
            }
            .addOnFailureListener {
                Log.w(ComboRelay.TAG, "no phone to send $path to: ${it.javaClass.simpleName}")
                pending.finish()
            }
    }
}
