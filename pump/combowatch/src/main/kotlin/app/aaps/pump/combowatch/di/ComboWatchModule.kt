package app.aaps.pump.combowatch.di

import app.aaps.pump.combowatch.ComboWatchDebugReceiver
import app.aaps.pump.combowatch.ComboWatchListenerService
import dagger.Module
import dagger.android.ContributesAndroidInjector

@Module
@Suppress("unused")
abstract class ComboWatchModule {

    @ContributesAndroidInjector abstract fun contributesComboWatchListenerService(): ComboWatchListenerService
    @ContributesAndroidInjector abstract fun contributesComboWatchDebugReceiver(): ComboWatchDebugReceiver
}
