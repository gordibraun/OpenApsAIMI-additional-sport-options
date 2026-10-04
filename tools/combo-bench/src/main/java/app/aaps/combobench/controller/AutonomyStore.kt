package app.aaps.combobench.controller

import app.aaps.combobench.JsonFiles
import app.aaps.pump.combowatch.executor.AutonomyPolicy
import app.aaps.pump.combowatch.protocol.PumpEvent
import app.aaps.pump.combowatch.protocol.RegulationSnapshot
import app.aaps.pump.combowatch.regulation.ActivityRecord
import app.aaps.pump.combowatch.regulation.BolusRecord
import app.aaps.pump.combowatch.regulation.CarbsRecord
import app.aaps.pump.combowatch.regulation.DeliveryLog
import app.aaps.pump.combowatch.regulation.GlucoseReading
import app.aaps.pump.combowatch.regulation.TbrSegment
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the watch keeps for the time it may be alone: the owner's choice of mode, the phone's
 * last snapshot, its own sensor readings, what the pump delivered, and a journal of what it
 * decided. All of it on disk, so that a restart of the app in the middle of the night changes
 * nothing.
 */
internal class AutonomyStore(private val files: JsonFiles, private val nowEpochMs: () -> Long = System::currentTimeMillis) {

    // ---- mode ------------------------------------------------------------------------------------

    /** Observing until the owner says otherwise: computing and writing down, never touching the pump. */
    @Synchronized fun mode(): AutonomyPolicy.Mode = runCatching {
        if (files.exists(MODE_FILE)) AutonomyPolicy.Mode.valueOf(files.read(MODE_FILE).getString("mode")) else DEFAULT_MODE
    }.getOrDefault(DEFAULT_MODE)

    @Synchronized fun setMode(mode: AutonomyPolicy.Mode) =
        files.write(MODE_FILE, JSONObject().put("mode", mode.name).put("setAt", nowEpochMs()))

    // ---- the phone's snapshot ----------------------------------------------------------------------

    @Synchronized fun snapshot(): RegulationSnapshot? = runCatching {
        if (files.exists(SNAPSHOT_FILE)) RegulationSnapshot.fromJson(files.read(SNAPSHOT_FILE)) else null
    }.getOrNull()

    @Synchronized fun saveSnapshot(snapshot: RegulationSnapshot) = files.write(SNAPSHOT_FILE, snapshot.toJson())

    // ---- what the face draws -----------------------------------------------------------------------

    /** The watch's latest forecast series and insulin on board, written at each decision; see [AutonomyRunner]. */
    @Synchronized fun faceForecast(): JSONObject? = runCatching { if (files.exists(FORECAST_FILE)) files.read(FORECAST_FILE) else null }.getOrNull()

    @Synchronized fun saveFaceForecast(value: JSONObject) = files.write(FORECAST_FILE, value)

    // ---- sensor readings ---------------------------------------------------------------------------

    @Synchronized fun readings(): List<GlucoseReading> = runCatching {
        if (!files.exists(GLUCOSE_FILE)) emptyList()
        else files.read(GLUCOSE_FILE).getJSONArray("readings").let { array ->
            List(array.length()) { array.getJSONObject(it).let { item -> GlucoseReading(item.getLong("at"), item.getDouble("mgdl")) } }
        }
    }.getOrDefault(emptyList())

    /** Keep a reading. Returns false if it was already there. */
    @Synchronized fun addReading(reading: GlucoseReading): Boolean {
        val known = readings()
        // The same sample can arrive twice with its age counted a second apart.
        if (known.any { kotlin.math.abs(it.atEpochMs - reading.atEpochMs) < SAME_SAMPLE_MS }) return false
        val kept = (known + reading).filter { it.atEpochMs >= nowEpochMs() - KEEP_READINGS_MS }.sortedBy { it.atEpochMs }
        files.write(
            GLUCOSE_FILE,
            JSONObject().put("readings", JSONArray().apply { kept.forEach { put(JSONObject().put("at", it.atEpochMs).put("mgdl", it.mgdl)) } })
        )
        return true
    }

    // ---- what the pump delivered -------------------------------------------------------------------

    @Synchronized fun delivery(): DeliveryLog = runCatching {
        if (!files.exists(DELIVERY_FILE)) DeliveryLog(carbs = carbs(), activities = activities())
        else files.read(DELIVERY_FILE).let { saved ->
            val tbrs = saved.getJSONArray("tbrs").let { array ->
                List(array.length()) {
                    array.getJSONObject(it).let { item ->
                        TbrSegment(
                            startEpochMs = item.getLong("start"), percent = item.getInt("percent"), durationMinutes = item.getInt("minutes"),
                            endedEpochMs = if (item.isNull("ended")) null else item.getLong("ended"), byWatch = item.optBoolean("byWatch")
                        )
                    }
                }
            }
            val boluses = saved.getJSONArray("boluses").let { array ->
                List(array.length()) { array.getJSONObject(it).let { item -> BolusRecord(item.getLong("at"), item.getDouble("units")) } }
            }
            DeliveryLog(tbrs, boluses, carbs(), activities())
        }
    }.getOrDefault(DeliveryLog(carbs = carbs(), activities = activities()))

    // ---- walks and sport sessions entered on the watch -------------------------------------------------

    @Synchronized fun activities(): List<ActivityRecord> = runCatching {
        if (!files.exists(ACTIVITY_FILE)) emptyList()
        else files.read(ACTIVITY_FILE).getJSONArray("activities").let { array ->
            List(array.length()) {
                array.getJSONObject(it).let { item -> ActivityRecord(item.getLong("start"), item.getInt("minutes"), item.getString("mode"), item.getInt("tail")) }
            }
        }
    }.getOrDefault(emptyList())

    /** Keep an activity; one entered again for the same start replaces the earlier entry. Kept half a day past its tail. */
    @Synchronized fun addActivity(activity: ActivityRecord) {
        val kept = (activities().filter { kotlin.math.abs(it.startEpochMs - activity.startEpochMs) >= 60_000L } + activity)
            .filter { it.tailEndEpochMs >= nowEpochMs() - KEEP_ACTIVITIES_MS }
            .sortedBy { it.startEpochMs }
        files.write(
            ACTIVITY_FILE,
            JSONObject().put("activities", JSONArray().apply {
                kept.forEach { put(JSONObject().put("start", it.startEpochMs).put("minutes", it.durationMinutes).put("mode", it.mode).put("tail", it.tailMinutes)) }
            })
        )
    }

    // ---- carbohydrates entered on the watch ---------------------------------------------------------

    @Synchronized fun carbs(): List<CarbsRecord> = runCatching {
        if (!files.exists(CARBS_FILE)) emptyList()
        else files.read(CARBS_FILE).getJSONArray("carbs").let { array ->
            List(array.length()) { array.getJSONObject(it).let { item -> CarbsRecord(item.getLong("at"), item.getInt("grams")) } }
        }
    }.getOrDefault(emptyList())

    @Synchronized fun addCarbs(record: CarbsRecord) {
        val kept = (carbs() + record).filter { it.atEpochMs >= nowEpochMs() - KEEP_DELIVERY_MS }.sortedBy { it.atEpochMs }
        files.write(CARBS_FILE, JSONObject().put("carbs", JSONArray().apply { kept.forEach { put(JSONObject().put("at", it.atEpochMs).put("grams", it.grams)) } }))
    }

    private fun saveDelivery(tbrs: List<TbrSegment>, boluses: List<BolusRecord>) {
        val since = nowEpochMs() - KEEP_DELIVERY_MS
        files.write(
            DELIVERY_FILE,
            JSONObject()
                .put("tbrs", JSONArray().apply {
                    tbrs.filter { it.scheduledEndEpochMs >= since }.forEach {
                        put(
                            JSONObject().put("start", it.startEpochMs).put("percent", it.percent).put("minutes", it.durationMinutes)
                                .put("ended", it.endedEpochMs ?: JSONObject.NULL).put("byWatch", it.byWatch)
                        )
                    }
                })
                .put("boluses", JSONArray().apply {
                    boluses.filter { it.atEpochMs >= since }.forEach { put(JSONObject().put("at", it.atEpochMs).put("units", it.units)) }
                })
        )
    }

    /**
     * Follow what the driver saw on the pump. [byWatch] says whether the command under way is one
     * the watch gave itself; a temporary basal started under it is the watch's own.
     */
    @Synchronized fun onPumpEvent(event: PumpEvent, byWatch: Boolean) {
        val log = delivery()
        when (event.type) {
            PumpEvent.Type.TBR_STARTED   -> {
                val percent = event.tbrPercentage ?: return
                val minutes = event.tbrDurationMinutes ?: return
                // The same start reported twice changes nothing.
                if (log.tbrs.any { it.startEpochMs == event.timestampEpochMs && it.percent == percent }) return
                saveDelivery(log.tbrs + TbrSegment(event.timestampEpochMs, percent, minutes, byWatch = byWatch), log.boluses)
            }

            // The driver reports the end of whatever it had recorded as running. That is the
            // latest temporary basal not yet closed; a later report of the same end finds none.
            PumpEvent.Type.TBR_ENDED     -> {
                val open = log.tbrs.lastOrNull { it.endedEpochMs == null && it.startEpochMs < event.timestampEpochMs } ?: return
                saveDelivery(log.tbrs.map { if (it === open) it.copy(endedEpochMs = event.timestampEpochMs) else it }, log.boluses)
            }

            PumpEvent.Type.BOLUS_INFUSED -> {
                val units = (event.bolusTenthsIU ?: return) / 10.0
                if (log.boluses.any { it.atEpochMs == event.timestampEpochMs && it.units == units }) return
                saveDelivery(log.tbrs, log.boluses + BolusRecord(event.timestampEpochMs, units))
            }

            else                         -> Unit
        }
    }

    /**
     * Make the record agree with what the pump itself shows, each time the pump is read.
     *
     * The driver's events cover what this watch was there to see. A temporary basal that was
     * already running when this record began, or one that ended on the pump for a reason no event
     * told of, is put right here from the pump's own screen.
     */
    @Synchronized fun syncWithPump(readAtEpochMs: Long, tbrRunning: Boolean, tbrPercentage: Int?, tbrRemainingMinutes: Int?) {
        val log = delivery()
        val recorded = log.tbrAt(readAtEpochMs)
        if (tbrRunning && tbrPercentage != null && tbrRemainingMinutes != null) {
            if (recorded?.percent == tbrPercentage) return
            val closed = log.tbrs.map { if (it.runsAt(readAtEpochMs)) it.copy(endedEpochMs = readAtEpochMs) else it }
            saveDelivery(closed + TbrSegment(readAtEpochMs, tbrPercentage, tbrRemainingMinutes.coerceAtLeast(1)), log.boluses)
        } else if (recorded != null) {
            saveDelivery(log.tbrs.map { if (it.runsAt(readAtEpochMs)) it.copy(endedEpochMs = readAtEpochMs) else it }, log.boluses)
        }
    }

    /** The pump was unpaired: its delivery, and the snapshot made for it, say nothing about the next one. */
    @Synchronized fun forgetPump() {
        files.write(DELIVERY_FILE, JSONObject().put("tbrs", JSONArray()).put("boluses", JSONArray()))
        files.write(SNAPSHOT_FILE, JSONObject())
    }

    // ---- journal -----------------------------------------------------------------------------------

    @Synchronized fun journal(): List<JSONObject> = runCatching {
        if (!files.exists(JOURNAL_FILE)) emptyList()
        else files.read(JOURNAL_FILE).getJSONArray("entries").let { array -> List(array.length()) { array.getJSONObject(it) } }
    }.getOrDefault(emptyList())

    @Synchronized fun addToJournal(entry: JSONObject) {
        val kept = (journal() + entry.put("at", entry.optLong("at", nowEpochMs()))).takeLast(MAX_JOURNAL_ENTRIES)
        files.write(JOURNAL_FILE, JSONObject().put("entries", JSONArray().apply { kept.forEach { put(it) } }))
    }

    companion object {

        val DEFAULT_MODE = AutonomyPolicy.Mode.OBSERVE

        const val MODE_FILE = "autonomy-mode.json"
        const val SNAPSHOT_FILE = "autonomy-snapshot.json"
        const val GLUCOSE_FILE = "autonomy-glucose.json"
        const val DELIVERY_FILE = "autonomy-delivery.json"
        const val CARBS_FILE = "autonomy-carbs.json"
        const val ACTIVITY_FILE = "autonomy-activity.json"
        const val KEEP_ACTIVITIES_MS = 12 * 60 * 60_000L
        const val JOURNAL_FILE = "autonomy-journal.json"
        const val FORECAST_FILE = "autonomy-forecast.json"

        const val SAME_SAMPLE_MS = 60_000L

        /** Trends need forty minutes; the rest is kept for looking back. */
        const val KEEP_READINGS_MS = 12 * 60 * 60_000L

        /** A forecast looks back as far as its snapshot, at most four hours; an hour of zero is counted over more. */
        const val KEEP_DELIVERY_MS = 12 * 60 * 60_000L
        const val MAX_JOURNAL_ENTRIES = 300
    }
}
