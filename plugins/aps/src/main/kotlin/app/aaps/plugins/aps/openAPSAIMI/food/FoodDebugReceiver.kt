package app.aaps.plugins.aps.openAPSAIMI.food

import android.content.Context
import android.content.Intent
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import dagger.android.DaggerBroadcastReceiver
import java.io.File
import javax.inject.Inject

/**
 * Checks from adb without a camera; the manifest lets only the shell (DUMP permission) send it.
 * Debug builds are the only ones where the shell can also write the file:
 *
 *   adb shell "cat /sdcard/Download/plate.jpg | run-as info.nightscout.androidaps sh -c 'cat > files/plate.jpg'"
 *   adb shell am broadcast -a app.aaps.food.ADD_PHOTO --es path /data/user/0/info.nightscout.androidaps/files/plate.jpg --es caption "обед"
 *
 * The same broadcast with `--es url …` or `--es token …` sets the server address or token, so a
 * secret can travel from a shell variable to the phone without being typed into any chat.
 */
class FoodDebugReceiver : DaggerBroadcastReceiver() {

    @Inject lateinit var store: FoodPhotoStore
    @Inject lateinit var preferences: Preferences

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        intent.getStringExtra("url")?.let { preferences.put(StringKey.FoodApiUrl, it.trim()) }
        intent.getStringExtra("token")?.let { preferences.put(StringKey.FoodApiToken, it.trim()) }
        if (intent.hasExtra("url") || intent.hasExtra("token")) store.enqueueSync()
        val source = intent.getStringExtra("path")?.let { File(it) }?.takeIf { it.exists() } ?: return
        val (id, target) = store.newPhotoFile()
        source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        store.add(id, target, intent.getStringExtra("caption").orEmpty())
    }
}
