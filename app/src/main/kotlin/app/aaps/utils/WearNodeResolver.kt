package app.aaps.utils

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable

object WearNodeResolver {
    private const val TAG = "WEAR_NODE_RESOLVER"
    private const val PHONE_CAPABILITY = "androidaps_mobile"
    @Volatile private var cached: String? = null

    fun getActiveNodeId(ctx: Context): String? {
        cached?.let { return it }
        return try {
            val info = Tasks.await(
                Wearable.getCapabilityClient(ctx).getCapability(PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            )
            val node = info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull()
            cached = node?.id
            Log.d(TAG, "node=${node?.displayName} id=${cached}")
            cached
        } catch (t: Throwable) { Log.e(TAG, "resolve fail", t); null }
    }

    fun invalidate() { cached = null }
}