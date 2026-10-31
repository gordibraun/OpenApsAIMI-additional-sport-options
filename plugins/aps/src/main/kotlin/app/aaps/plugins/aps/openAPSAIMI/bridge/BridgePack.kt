package app.aaps.plugins.aps.openAPSAIMI.bridge

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.LoggerUtils
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the phone knows at the moment the owner presses «Отправить»: glucose, the loop's last
 * decision, treatments of the last hours, pump and profile, battery and network, and the tail of
 * the AAPS log. Taken then and there, so that the on-call reads the state of the problem and not
 * the state after the network came back. Every section is best-effort; a missing one is named.
 */
@Singleton
class BridgePack @Inject constructor(
    private val context: Context,
    private val loop: Loop,
    private val persistenceLayer: PersistenceLayer,
    private val iobCobCalculator: IobCobCalculator,
    private val profileFunction: ProfileFunction,
    private val activePlugin: ActivePlugin,
    private val config: Config,
    private val loggerUtils: LoggerUtils
) {

    fun collect(): JSONObject {
        val now = System.currentTimeMillis()
        val pack = JSONObject().put("at", now).put("kind", "phone")
        val problems = JSONArray()
        fun section(name: String, block: () -> Any?) {
            runCatching { block() }.onSuccess { pack.put(name, it ?: JSONObject.NULL) }.onFailure { problems.put("$name: ${it.javaClass.simpleName} ${it.message}") }
        }
        section("app") { JSONObject().put("version", config.VERSION_NAME).put("build", config.BUILD_VERSION).put("android", Build.VERSION.RELEASE).put("device", Build.MODEL) }
        section("phone") { phone() }
        section("glucose") { glucose(now) }
        section("loop") { loopState() }
        section("treatments") { treatments(now) }
        section("pump") { pump() }
        section("profile") { profile() }
        section("logTail") { logTail() }
        if (problems.length() > 0) pack.put("problems", problems)
        return pack
    }

    private fun phone(): JSONObject {
        val battery = context.getSystemService(BatteryManager::class.java)
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val network = when {
            caps == null                                                -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else                                                        -> "other"
        }
        return JSONObject().put("batteryPercent", battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)).put("charging", plugged != 0).put("network", network)
    }

    private fun glucose(now: Long): JSONObject {
        val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(now - 3 * 3600_000L, now, true)
        val arr = JSONArray()
        readings.forEach { arr.put(JSONArray().put(it.timestamp).put(it.value.toInt())) }
        val last = readings.lastOrNull()
        return JSONObject().put("last", last?.let { JSONObject().put("at", it.timestamp).put("mgdl", it.value.toInt()).put("ageMin", (now - it.timestamp) / 60_000) } ?: JSONObject.NULL)
            .put("series3h", arr)
    }

    private fun loopState(): JSONObject {
        val run = loop.lastRun
        val request = run?.request
        val iob: Double? = runCatching { iobCobCalculator.calculateIobFromBolus().iob + iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().basaliob }.getOrNull()
        val cob = runCatching { iobCobCalculator.getCobInfo("bridge").displayCob }.getOrNull()
        return JSONObject()
            .put("lastRunAt", run?.lastAPSRun ?: JSONObject.NULL)
            .put("iobU", iob ?: JSONObject.NULL).put("cobG", cob ?: JSONObject.NULL)
            .put("rate", request?.rate ?: JSONObject.NULL).put("durationMin", request?.duration ?: JSONObject.NULL).put("smbU", request?.smb ?: JSONObject.NULL)
            .put("carbsReqG", request?.carbsReq ?: JSONObject.NULL).put("carbsReqWithinMin", request?.carbsReqWithin ?: JSONObject.NULL)
            .put("reason", request?.reason?.take(4000) ?: JSONObject.NULL)
            .put("result", runCatching { run?.constraintsProcessed?.resultAsString() }.getOrNull()?.take(1000) ?: JSONObject.NULL)
    }

    private fun treatments(now: Long): JSONObject {
        val from = now - 6 * 3600_000L
        val boluses = JSONArray()
        persistenceLayer.getBolusesFromTimeToTime(from, now, true).forEach { boluses.put(JSONObject().put("at", it.timestamp).put("u", it.amount).put("type", it.type.name)) }
        val carbs = JSONArray()
        persistenceLayer.getCarbsFromTimeToTimeExpanded(from, now, true).forEach { carbs.put(JSONObject().put("at", it.timestamp).put("g", it.amount).put("durationMin", it.duration / 60_000)) }
        val tbrs = JSONArray()
        persistenceLayer.getTemporaryBasalsActiveBetweenTimeAndTime(now - 3 * 3600_000L, now).forEach {
            tbrs.put(JSONObject().put("at", it.timestamp).put("durationMin", it.duration / 60_000).put("rate", it.rate).put("absolute", it.isAbsolute).put("type", it.type.name))
        }
        return JSONObject().put("boluses6h", boluses).put("carbs6h", carbs).put("tbrs3h", tbrs)
    }

    private fun pump(): JSONObject {
        val pump = activePlugin.activePump
        val status: String? = runCatching {
            "connected=${pump.isConnected()} suspended=${pump.isSuspended()} busy=${pump.isBusy()} lastData=${pump.lastDataTime} baseBasal=${pump.baseBasalRate}"
        }.getOrNull()
        return JSONObject().put("name", pump.model().description).put("status", status ?: JSONObject.NULL)
            .put("reservoirU", runCatching { pump.reservoirLevel }.getOrNull() ?: JSONObject.NULL).put("battery", runCatching { pump.batteryLevel }.getOrNull() ?: JSONObject.NULL)
    }

    private fun profile(): JSONObject {
        val p = profileFunction.getProfile() ?: return JSONObject().put("name", profileFunction.getProfileName()).put("active", false)
        return JSONObject().put("name", profileFunction.getProfileName()).put("isfMgdl", p.getIsfMgdl("bridge")).put("ic", p.getIc()).put("basalUph", p.getBasal())
            .put("targetLow", p.getTargetLowMgdl()).put("targetHigh", p.getTargetHighMgdl())
    }

    private fun logTail(): String {
        val file = File(loggerUtils.logDirectory, "AndroidAPS.log")
        if (!file.exists()) return "no log file at ${file.absolutePath}"
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            val want = minOf(size, LOG_TAIL_BYTES)
            raf.seek(size - want)
            val bytes = ByteArray(want.toInt())
            raf.readFully(bytes)
            val text = String(bytes, Charsets.UTF_8)
            return if (want < size) text.substringAfter('\n') else text
        }
    }

    companion object {

        private const val LOG_TAIL_BYTES = 160_000L
    }
}
