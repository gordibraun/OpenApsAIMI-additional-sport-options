package app.aaps.combobench

import android.app.Application
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.NullLoggerBackend

class BenchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Library debug messages include keys and PINs. Only our structured journal is used.
        Logger.backend = NullLoggerBackend()
        Logger.threshold = LogLevel.ERROR
    }
}
