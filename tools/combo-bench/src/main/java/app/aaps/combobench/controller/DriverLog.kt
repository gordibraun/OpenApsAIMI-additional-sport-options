package app.aaps.combobench.controller

import android.os.SystemClock
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.base.LoggerBackend
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bounded capture of the driver's own log for one session, limited to the navigation and
 * screen-parsing tags. Transport and pairing tags are left out because their dumps can contain
 * key material, and PumpIO lines are kept only when they are about button presses.
 */
internal class DriverLog : LoggerBackend {
    private val safeTags = setOf("Pump", "RTNavigation", "ParsedDisplayFrameStream", "Parser", "PumpIO", "Screen")
    private val lines = ArrayDeque<JSONObject>()
    private var previous: LoggerBackend? = null
    private var previousThreshold: LogLevel? = null
    private val started = SystemClock.elapsedRealtime()

    @Synchronized override fun log(tag: String, level: LogLevel, throwable: Throwable?, message: String?) {
        val keepLevel = if (tag == "Pump" || tag == "RTNavigation" || tag == "Screen" || tag == "PumpIO") LogLevel.DEBUG else LogLevel.WARN
        if (tag !in safeTags || level.numericLevel > keepLevel.numericLevel) return
        if (lines.size >= 400) lines.removeFirst()
        lines.addLast(
            JSONObject().put("t", SystemClock.elapsedRealtime() - started).put("tag", tag).put("level", level.str)
                .put("throwable", throwable?.javaClass?.simpleName ?: JSONObject.NULL)
                .put(
                    "message",
                    if (tag == "PumpIO" && message?.contains("button", ignoreCase = true) != true) JSONObject.NULL
                    else message?.take(300) ?: JSONObject.NULL
                )
        )
    }

    @Synchronized fun install() {
        previous = Logger.backend; previousThreshold = Logger.threshold
        Logger.backend = this; Logger.threshold = LogLevel.DEBUG
    }

    @Synchronized fun uninstall() {
        previous?.let { Logger.backend = it }; previousThreshold?.let { Logger.threshold = it }
    }

    @Synchronized fun snapshot() = JSONArray(lines.toList())
}
