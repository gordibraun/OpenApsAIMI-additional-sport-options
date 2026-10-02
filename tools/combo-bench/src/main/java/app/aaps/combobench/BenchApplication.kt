package app.aaps.combobench

import android.app.Application
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.NullLoggerBackend
import info.nightscout.comboctl.base.RTLinkProfile

class BenchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Library debug messages include keys and PINs. Only our structured journal is used.
        Logger.backend = NullLoggerBackend()
        Logger.threshold = LogLevel.ERROR
        // The bench runs on the watch, whose link to the pump needs the slow-link pacing. The
        // phone never sets this, so its direct driver keeps the pacing it always had.
        RTLinkProfile.slowLink = true
    }
}
