package app.aaps.combobench

import android.app.Application
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.NullLoggerBackend
import info.nightscout.comboctl.base.DriverProfile

class BenchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Library debug messages include keys and PINs. Only our structured journal is used.
        Logger.backend = NullLoggerBackend()
        Logger.threshold = LogLevel.ERROR
        // The bench runs on the watch and uses the more defensive pacing; see DriverProfile. The
        // phone never sets this, so its direct driver keeps the pacing it always had.
        DriverProfile.confirmedStepPacing = true
        // Recognising a display frame costs the watch most of a CPU core; do it on demand so the
        // packet receive loop keeps up with the pump.
        DriverProfile.lazyDisplayFrameParsing = true
    }
}
