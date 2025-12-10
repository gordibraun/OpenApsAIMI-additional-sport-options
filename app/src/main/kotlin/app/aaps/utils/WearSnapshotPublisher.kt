// alexeydedeshko

package app.aaps.utils

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import app.aaps.core.interfaces.rx.weardata.EventData

class WearSnapshotPublisher(private val ctx: Context) {

    companion object {
        private const val TAG = "WEAR_SNAPSHOT_PUB"
        private const val PATH_SNAPSHOT = "/aaps/snapshot"
        private const val PATH_SNAPSHOT_MSG = "/aaps/snapshot_msg"
    }

    fun publish(single: EventData.SingleBg?, status: EventData.Status?) {
        // 1) Надёжный канал: DataItem (лежит на часах заранее)
        val req = PutDataMapRequest.create(PATH_SNAPSHOT).apply {
            if (single != null) dataMap.putString("singleJson", single.serialize())
            if (status != null) dataMap.putString("statusJson", status.serialize())

            // 🔥 Ключевой фикс: меняющийся таймстамп для форс-обновления
            dataMap.putLong("heartbeat", System.currentTimeMillis())
            dataMap.putLong("nonce", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        Wearable.getDataClient(ctx).putDataItem(req)
            .addOnSuccessListener { Log.d(TAG, "putDataItem ok: $PATH_SNAPSHOT") }
            .addOnFailureListener { Log.e(TAG, "putDataItem fail", it) }

        // 2) Быстрый дубль: Message (если есть nodeId)
        val nodeId = WearNodeResolver.getActiveNodeId(ctx)
        if (nodeId != null && single != null) {
            val bytes = single.serialize().toByteArray()
            Wearable.getMessageClient(ctx).sendMessage(nodeId, PATH_SNAPSHOT_MSG, bytes)
                .addOnSuccessListener { Log.d(TAG, "sendMessage ok to $nodeId") }
                .addOnFailureListener { Log.e(TAG, "sendMessage fail", it) }
        }
    }}