package app.aaps.plugins.aps.openAPSAIMI.bridge

import android.content.Context
import android.content.Intent
import dagger.android.DaggerBroadcastReceiver
import javax.inject.Inject

/** The pump plugin dropped a pack from the watch's «Сообщить Claude» into the inbox: pick it up and send. */
class BridgeSosReceiver : DaggerBroadcastReceiver() {

    @Inject lateinit var store: BridgeStore

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION) return
        store.importWatchInbox()
        store.scheduleUpload()
    }

    companion object {

        const val ACTION = "app.aaps.bridge.WATCH_SOS"
    }
}
