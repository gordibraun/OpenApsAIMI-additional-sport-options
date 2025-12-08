package app.aaps.receivers

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import app.aaps.plugins.main.general.persistentNotification.DummyServiceHelper
import dagger.android.DaggerBroadcastReceiver
import javax.inject.Inject

class AutoStartReceiver : DaggerBroadcastReceiver() {

    @Inject
    lateinit var dummyServiceHelper: DummyServiceHelper

    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        processIntent(context, intent)
    }

    @VisibleForTesting
    fun processIntent(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED -> {
                dummyServiceHelper.startService(context)
            }
        }
    }
}