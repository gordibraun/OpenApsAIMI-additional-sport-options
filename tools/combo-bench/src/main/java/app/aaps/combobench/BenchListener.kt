package app.aaps.combobench

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class BenchListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == BenchRuntime.PATH) BenchRuntime.get(this).receive(event.sourceNodeId, event.data)
    }
}
