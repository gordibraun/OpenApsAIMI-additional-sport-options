package app.aaps.combobench.controller

import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.content.Intent
import android.app.PendingIntent
import android.os.BatteryManager
import app.aaps.combobench.BenchFiles
import app.aaps.combobench.BuildConfig
import app.aaps.combobench.ManualPumpRuntime
import app.aaps.combobench.ManualPumpTarget
import app.aaps.combobench.TriageActivity
import app.aaps.pump.combowatch.executor.ComboExecutor
import app.aaps.pump.combowatch.executor.AutonomyPolicy
import app.aaps.pump.combowatch.executor.CommandGate
import app.aaps.pump.combowatch.executor.CommandJournal
import app.aaps.pump.combowatch.executor.EventOutbox
import app.aaps.pump.combowatch.executor.SimpleCommandJournal
import app.aaps.pump.combowatch.protocol.ComboCommand
import app.aaps.pump.combowatch.protocol.ComboResult
import app.aaps.pump.combowatch.protocol.ComboWatchProtocol
import app.aaps.pump.combowatch.protocol.CommandKind
import app.aaps.pump.combowatch.protocol.ControlLease
import app.aaps.pump.combowatch.protocol.Outcome
import app.aaps.pump.combowatch.protocol.PumpEvent
import app.aaps.pump.combowatch.protocol.PumpSnapshot
import app.aaps.pump.combowatch.protocol.RegulationSnapshot
import app.aaps.pump.combowatch.protocol.WatchHeartbeat
import app.aaps.pump.combowatch.regulation.ActivityEffect
import app.aaps.pump.combowatch.regulation.ActivityRecord
import app.aaps.pump.combowatch.regulation.CarbsNeeded
import app.aaps.pump.combowatch.regulation.CarbsRecord
import app.aaps.pump.combowatch.regulation.WatchRegulator
import app.aaps.pump.combowatch.regulation.GlucoseReading
import app.aaps.pump.combowatch.regulation.GlucoseTrend
import app.aaps.pump.combowatch.regulation.InsulinEffectiveness
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The watch-side controller: holds the lease, the journal and the event outbox, and runs commands
 * from the phone against the pump one at a time.
 *
 * It talks to the phone only through the relay in the AAPS watch app, because the Wear data layer
 * connects apps of the same package and the phone's counterpart is AAPS. Everything that matters
 * for safety - who may control the pump, what already ran, what the pump did - lives here, next to
 * the pump pairing, and survives restarts on disk.
 */
internal class ControllerHost private constructor(context: Context) {

    private val context = context.applicationContext
    private val files = BenchFiles(context)

    private val journal: CommandJournal = SimpleCommandJournal(initial = loadJournal(), persist = ::saveJournal)
    private val outbox = loadOutbox()

    @Volatile private var lease: ControlLease? = loadLease()

    /** When the phone last said anything, by this watch's clock; survives a restart of the app. */
    @Volatile private var phoneLastHeardEpochMs: Long = runCatching {
        if (files.exists(PHONE_HEARD_FILE)) files.read(PHONE_HEARD_FILE).getLong("at") else 0L
    }.getOrDefault(0L)

    private fun phoneHeard() {
        val now = System.currentTimeMillis()
        // Written at most once a minute: it only has to be right to within the minutes that matter.
        if (now - phoneLastHeardEpochMs >= 60_000L) runCatching { files.write(PHONE_HEARD_FILE, JSONObject().put("at", now)) }
        phoneLastHeardEpochMs = now
    }
    @Volatile private var lastSnapshot: PumpSnapshot? = null
    @Volatile private var pumpReachable = false
    /** When a command last failed to reach the pump; zero when none did since the last read. */
    @Volatile private var pumpNotReachedAtEpochMs = 0L

    /** What the watch keeps for the time it may be on its own. */
    val autonomy = AutonomyStore(files)

    /** Sensing a walk or a sport session from heart rate and the fitness app's workouts; see [ActivityDetector]. */
    private val detector: ActivityDetector = runCatching {
        if (files.exists(SENSING_FILE)) ActivityDetector.fromJson(files.read(SENSING_FILE)) else ActivityDetector()
    }.getOrDefault(ActivityDetector())
    private val sensingLock = Any()

    init {
        // Health Services keeps delivering to the service across restarts once registered; asking again is harmless.
        Thread { runCatching { startSensing() }.onFailure { Log.w(ActivitySensing.TAG, "sensing not started: ${it.javaClass.simpleName}") } }.start()
    }

    private val session = DriverPumpSession(
        context, files,
        onEvent = { event ->
            // Stamped here, where the pump is known for certain, rather than left for the phone to infer.
            outbox.append(event.copy(pumpSerial = heldPump()))
            // The watch's own record of what the pump delivered, whoever asked for it.
            runCatching { autonomy.onPumpEvent(event, byWatch = runner.ownCommandInFlight) }
            // A bolus or a basal change shows on the face at once, not at its next scheduled redraw.
            if (event.type == PumpEvent.Type.BOLUS_INFUSED || event.type == PumpEvent.Type.TBR_STARTED || event.type == PumpEvent.Type.TBR_ENDED) refreshFace()
        },
        onPumpRead = { snapshot, boluses ->
            lastSnapshot = snapshot
            pumpReachable = true
            pumpNotReachedAtEpochMs = 0L
            runCatching { autonomy.syncWithPump(snapshot.readAtEpochMs, snapshot.tbrRunning, snapshot.tbrPercentage, snapshot.tbrRemainingMinutes) }
            executor.reconcile(snapshot, boluses)
        }
    )

    private val executor: ComboExecutor = ComboExecutor(
        CommandGate({ System.currentTimeMillis() }, { maxBolusTenthsIU() }, { heldPump() }),
        journal, session
    ) { System.currentTimeMillis() }

    /** Who leads the basal and when that changed; see [LeadershipLog]. */
    private val leadership = LeadershipLog(
        load = {
            if (!files.exists(LEADERSHIP_FILE)) emptyList()
            else files.read(LEADERSHIP_FILE).getJSONArray("entries").let { array -> List(array.length()) { array.getJSONObject(it) } }
        },
        save = { entries -> files.write(LEADERSHIP_FILE, JSONObject().put("entries", JSONArray(entries))) }
    )

    private val runner: AutonomyRunner = AutonomyRunner(
        store = autonomy,
        executor = executor,
        phoneLease = { lease },
        phoneLastHeardEpochMs = { phoneLastHeardEpochMs },
        heldPump = { heldPump() },
        pumpBasalUph = { pumpBasalUph() },
        note = { text, at -> outbox.append(PumpEvent(0, PumpEvent.Type.WATCH_NOTE, at, pumpSerial = heldPump(), note = text)) },
        askForCarbs = ::notifyCarbs,
        // The bench's own manual sessions use the same pump and pairing; never overlap with them.
        pumpInOtherUse = { ManualPumpRuntime.get(this.context).usingBluetoothNow() },
        beforePumpSession = ::leaveTheSensorItsWindow,
        lastPumpReadEpochMs = { savedSnapshot()?.readAtEpochMs },
        onOwnResult = ::notePumpAnswer,
        ownerBolusLimitTenths = { ownerBolusLimitTenths() }
    )

    /** What a command's answer says about the pump being within reach; the face shows it. */
    private fun notePumpAnswer(result: ComboResult) {
        if (result.outcome == Outcome.FAILED && result.reason?.startsWith("pump not reached") == true) {
            pumpReachable = false
            pumpNotReachedAtEpochMs = System.currentTimeMillis()
        }
        result.snapshot?.let { lastSnapshot = it }
        refreshFace()
    }

    /**
     * Hold the pump back while the glucose sensor is due to speak; see [SensorWindows]. Called
     * with the pump turn held, so nothing else reaches the pump meanwhile. A reading arriving
     * during the wait means the window is over, and the wait ends with it.
     */
    private fun leaveTheSensorItsWindow() {
        val started = System.currentTimeMillis()
        var announced = false
        while (true) {
            val now = System.currentTimeMillis()
            val until = SensorWindows.waitUntil(now, autonomy.readings()) ?: return
            if (now - started >= MAX_SENSOR_WAIT_MS) return
            if (!announced) {
                announced = true
                android.util.Log.i("ComboController", "pump waits ${(until - now) / 1000}s: the sensor's window comes first")
            }
            android.os.SystemClock.sleep(minOf(until - now, SENSOR_WAIT_SLICE_MS).coerceAtLeast(250L))
        }
    }

    @Volatile private var lastFaceCarbsLogged: Int? = -1
    private var lastPhoneCarbsAsk: Pair<Int, Long>? = null

    /**
     * The phone's own «eat N g» reaches the wrist as a notice too, but only when it is worth a
     * buzz (at least [FaceGraph.MIN_CARBS_SHOWN_G] g, wanted within an hour) and once per figure;
     * a few grams over two hours stay a line on the face.
     */
    private fun maybeAskCarbsFromPhone(snapshot: RegulationSnapshot) {
        val grams = snapshot.carbsRequiredG?.takeIf { it >= FaceGraph.MIN_CARBS_SHOWN_G } ?: return
        val within = snapshot.carbsRequiredWithinMin ?: return
        if (within > PHONE_CARBS_ASK_URGENT_MIN) return
        val now = System.currentTimeMillis()
        lastPhoneCarbsAsk?.let { (lastGrams, at) -> if (lastGrams == grams && now - at < PHONE_CARBS_ASK_REPEAT_MS) return }
        lastPhoneCarbsAsk = grams to now
        notifyCarbs(grams, "Телефон просит $grams г углеводов в течение $within мин; на циферблате то же число.", subtitle = "Так же говорит телефон")
    }

    /** A notification that vibrates: the one thing the watch asks of its wearer by itself. */
    private fun notifyCarbs(grams: Int, why: String, subtitle: String = "Одной остановки базала не хватит") {
        runCatching {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(
                android.app.NotificationChannel(CARBS_CHANNEL, "Низкий прогноз", android.app.NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
                }
            )
            manager.notify(
                CARBS_NOTIFICATION_ID,
                android.app.Notification.Builder(context, CARBS_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Съешьте около $grams г углеводов")
                    .setContentText(subtitle)
                    .setStyle(android.app.Notification.BigTextStyle().bigText("$subtitle. $why"))
                    .setCategory(android.app.Notification.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    val isBusy: Boolean get() = executor.isBusy

    /** Held by whatever is using the pump or changing which pump is held; one at a time. */
    private val pumpTurn = Any()

    /** Run [block] while no command is using the pump, and keep commands out until it returns. */
    fun <T> whilePumpIdle(block: () -> T): T = synchronized(pumpTurn) { block() }

    /** The pump this watch is fully paired with, as the driver names it. */
    private fun heldPump(): String? = ManualPumpRuntime.get(context).pairedPump()?.pump

    /**
     * What the owner should know before the pump is unpaired, in the words shown on the watch.
     * None of it prevents the unpairing: a pump that is broken or gone has to be replaceable.
     */
    fun unpairWarnings(testPump: Boolean): List<String> {
        val warnings = mutableListOf<String>()
        val now = System.currentTimeMillis()
        journal.unresolved()?.let { entry ->
            val what = when (entry.kind) {
                CommandKind.DELIVER_BOLUS -> "болюс ${(entry.bolusTenthsIU ?: 0) / 10.0} ЕД"
                CommandKind.SET_TBR       -> "TBR ${entry.tbrPercentage ?: "?"} %"
                CommandKind.CANCEL_TBR    -> "отмена TBR"
                else                      -> "команда"
            }
            warnings += "Исход последней команды ($what) не выяснен. После отвязки узнать его можно будет только по самой помпе"
        }
        val pending = outbox.pending()
        if (pending.isNotEmpty() && testPump)
            warnings += "Записи тестовой помпы (${pending.size}) телефону не передаются и будут удалены"
        else if (pending.isNotEmpty()) {
            val boluses = pending.count { it.type == PumpEvent.Type.BOLUS_INFUSED }
            warnings += "Телефон ещё не получил записей с этой помпы: ${pending.size}" +
                (if (boluses > 0) ", из них болюсов: $boluses" else "") + ". Они будут переданы, когда телефон окажется на связи"
        }
        if (lease?.liveAt(now) == true) warnings += "Телефон сейчас управляет помпой через часы; после отвязки его команды выполняться не будут"
        savedSnapshot()?.let { snapshot ->
            val remaining = (snapshot.tbrRemainingMinutes ?: 0) - ((now - snapshot.readAtEpochMs) / 60_000L).toInt()
            if (snapshot.tbrRunning && remaining > 0)
                warnings += "На помпе, по последним данным, идёт TBR ${snapshot.tbrPercentage} % ещё около $remaining мин; он продолжится"
        }
        return warnings
    }

    /** The last reading of the held pump, from memory or, after a restart, from the last heartbeat. */
    private fun savedSnapshot(): PumpSnapshot? = lastSnapshot ?: runCatching {
        if (!files.exists(HEARTBEAT_FILE)) null
        else WatchHeartbeat.fromJson(files.read(HEARTBEAT_FILE)).snapshot?.takeIf { it.pumpSerial == heldPump() }
    }.getOrNull()

    /**
     * Called when the pump is unpaired, after the pairing itself is gone.
     *
     * What is known about that pump is dropped, and commands whose outcome was never established
     * are given up: the next pump's screen and history are no evidence about them, so they must
     * not be settled from it. Everything else is kept on purpose. The journal still answers a
     * late copy of an old command instead of running it; the lease is the phone's statement and
     * names the pump it was granted for, so it authorises nothing on another pump; and the
     * events not yet acknowledged still go to the phone, each naming the pump it was seen on -
     * they are how the phone accounts for insulin that pump delivered.
     *
     * @return the commands whose outcome was given up, for telling the owner.
     */
    fun forgetPump(wasTestPump: Boolean): List<CommandJournal.Entry> {
        val givenUp = journal.abandonUnresolved("pump unpaired before the outcome was established")
        // The bench's test pump is not connected to anybody, so what it "delivered" must never be
        // counted as insulin. Its events are the only ones ever dropped unsent - along with those
        // from before events named their pump, when the test pump was the only pump there was.
        if (wasTestPump) outbox.discard { it.pumpSerial == null || it.pumpSerial == "PUMP_${ManualPumpTarget.TEST_SERIAL}" }
        autonomy.forgetPump()
        lastSnapshot = null
        pumpReachable = false
        for (name in listOf(HEARTBEAT_FILE, RESULT_FILE)) {
            java.io.File(context.filesDir, name).delete()
            java.io.File(context.filesDir, "$name.bak").delete()
        }
        // Tells the phone at once that this watch holds no pump any more.
        sendEvents()
        sendHeartbeat()
        return givenUp
    }

    // ---- inbound, called on the controller's worker thread ---------------------------------------

    fun onLease(payload: String) {
        phoneHeard()
        val incoming = ControlLease.fromJson(JSONObject(payload))
        val current = lease
        // An older generation must not replace a newer one: a renewal that was delayed on the way
        // would otherwise hand control back to a phone session that has since been replaced.
        if ((current != null) && (incoming.generation < current.generation)) return
        lease = incoming
        files.write(LEASE_FILE, incoming.toJson())
        // What the phone leaves for the case that this was its last word for a while.
        JSONObject(payload).optJSONObject(RegulationSnapshot.KEY_IN_LEASE)?.let { saved ->
            runCatching {
                val snapshot = RegulationSnapshot.fromJson(saved)
                autonomy.saveSnapshot(snapshot)
                maybeAskCarbsFromPhone(snapshot)
                // The phone's forecast is what the system expected: kept to check insulin's effect against later.
                snapshot.phoneForecast?.let { autonomy.addForecastMade(snapshot.madeAtEpochMs, it, byWatch = false) }
                keepPhoneActivities(snapshot)
            }
        }
        observeLeadership()
        // The phone is here: whatever the watch noted and did meanwhile goes to it now.
        sendEvents()
        sendHeartbeat()
    }

    /** Note who leads now; a line is written to the log when that changed since the last look. */
    private fun observeLeadership(): LeadershipLog.Entry? {
        val current = lease
        return runCatching {
            leadership.observe(
                standing = runner.standing(), mode = autonomy.mode(), nowEpochMs = System.currentTimeMillis(),
                phoneHeardEpochMs = phoneLastHeardEpochMs, leaseExpiresEpochMs = current?.expiresAtEpochMs,
                leaseRevoked = current?.controllerIsWatch == false
            )
        }.getOrNull()?.also { entry ->
            // The watch has just found itself in charge: from here on it ticks by itself.
            if (entry.leader == LeadershipLog.Leader.WATCH || entry.leader == LeadershipLog.Leader.WATCH_OBSERVING) scheduleTickIfAlone()
        }
    }

    /** The lines of the leadership log, oldest first; for the owner's screen. */
    fun leadershipEntries(): List<LeadershipLog.Entry> = leadership.entries()

    // ---- the watch on its own ----------------------------------------------------------------------

    /**
     * Keep a sensor reading, and say whether it should wake the regulator. Light enough to be
     * called for every reading from a broadcast receiver: with the phone in charge it writes one
     * small file and computes nothing.
     */
    fun keepReading(mgdl: Int, sampledAtEpochMs: Long): Boolean {
        if (!autonomy.addReading(GlucoseReading(sampledAtEpochMs, mgdl.toDouble()))) return false
        // A reading is also when the phone's silence is found to have grown long enough.
        if (observeLeadership() != null) refreshFace()
        // Whoever leads: is insulin doing what the forecasts said? The owner is told when it is not.
        runCatching { checkInsulinEffect(System.currentTimeMillis()) }
            .onFailure { android.util.Log.e("ComboController", "insulin check failed: ${it.javaClass.simpleName}") }
        runCatching { onSensingTick(System.currentTimeMillis()) }
        return runner.wantsToRun() || runner.needsSettling()
    }

    // ---- sensing a walk or a sport session ------------------------------------------------------

    /** Register with Health Services, or tell the owner once a day that the sensors are not allowed yet. */
    fun startSensing() {
        if (!ActivitySensing.granted(context)) {
            val askedAt = runCatching { files.read(SENSING_FILE).optLong("permissionAskedAt") }.getOrDefault(0L)
            if (System.currentTimeMillis() - askedAt > 24 * 60 * 60_000L) {
                saveSensing { put("permissionAskedAt", System.currentTimeMillis()) }
                notifySensingPermission()
            }
            return
        }
        val what = ActivitySensing.register(context)
        autonomy.addToJournal(JSONObject().put("at", System.currentTimeMillis()).put("action", "SENSING").put("text", "Распознавание нагрузки включено: $what").put("mode", autonomy.mode().name))
    }

    fun onHeartRate(atEpochMs: Long, bpm: Int) = synchronized(sensingLock) { applySensing(detector.onHeartRate(atEpochMs, bpm)) }

    fun onWorkout(atEpochMs: Long, type: String?, running: Boolean) = synchronized(sensingLock) {
        // Health Services also reports plain «not exercising» states (passive, asleep); only a workout, or the end of one we follow, is worth a line.
        if (running || detector.session?.let { !it.ended && it.fromWorkout } == true) autonomy.addToJournal(
            JSONObject().put("at", atEpochMs).put("action", "WORKOUT")
                .put("text", "Приложение тренировок: ${if (running) "идёт" else "закончена"} ${type ?: "без типа"}").put("mode", autonomy.mode().name)
        )
        applySensing(detector.onWorkout(atEpochMs, type, running))
    }

    fun onSensingTick(nowEpochMs: Long) = synchronized(sensingLock) {
        applySensing(detector.onTick(nowEpochMs))
        profileFinishedSessions(nowEpochMs)
    }

    /** Every session that has ended - sensed or entered by hand - gets its heart-rate profile sent to the phone once. */
    private fun profileFinishedSessions(nowEpochMs: Long) {
        val profiled = runCatching { files.read(SENSING_FILE).optJSONArray("profiled") }.getOrNull()
            ?.let { array -> List(array.length()) { array.getLong(it) } }.orEmpty().toMutableList()
        var changed = false
        autonomy.activities()
            .filter { it.activeEndEpochMs <= nowEpochMs && it.activeEndEpochMs >= nowEpochMs - 6 * 60 * 60_000L && profiled.none { p -> kotlin.math.abs(p - it.startEpochMs) < 60_000L } }
            .forEach { record ->
                val profile = detector.profile(record.startEpochMs, record.activeEndEpochMs) ?: return@forEach
                outbox.append(PumpEvent(0, PumpEvent.Type.ACTIVITY_PROFILE, record.startEpochMs, pumpSerial = heldPump(), note = profile.toTokens()))
                autonomy.addToJournal(
                    JSONObject().put("at", nowEpochMs).put("action", "SENSING").put("mode", autonomy.mode().name)
                        .put("text", "Пульс за нагрузку с ${clock(record.startEpochMs)}: средний ${profile.avgBpm}, максимум ${profile.maxBpm}, " +
                            "усилие ${profile.effortMinutes} мин, тяжёлое ${profile.hardMinutes} мин (покой ${profile.restingBpm})")
                )
                profiled += record.startEpochMs
                changed = true
            }
        if (changed) {
            while (profiled.size > 20) profiled.removeAt(0)
            saveSensing { put("profiled", JSONArray(profiled)) }
            sendEvents()
        }
    }

    fun onSensingPermissionLost() {
        notifySensingPermission()
    }

    /** The owner's answer on the notice: SPORT or WALK names the session, NONE takes it back everywhere. */
    fun answerSensedActivity(answer: String, startEpochMs: Long) = synchronized(sensingLock) {
        val current = detector.session
        if (current == null || kotlin.math.abs(current.startEpochMs - startEpochMs) > 60_000L) return@synchronized
        when (answer) {
            "NONE" -> {
                detector.decline(System.currentTimeMillis())
                autonomy.removeActivity(current.startEpochMs)
                outbox.append(PumpEvent(0, PumpEvent.Type.ACTIVITY_CANCELLED, current.startEpochMs, pumpSerial = heldPump(), note = "не нагрузка"))
                autonomy.addToJournal(JSONObject().put("at", System.currentTimeMillis()).put("action", "SENSING").put("text", "Хозяин ответил: не нагрузка; запись с ${clock(current.startEpochMs)} снята").put("mode", autonomy.mode().name))
                runCatching { context.getSystemService(android.app.NotificationManager::class.java).cancel(ACTIVITY_NOTIFICATION_ID) }
                sendEvents()
                refreshFace()
            }
            "SPORT", "WALK" -> {
                val changed = detector.confirm(answer)
                val record = ActivityRecord(current.startEpochMs, current.durationMinutes, current.mode)
                if (changed) {
                    autonomy.addActivity(record)
                    outbox.append(PumpEvent(0, PumpEvent.Type.ACTIVITY, record.startEpochMs, pumpSerial = heldPump(), activityMode = record.mode, activityDurationMinutes = record.durationMinutes, activityStartOffsetMinutes = 0))
                    sendEvents()
                }
                notifyActivity(record, "Нагрузка записана", "Телефон запишет её при связи.")
                refreshFace()
            }
        }
        saveSensing()
    }

    private fun applySensing(signals: List<ActivityDetector.Signal>) {
        if (signals.isEmpty()) { saveSensing(); return }
        signals.forEach { signal ->
            when (signal) {
                is ActivityDetector.Signal.Started  -> {
                    val record = ActivityRecord(signal.startEpochMs, signal.durationMinutes, signal.mode)
                    autonomy.addActivity(record)
                    outbox.append(PumpEvent(0, PumpEvent.Type.ACTIVITY, record.startEpochMs, pumpSerial = heldPump(), activityMode = record.mode, activityDurationMinutes = record.durationMinutes, activityStartOffsetMinutes = 0))
                    val text = "Нагрузка замечена часами: ${ActivityEffect.name(record.mode)} с ${clock(record.startEpochMs)} (${signal.why}). " +
                        "Записана; базал не выше ${(ActivityEffect.newInsulinFactor(record, System.currentTimeMillis()) * 100).toInt()} %, микродоз нет"
                    autonomy.addToJournal(JSONObject().put("at", System.currentTimeMillis()).put("action", "SENSING").put("text", text).put("mode", autonomy.mode().name))
                    notifySensedActivity(record, signal.sure, signal.why)
                    sendEvents()
                    refreshFace()
                }
                is ActivityDetector.Signal.Extended -> {
                    val current = detector.session ?: return@forEach
                    val record = ActivityRecord(signal.startEpochMs, signal.durationMinutes, current.mode)
                    autonomy.addActivity(record)
                    outbox.append(PumpEvent(0, PumpEvent.Type.ACTIVITY, record.startEpochMs, pumpSerial = heldPump(), activityMode = record.mode, activityDurationMinutes = record.durationMinutes, activityStartOffsetMinutes = 0))
                    sendEvents()
                }
                is ActivityDetector.Signal.Ended    -> {
                    val current = detector.session ?: return@forEach
                    val record = ActivityRecord(signal.startEpochMs, signal.durationMinutes, current.mode)
                    autonomy.addActivity(record)
                    outbox.append(PumpEvent(0, PumpEvent.Type.ACTIVITY, record.startEpochMs, pumpSerial = heldPump(), activityMode = record.mode, activityDurationMinutes = record.durationMinutes, activityStartOffsetMinutes = 0))
                    autonomy.addToJournal(JSONObject().put("at", System.currentTimeMillis()).put("action", "SENSING").put("text", "Нагрузка закончилась: ${ActivityEffect.name(record.mode)} ${record.durationMinutes} мин с ${clock(record.startEpochMs)}; хвост ${record.tailMinutes} мин").put("mode", autonomy.mode().name))
                    notifyActivity(record, "Нагрузка закончилась", "Хвост учитывается ещё ${record.tailMinutes / 60} ч.")
                    sendEvents()
                    refreshFace()
                }
            }
        }
        saveSensing()
    }

    private fun saveSensing(extra: JSONObject.() -> Unit = {}) {
        runCatching {
            val json = detector.toJson()
            val previous = runCatching { files.read(SENSING_FILE) }.getOrNull()
            previous?.optLong("permissionAskedAt")?.takeIf { it > 0L }?.let { json.put("permissionAskedAt", it) }
            previous?.optJSONArray("profiled")?.let { json.put("profiled", it) }
            json.extra()
            files.write(SENSING_FILE, json)
        }
    }

    private fun clock(epochMs: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(epochMs))

    /** «Нагрузка?» with the answers as buttons; a workout the fitness app reported needs only a way to deny it. */
    private fun notifySensedActivity(record: ActivityRecord, sure: Boolean, why: String) {
        runCatching {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(
                android.app.NotificationChannel(ACTIVITY_CHANNEL, "Нагрузка записана", android.app.NotificationManager.IMPORTANCE_DEFAULT).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 150, 250)
                }
            )
            fun answer(code: Int, answer: String) = android.app.PendingIntent.getBroadcast(
                context, code,
                Intent(context, ActivityAnswerReceiver::class.java).setAction(ActivityAnswerReceiver.ACTION + "." + answer)
                    .putExtra(ActivityAnswerReceiver.EXTRA_ANSWER, answer).putExtra(ActivityAnswerReceiver.EXTRA_START, record.startEpochMs),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val title = if (sure) "Нагрузка: ${ActivityEffect.name(record.mode)}" else "Нагрузка? ${ActivityEffect.name(record.mode)}"
            val text = "С ${clock(record.startEpochMs)}: $why. Записана, базал снижен, микродоз нет." + if (sure) " Если это не нагрузка — отмените." else " Что это было?"
            val builder = android.app.Notification.Builder(context, ACTIVITY_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
            if (!sure) {
                builder.addAction(android.app.Notification.Action.Builder(null, "Спорт", answer(1, "SPORT")).build())
                builder.addAction(android.app.Notification.Action.Builder(null, "Прогулка", answer(2, "WALK")).build())
            }
            builder.addAction(android.app.Notification.Action.Builder(null, "Не нагрузка", answer(3, "NONE")).build())
            manager.notify(ACTIVITY_NOTIFICATION_ID, builder.build())
        }.onFailure { Log.w("ComboController", "sensed activity notice not shown: ${it.javaClass.simpleName}") }
    }

    private fun notifySensingPermission() {
        runCatching {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(android.app.NotificationChannel(ACTIVITY_CHANNEL, "Нагрузка записана", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val open = android.app.PendingIntent.getActivity(
                context, 4, Intent(context, ActivitySensingPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val text = "Чтобы часы сами замечали спорт и прогулки, разрешите им пульс и распознавание активности."
            manager.notify(
                SENSING_PERMISSION_NOTIFICATION_ID,
                android.app.Notification.Builder(context, ACTIVITY_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_directions)
                    .setContentTitle("Датчики нагрузки")
                    .setContentText(text)
                    .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    /** Why the watch is not on its own right now, or null when it is. */
    fun whyNotAlone(): String? = (runner.standing() as? AutonomyPolicy.Standing.NotAlone)?.reason

    /**
     * Carbohydrates the owner entered on the watch. Kept here for the watch's own forecast, and
     * put in the queue for the phone's records, which get them the moment the phone is in touch -
     * at once when it is, later when it is not. Nobody waits for the phone.
     */
    fun keepCarbs(grams: Int, atEpochMs: Long, foodType: String?) {
        autonomy.addCarbs(CarbsRecord(atEpochMs, grams))
        outbox.append(PumpEvent(0, PumpEvent.Type.CARBS, atEpochMs, pumpSerial = heldPump(), note = foodType, carbsGrams = grams))
        sendEvents()
        refreshFace()
    }

    /**
     * A walk or a sport session the owner entered on the watch. Kept for the watch's own forecast
     * and basal, and put in the queue for the phone, which records it as it would one entered
     * through it, as soon as it is in touch. Nobody waits for the phone.
     *
     * @return true when the watch is on its own and should regulate now rather than at the next
     *   reading; the caller does that on a worker thread, since it may be a pump session.
     */
    fun keepActivity(mode: String, durationMinutes: Int, startOffsetMinutes: Int, carbType: String?, atEpochMs: Long): Boolean {
        val start = atEpochMs + startOffsetMinutes * 60_000L
        val activity = ActivityRecord(start, durationMinutes, mode)
        autonomy.addActivity(activity)
        outbox.append(
            PumpEvent(
                0, PumpEvent.Type.ACTIVITY, start, pumpSerial = heldPump(), note = carbType,
                activityMode = mode, activityDurationMinutes = durationMinutes, activityStartOffsetMinutes = startOffsetMinutes
            )
        )
        val cap = (ActivityEffect.newInsulinFactor(activity, atEpochMs) * 100).toInt()
        val text = "Нагрузка с часов: ${ActivityEffect.name(mode)} $durationMinutes мин, " +
            (if (startOffsetMinutes > 0) "начало через $startOffsetMinutes мин" else "начало сейчас") +
            ". Часы считают её в прогнозе и держат базал не выше $cap %; телефон запишет её, как только будет на связи"
        autonomy.addToJournal(JSONObject().put("at", atEpochMs).put("action", "ACTIVITY").put("text", text).put("mode", autonomy.mode().name))
        notifyActivity(activity, "Нагрузка записана", "Телефон запишет её при связи.")
        sendEvents()
        refreshFace()
        return runner.wantsToRun()
    }

    /**
     * The wearer asked (30.10.2026) to be told plainly when a session is on record: a notice that
     * vibrates once and stays in the tray, naming the window and the tail, so that a session entered
     * for the wrong time is seen at once (29.10 one was entered at 16:02 for 17:02 and ran at 21:00).
     */
    private fun notifyActivity(activity: ActivityRecord, title: String, trailer: String) {
        runCatching {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(
                android.app.NotificationChannel(ACTIVITY_CHANNEL, "Нагрузка записана", android.app.NotificationManager.IMPORTANCE_DEFAULT).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 150, 250)
                }
            )
            val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            val text = "${ActivityEffect.name(activity.mode).replaceFirstChar { it.uppercase() }} ${activity.durationMinutes} мин: " +
                "${clock.format(java.util.Date(activity.startEpochMs))}–${clock.format(java.util.Date(activity.activeEndEpochMs))}" +
                (if (activity.tailMinutes > 0) ", хвост до ${clock.format(java.util.Date(activity.tailEndEpochMs))}" else "") + ". $trailer"
            manager.notify(
                ACTIVITY_NOTIFICATION_ID,
                android.app.Notification.Builder(context, ACTIVITY_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_directions)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .build()
            )
        }.onFailure { Log.w("ComboController", "activity notice not shown: ${it.javaClass.simpleName}") }
    }

    /**
     * Walks and sport sessions the phone has on record, however they were entered, kept here too: on
     * its own the watch then neither corrects the rise of a session nor forgets its tail. 29.10.2026
     * the phone knew of a session the watch did not, and the watch alone gave three micro-doses into it.
     */
    private fun keepPhoneActivities(snapshot: RegulationSnapshot) {
        if (snapshot.activities.isEmpty()) return
        val known = autonomy.activities()
        snapshot.activities.forEach { window ->
            val record = ActivityRecord(window.startEpochMs, window.durationMinutes, window.mode, window.tailMinutes)
            // Same start = same session, whatever the phone's copy says about mode or length: a session
            // the watch sensed grows here first and the phone's record follows, never the other way.
            val alreadyKnown = known.any { kotlin.math.abs(it.startEpochMs - record.startEpochMs) < 60_000L }
            if (alreadyKnown) return@forEach
            autonomy.addActivity(record)
            val start = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(record.startEpochMs))
            val text = "Нагрузка с телефона: ${ActivityEffect.name(record.mode)} ${record.durationMinutes} мин, начало $start, " +
                "хвост ${record.tailMinutes} мин. Часы учтут её в прогнозе и не будут корректировать подъём во время неё"
            autonomy.addToJournal(JSONObject().put("at", System.currentTimeMillis()).put("action", "ACTIVITY").put("text", text).put("mode", autonomy.mode().name))
            notifyActivity(record, "Нагрузка записана на телефоне", "Часы учтут её.")
        }
    }

    /** The owner's bolus limit as the phone last said it, even under a lease that has since run out. */
    private fun ownerBolusLimitTenths(): Int = lease?.maxBolusTenthsIU ?: maxBolusTenthsIU()

    /** The dose for a meal, worked out on the watch; see [WatchRegulator.adviseBolus]. Written down either way. */
    fun bolusAdvice(carbsG: Int): WatchRegulator.BolusAdvice {
        val advice = runner.adviseBolus(carbsG, ownerBolusLimitTenths())
        val text = advice.refusal?.let { "Расчёт на часах для $carbsG г не сделан: $it" }
            ?: "Расчёт на часах: $carbsG г → ${String.format(java.util.Locale.getDefault(), "%.1f", advice.tenthsIU / 10.0)} ЕД (${advice.lines.joinToString("; ")})"
        autonomy.addToJournal(JSONObject().put("at", System.currentTimeMillis()).put("action", "BOLUS_ADVICE").put("text", text).put("mode", autonomy.mode().name))
        return advice
    }

    /** A dose typed in on the watch with the phone away; see [WatchRegulator.adviseManualBolus]. Written down either way. */
    fun manualBolusAdvice(tenthsIU: Int): WatchRegulator.BolusAdvice {
        val advice = runner.adviseManualBolus(tenthsIU, ownerBolusLimitTenths())
        fun u(tenths: Int) = String.format(java.util.Locale.getDefault(), "%.1f", tenths / 10.0)
        val text = advice.refusal?.let { "Доза с часов ${u(tenthsIU)} ЕД не принята: $it" }
            ?: "Доза с часов: ${u(advice.tenthsIU)} ЕД (${advice.lines.joinToString("; ")})"
        autonomy.addToJournal(JSONObject().put("at", System.currentTimeMillis()).put("action", "BOLUS_ADVICE").put("text", text).put("mode", autonomy.mode().name))
        return advice
    }

    /**
     * While the watch leads, a tick every five minutes keeps it going when the sensor falls
     * silent: the pump still gets read for boluses given on it, and the journal says that
     * nothing is decided without glucose. Readings reschedule it, so with the sensor present the
     * tick never fires; a phone back in charge ends the chain.
     */
    fun isLeadingAlone(): Boolean = runner.wantsToRun()

    fun scheduleTickIfAlone() {
        if (runner.wantsToRun()) ControllerService.schedule(context, ControllerService.PATH_TICK, null, ControllerService.TICK_MS)
    }

    /**
     * The meal the owner confirmed on the watch: the carbohydrates are kept first - they are eaten
     * whatever the pump does - then the bolus is given under the watch's own lease. A pump session;
     * never called on the main thread. Returns the pump's answer, or null when no pump is paired.
     */
    fun deliverOwnerBolus(carbsG: Int, foodType: String?, tenthsIU: Int): ComboResult? = synchronized(pumpTurn) {
        val now = System.currentTimeMillis()
        if (carbsG > 0) keepCarbs(carbsG, now, foodType)
        if (tenthsIU <= 0) return@synchronized null
        val result = runner.deliverOwnerBolus(tenthsIU, ownerBolusLimitTenths())
        val units = String.format(java.util.Locale.getDefault(), "%.1f", tenthsIU / 10.0)
        val what = if (carbsG > 0) "$units ЕД на $carbsG г" else "$units ЕД, доза владельца"
        val text = when (result?.outcome) {
            Outcome.DONE    -> "Болюс с часов: $what — подан"
            Outcome.UNKNOWN -> "Болюс с часов: $what — исход не выяснен, часы сверяются с помпой"
            null            -> "Болюс с часов: $what — помпа не привязана"
            else            -> "Болюс с часов: $what — ${result.outcome}: ${result.reason}"
        }
        autonomy.addToJournal(JSONObject().put("at", now).put("action", "BOLUS").put("text", text).put("mode", autonomy.mode().name))
        // The phone learns of the bolus from the pump's own receipt; this line tells it why.
        outbox.append(PumpEvent(0, PumpEvent.Type.WATCH_NOTE, now, pumpSerial = heldPump(), note = text))
        result?.snapshot?.let { lastSnapshot = it }
        sendEvents()
        sendHeartbeat()
        result
    }

    // ---- insulin not acting: the owner is told, and asked ------------------------------------------

    /** Glucose now against what the forecasts of an hour or two ago promised; see [InsulinEffectiveness]. */
    fun insulinVerdict(nowEpochMs: Long = System.currentTimeMillis()): InsulinEffectiveness.Verdict =
        InsulinEffectiveness.evaluate(nowEpochMs, autonomy.readings(), autonomy.forecastsMade(), autonomy.delivery())

    private fun checkInsulinEffect(now: Long) {
        val verdict = insulinVerdict(now)
        val step = InsulinAlertPolicy.onVerdict(autonomy.triage(), verdict, now)
        autonomy.saveTriage(step.state)
        if (step.clear) cancelInsulinNotice()
        if (!step.notify) return
        notifyInsulin(
            title = if (verdict.level == InsulinEffectiveness.Level.STRONG) "Инсулин почти не действует" else "Инсулин действует слабо",
            text = verdict.text + " Нажмите, чтобы разобраться: набор, болезнь или еда."
        )
        autonomy.addToJournal(JSONObject().put("at", now).put("action", "INSULIN_CHECK").put("text", verdict.text).put("mode", autonomy.mode().name))
        outbox.append(PumpEvent(0, PumpEvent.Type.WATCH_NOTE, now, pumpSerial = heldPump(), note = "Часы: ${verdict.text}"))
        sendEvents()
    }

    /** A notification that vibrates and opens the triage screen when tapped. */
    private fun notifyInsulin(title: String, text: String) {
        runCatching {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(
                android.app.NotificationChannel(INSULIN_CHANNEL, "Инсулин не действует", android.app.NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 300, 150, 300, 150, 600)
                }
            )
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, TriageActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.notify(
                INSULIN_NOTIFICATION_ID,
                android.app.Notification.Builder(context, INSULIN_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                    .setCategory(android.app.Notification.CATEGORY_ALARM)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    private fun cancelInsulinNotice() = runCatching { context.getSystemService(android.app.NotificationManager::class.java).cancel(INSULIN_NOTIFICATION_ID) }

    /** What the triage screen shows the owner. */
    class TriageFacts(
        val verdict: InsulinEffectiveness.Verdict,
        val bgMgdl: Int?,
        val deltaPer5: Double?,
        val iobU: Double?,
        val bolusesLast2hU: Double,
        val resistance: AutonomyStore.Resistance?,
        val quietUntilEpochMs: Long,
        val setChangedAtEpochMs: Long,
        val alone: Boolean
    )

    fun triageFacts(): TriageFacts {
        val now = System.currentTimeMillis()
        val face = faceFacts()
        val trend = GlucoseTrend.from(autonomy.readings(), now)
        val triage = autonomy.triage()
        return TriageFacts(
            verdict = insulinVerdict(now),
            bgMgdl = trend?.takeIf { now - it.atEpochMs <= 15 * 60_000L }?.mgdl?.roundToInt(),
            deltaPer5 = trend?.takeIf { it.known }?.delta,
            iobU = face.iobU,
            bolusesLast2hU = autonomy.delivery().boluses.filter { it.atEpochMs >= now - InsulinEffectiveness.WINDOW_MS }.sumOf { it.units },
            resistance = autonomy.resistance(),
            quietUntilEpochMs = triage.optLong(InsulinAlertPolicy.KEY_QUIET_UNTIL),
            setChangedAtEpochMs = triage.optLong(InsulinAlertPolicy.KEY_SET_CHANGED_AT),
            alone = runner.standing() == AutonomyPolicy.Standing.Alone
        )
    }

    private fun journalLine(now: Long, action: String, text: String) =
        autonomy.addToJournal(JSONObject().put("at", now).put("action", action).put("text", text).put("mode", autonomy.mode().name))

    /** The owner changed the infusion set: quiet for a while, a look an hour on, and the phone's records told. */
    fun triageSetChanged() {
        val now = System.currentTimeMillis()
        val bg = GlucoseTrend.from(autonomy.readings(), now)?.mgdl?.roundToInt()
        val state = InsulinAlertPolicy.quiet(autonomy.triage(), now, QUIET_AFTER_SET_CHANGE_MIN, "set changed")
            .put(InsulinAlertPolicy.KEY_SET_CHANGED_AT, now).put(InsulinAlertPolicy.KEY_SET_CHANGED_BG, bg ?: JSONObject.NULL)
        autonomy.saveTriage(state)
        cancelInsulinNotice()
        val text = "Часы: владелец сменил набор/канюлю" + (bg?.let { " при сахаре $it" } ?: "") + "; проверка через час"
        journalLine(now, "SET_CHANGED", text)
        outbox.append(PumpEvent(0, PumpEvent.Type.WATCH_NOTE, now, pumpSerial = heldPump(), note = text))
        ControllerService.schedule(context, ControllerService.PATH_TRIAGE_CHECK, null, RECHECK_AFTER_SET_CHANGE_MS)
        sendEvents()
    }

    /** An hour after the set change: did glucose turn? Said either way, so that the owner knows where things stand. */
    fun triageCheckAfterSetChange() {
        val now = System.currentTimeMillis()
        val state = autonomy.triage()
        val changedAt = state.optLong(InsulinAlertPolicy.KEY_SET_CHANGED_AT).takeIf { it > 0L } ?: return
        val before = state.optInt(InsulinAlertPolicy.KEY_SET_CHANGED_BG, -1).takeIf { it > 0 }
        val trend = GlucoseTrend.from(autonomy.readings(), now) ?: return
        val bg = trend.mgdl.roundToInt()
        val fell = before != null && before - bg >= SET_CHANGE_FALL_MGDL
        val better = bg < InsulinAlertPolicy.CLEAR_BELOW_MGDL || fell || (trend.known && trend.delta <= -3.0)
        val minutes = ((now - changedAt) / 60_000L).toInt()
        val was = before?.let { " (был $it)" } ?: ""
        val text = if (better) "Через $minutes мин после смены набора сахар $bg$was: инсулин заработал."
        else "Через $minutes мин после смены набора сахар всё ещё $bg$was. Если доза после смены введена — возможно, болезнь или день без движения: откройте опрос."
        notifyInsulin(if (better) "После смены набора: лучше" else "После смены набора: без перемен", text)
        journalLine(now, "SET_CHANGE_CHECK", text)
    }

    /** The owner says insulin works worse today (illness, a day spent sitting): more of it, for [hours]. */
    fun triageResistance(extraPercent: Int, hours: Int, reason: String) {
        val now = System.currentTimeMillis()
        val factor = (1.0 + extraPercent / 100.0).coerceIn(1.0, WatchRegulator.MAX_RESISTANCE_FACTOR)
        autonomy.setResistance(AutonomyStore.Resistance(factor, now + hours * 60 * 60_000L, now, reason))
        autonomy.saveTriage(InsulinAlertPolicy.quiet(autonomy.triage(), now, QUIET_AFTER_RESISTANCE_MIN, "resistance mode"))
        cancelInsulinNotice()
        val caps = WatchRegulator.resistantCaps(factor)
        val text = "Часы: режим «инсулин работает хуже» +$extraPercent % на $hours ч ($reason): дозы и коррекции на часах усилены, " +
            "базал до ${caps.maxRaisePercent} %, микродозы до ${String.format(java.util.Locale.US, "%.1f", caps.maxSmbU)} ЕД. " +
            "На телефоне поставьте профиль ${100 + extraPercent} % на то же время."
        journalLine(now, "RESISTANCE_ON", text)
        outbox.append(PumpEvent(0, PumpEvent.Type.WATCH_NOTE, now, pumpSerial = heldPump(), note = text))
        sendEvents()
        refreshFace()
        // Alone, the strengthened coefficients are acted on now rather than at the next reading.
        if (runner.wantsToRun()) ControllerService.start(context, ControllerService.PATH_GLUCOSE, null)
    }

    fun triageEndResistance() {
        val now = System.currentTimeMillis()
        autonomy.setResistance(null)
        val text = "Часы: режим «инсулин работает хуже» выключен владельцем"
        journalLine(now, "RESISTANCE_OFF", text)
        outbox.append(PumpEvent(0, PumpEvent.Type.WATCH_NOTE, now, pumpSerial = heldPump(), note = text))
        sendEvents()
        refreshFace()
    }

    /** The owner wants quiet about insulin for [minutes], with [why] on record. */
    fun triageQuiet(minutes: Int, why: String) {
        val now = System.currentTimeMillis()
        autonomy.saveTriage(InsulinAlertPolicy.quiet(autonomy.triage(), now, minutes, why))
        cancelInsulinNotice()
        journalLine(now, "INSULIN_CHECK_QUIET", "Владелец: $why; часы молчат об инсулине $minutes мин")
    }

    /** Let the regulator look at the newest reading; see [AutonomyRunner.onReading]. */
    fun regulate(rehearsal: Boolean = false): JSONObject? = synchronized(pumpTurn) {
        val entry = runner.onReading(rehearsal)
        if (rehearsal && entry != null) files.write(REHEARSAL_FILE, entry)
        if (!rehearsal && entry != null) refreshFace()
        entry
    }

    /** What the pump delivers at 100 % in each hour, from the profile the driver read off the pump the watch holds. */
    private fun pumpBasalUph(): List<Double> = runCatching {
        val saved = files.read(BASAL_PROFILE_FILE)
        if (saved.getString("pump") != heldPump()) emptyList()
        else saved.getJSONArray("factors").let { factors -> List(factors.length()) { factors.getInt(it) / 1000.0 } }
    }.getOrDefault(emptyList())

    fun onCommand(payload: String): ComboResult = synchronized(pumpTurn) {
        phoneHeard()
        observeLeadership()
        val command = ComboCommand.fromJson(JSONObject(payload))
        // The bench's own manual sessions use the same pump and pairing; never overlap with them.
        if (ManualPumpRuntime.get(context).usingBluetoothNow())
            return ComboResult(command.id, Outcome.REFUSED, System.currentTimeMillis(), reason = "the bench is using the pump")
                .also { publish(it) }

        leaveTheSensorItsWindow()
        val result = executor.execute(command, lease)
        notePumpAnswer(result)
        files.write(RESULT_FILE, result.toJson())
        publish(result)
        result
    }

    fun onEventsAck(payload: String) {
        phoneHeard()
        outbox.acknowledge(JSONObject(payload).getLong("upTo"))
    }

    /** Settle an unclear ending by reading the pump; see [ComboExecutor.reconcileNow]. */
    fun healIfNeeded(): Boolean = synchronized(pumpTurn) {
        if (!executor.awaitingReconciliation) return true
        leaveTheSensorItsWindow()
        val healed = executor.reconcileNow()
        sendEvents()
        sendHeartbeat()
        healed
    }

    val awaitingReconciliation: Boolean get() = executor.awaitingReconciliation

    // ---- outbound --------------------------------------------------------------------------------

    private fun publish(result: ComboResult) {
        // The events ride inside the answer, so the phone has recorded what the pump did before
        // the command that caused it returns - AAPS reads its own records straight afterwards.
        val pending = outbox.pending()
        val message = result.toJson()
        if (pending.isNotEmpty()) message.put("events", PumpEvent.listToJson(pending).getJSONArray("events"))
        toPhone(ComboWatchProtocol.PATH_RESULT, message)
        sendHeartbeat()
    }

    fun sendEvents() {
        val pending = outbox.pending()
        if (pending.isNotEmpty()) toPhone(ComboWatchProtocol.PATH_EVENTS, PumpEvent.listToJson(pending))
    }

    fun sendHeartbeat() {
        val now = System.currentTimeMillis()
        val current = lease
        val held = heldPump()
        val heartbeat = WatchHeartbeat(
            atEpochMs = now,
            leaseGeneration = current?.generation ?: 0L,
            leaseLive = current?.liveAt(now) == true,
            executorBusy = executor.isBusy,
            awaitingReconciliation = executor.awaitingReconciliation,
            pumpReachable = pumpReachable,
            watchBatteryPercent = context.getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 },
            // Only ever a reading of the pump held now, never one left over from another.
            snapshot = lastSnapshot?.takeIf { it.pumpSerial == held },
            heldPump = held
        )
        files.write(HEARTBEAT_FILE, heartbeat.toJson())
        toPhone(ComboWatchProtocol.PATH_HEARTBEAT, heartbeat.toJson())
        refreshFace()
    }

    /** Hand a message to the relay in the AAPS watch app, which owns the link to the phone. */
    private fun toPhone(path: String, payload: JSONObject) {
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_TO_PHONE)
                    .setComponent(ComponentName(RELAY_PACKAGE, RELAY_RECEIVER))
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(EXTRA_PATH, path)
                    .putExtra(EXTRA_PAYLOAD, payload.toString()),
                PERMISSION_RELAY
            )
        }
    }

    // ---- debug, for exercising the controller from adb without a phone ---------------------------

    /**
     * Grant a short lease locally. Only for debuggable builds driven from adb: it stands in for
     * the phone so that the controller can be tested against the pump on its own.
     */
    fun grantDebugLease(validForMs: Long): ControlLease {
        check(BuildConfig.DEBUG) { "debug lease is only available in debug builds" }
        // Standing in for the phone is a bench technique. A pump that may be in use takes its
        // orders from the phone alone, and the phone's lease is not to be overwritten from adb.
        check(ManualPumpRuntime.get(context).pairedPump()?.isTestPump == true) { "debug lease is only for the off-body test pump" }
        val now = System.currentTimeMillis()
        val granted = ControlLease(
            generation = maxOf(lease?.generation ?: 0L, DEBUG_GENERATION),
            issuedAtEpochMs = now, expiresAtEpochMs = now + validForMs,
            pumpSerial = heldPump() ?: "none", controllerIsWatch = true
        )
        lease = granted
        files.write(LEASE_FILE, granted.toJson())
        return granted
    }

    /** The last commands that reached this watch and what came of them, oldest first; for the owner's journal. */
    fun recentCommands(limit: Int): List<CommandJournal.Entry> = journal.entries().takeLast(limit)

    /** The face's view of things; see [FaceFacts]. */
    fun faceFacts(): FaceFacts {
        val now = System.currentTimeMillis()
        val held = heldPump()
        val pump = savedSnapshot()
        val running = autonomy.delivery().tbrAt(now)
        val tbr = running?.let { FaceFacts.Tbr(it.percent, it.endEpochMs) }
            ?: pump?.takeIf { it.tbrRunning && it.tbrPercentage != null && it.tbrRemainingMinutes != null }
                ?.let { FaceFacts.Tbr(it.tbrPercentage!!, it.readAtEpochMs + it.tbrRemainingMinutes!! * 60_000L) }
                ?.takeIf { it.endsAtEpochMs > now }
        val alone = runner.standing() == AutonomyPolicy.Standing.Alone
        observeLeadership()
        val led = leadership.current()
        // The phone's forecast travels with every lease; the watch's own stands only while it is alone.
        val phoneSnapshot = autonomy.snapshot()
        val own = if (alone) autonomy.faceForecast() else null
        val forecast = if (alone) {
            val series = own?.optJSONArray("series")?.let { array -> List(array.length()) { array.getInt(it) } }.orEmpty()
            if (series.isNotEmpty()) FaceFacts.Forecast(series.min(), series.last(), own?.optLong("at", now) ?: now, byWatch = true, series = series)
            else autonomy.journal().lastOrNull()?.let { entry ->
                val min = entry.optInt("forecastMin", -1); val end = entry.optInt("forecastEnd", -1)
                if (min > 0 && end > 0) FaceFacts.Forecast(min, end, entry.optLong("at", now), byWatch = true) else null
            }
        } else {
            phoneSnapshot?.phoneForecast?.takeIf { it.isNotEmpty() }
                ?.let { FaceFacts.Forecast(it.min(), it.last(), phoneSnapshot.madeAtEpochMs, byWatch = false, series = it) }
        }
        val allReadings = autonomy.readings()
        val activity = ActivityEffect.current(autonomy.activities(), now)
        // Grams that keep the forecast out of a low: the watch's own number when alone, the same
        // arithmetic on the phone's forecast and coefficients otherwise. Nothing while no low is
        // forecast, however far from the target.
        val forecastAt = if (alone) own?.optLong("at", 0L) ?: 0L else phoneSnapshot?.madeAtEpochMs ?: 0L
        // With the phone leading, its own figure comes first: the same grams its «Предложены углеводы»
        // notice shows, so that the two screens never disagree (31.10.2026: phone 5 g, watch nothing).
        val phoneAsks = if (alone) null else phoneSnapshot?.carbsRequiredG
        // Without the phone's own figure (an older phone build) the phone's rule is run here on its forecast;
        // alone, the watch has run the same rule on its own forecast (WatchRegulator, carbsToTargetG).
        val phoneLike = if (alone || phoneAsks != null) null
        else phoneSnapshot?.let { s -> forecast?.series?.takeIf { it.isNotEmpty() }?.let { CarbsNeeded.likeThePhone(it, s.targetMgdl, s.sensitivityMgdlPerU, s.carbRatioGPerU) } }
        val needed = when {
            alone             -> own?.optInt("carbsToTargetG", 0)?.takeIf { it > 0 }
            phoneAsks != null -> phoneAsks.takeIf { it > 0 }
            else              -> phoneLike?.grams?.takeIf { it > 0 }
        }
        val neededWithin = when {
            alone             -> own?.optInt("carbsWithinMin", 0)?.takeIf { it > 0 }
            phoneAsks != null -> phoneSnapshot?.carbsRequiredWithinMin
            else              -> phoneLike?.withinMinutes
        }
        // Carbohydrates entered on this watch after that forecast are not in it yet: they come off
        // the hint at once, and off the phone's carbs-on-board figure, which it gets with the next
        // lease and counts at its next loop, up to five minutes later.
        val ownCarbsSinceForecast = autonomy.carbs().filter { it.atEpochMs > forecastAt }.sumOf { it.grams }
        val carbsNeeded = needed?.let { it - ownCarbsSinceForecast }?.takeIf { it > 0 }
        if (carbsNeeded != lastFaceCarbsLogged) {
            lastFaceCarbsLogged = carbsNeeded
            android.util.Log.i("ComboFace", "face: carbs needed ${carbsNeeded ?: 0} g within ${neededWithin ?: 0} min (${if (alone) "the phone's rule on the watch's forecast" else if (phoneAsks != null) "the phone's own figure" else "the phone's rule on the phone's forecast"})")
        }
        // Insulin on board: the phone's figure (the watch's own when alone) plus the boluses the pump
        // reported after that figure was made. A dose given from this watch, or by hand on the pump,
        // is in the pump's history the next time the watch reads it, long before the phone's next
        // snapshot (19 Oct 2026: the face kept the old IOB for up to ten minutes after a bolus).
        // Decay over those few minutes is ignored, as with the carbohydrates below; once the phone
        // has counted a bolus its snapshot is newer than the bolus and it leaves this sum.
        val iobBaseAt = if (alone) own?.optLong("at", 0L) ?: 0L else phoneSnapshot?.madeAtEpochMs ?: 0L
        val bolusesSinceIobBase = autonomy.delivery().boluses.filter { it.atEpochMs > iobBaseAt }.sumOf { it.units }
        val iob = (if (alone) own?.optDouble("iobU")?.takeIf { it.isFinite() } else phoneSnapshot?.iobU)?.let { it + bolusesSinceIobBase }
        val ownCarbsSinceSnapshot = phoneSnapshot?.let { s -> autonomy.carbs().filter { it.atEpochMs > s.madeAtEpochMs }.sumOf { it.grams } } ?: 0
        val cob = if (alone) null else phoneSnapshot?.cobG?.let { it + ownCarbsSinceSnapshot }
        return FaceFacts(
            heldPump = held,
            phoneHeardEpochMs = phoneLastHeardEpochMs,
            leaseLive = lease?.liveAt(now) == true,
            // "Answers" means the last reading of it succeeded and no attempt has failed since. No age
            // limit: alone, the watch may rightly leave the pump untouched for hours, and a cross
            // would then only say that nobody asked. A failed attempt flips it at once.
            pumpReachable = pump?.readAtEpochMs?.let { readAt -> pumpNotReachedAtEpochMs < readAt } == true,
            pumpReadAtEpochMs = pump?.readAtEpochMs,
            tbr = tbr,
            reservoirUnits = pump?.reservoirUnits,
            mode = autonomy.mode(),
            alone = alone,
            leader = led?.leader ?: if (alone) LeadershipLog.Leader.WATCH else LeadershipLog.Leader.PHONE,
            leaderSinceEpochMs = led?.atEpochMs ?: 0L,
            forecast = forecast,
            readings = allReadings.filter { it.atEpochMs >= now - FACE_HISTORY_MS },
            deltaPer5Min = GlucoseTrend.from(allReadings, now)?.takeIf { it.known }?.delta,
            iobU = iob,
            cobG = cob,
            carbsNeededByPhone = !alone && phoneAsks != null,
            carbsNeededWithinMin = neededWithin,
            targetMgdl = phoneSnapshot?.targetMgdl?.takeIf { it.isFinite() && it > 0 },
            activity = activity,
            carbsNeededG = carbsNeeded
        )
    }

    /** Tell the face its complications have something new. Cheap: one broadcast per complication. */
    fun refreshFace() = FaceComplications.requestUpdate(context)

    fun stateJson(): JSONObject = JSONObject()
        .put("busy", executor.isBusy)
        .put("awaitingReconciliation", executor.awaitingReconciliation)
        .put("lease", lease?.toJson() ?: JSONObject.NULL)
        .put("leaseLive", lease?.liveAt(System.currentTimeMillis()) == true)
        .put("heldPump", heldPump() ?: JSONObject.NULL)
        .put("autonomyMode", autonomy.mode().name)
        .put("autonomyStanding", (runner.standing() as? AutonomyPolicy.Standing.NotAlone)?.reason ?: "alone")
        .put("readings", autonomy.readings().size)
        .put("snapshotAt", autonomy.snapshot()?.madeAtEpochMs ?: JSONObject.NULL)
        .put("pendingEvents", outbox.pending().size)
        .put("droppedEvents", outbox.droppedCount)
        .put("journal", JSONArray().apply { journal.entries().takeLast(12).forEach { put(entryJson(it)) } })

    // ---- limits ----------------------------------------------------------------------------------

    private fun maxBolusTenthsIU(): Int = runCatching {
        if (files.exists(LIMITS_FILE)) files.read(LIMITS_FILE).getInt("maxBolusTenthsIU") else CommandGate.DEFAULT_MAX_BOLUS_TENTHS_IU
    }.getOrDefault(CommandGate.DEFAULT_MAX_BOLUS_TENTHS_IU)

    // ---- persistence -----------------------------------------------------------------------------

    private fun entryJson(entry: CommandJournal.Entry) = JSONObject()
        .put("id", entry.id).put("startedAt", entry.startedAtEpochMs)
        .put("outcome", entry.outcome?.name ?: JSONObject.NULL)
        .put("reason", entry.reason ?: JSONObject.NULL)
        .put("kind", entry.kind?.name ?: JSONObject.NULL)
        .put("bolusTenthsIU", entry.bolusTenthsIU ?: JSONObject.NULL)
        .put("tbrPercentage", entry.tbrPercentage ?: JSONObject.NULL)
        .put("abandoned", entry.abandoned)

    private fun saveJournal(entries: List<CommandJournal.Entry>) {
        files.write(JOURNAL_FILE, JSONObject().put("entries", JSONArray().apply { entries.forEach { put(entryJson(it)) } }))
    }

    private fun loadJournal(): List<CommandJournal.Entry> {
        if (!files.exists(JOURNAL_FILE)) return emptyList()
        // A journal that cannot be read must not be mistaken for an empty one: that would forget
        // an unresolved command. Let it throw; the controller then refuses to start.
        val array = files.read(JOURNAL_FILE).getJSONArray("entries")
        return List(array.length()) { index ->
            val json = array.getJSONObject(index)
            CommandJournal.Entry(
                id = json.getString("id"),
                startedAtEpochMs = json.getLong("startedAt"),
                outcome = if (json.isNull("outcome")) null else Outcome.valueOf(json.getString("outcome")),
                reason = if (json.isNull("reason")) null else json.getString("reason"),
                kind = if (json.isNull("kind")) null else CommandKind.valueOf(json.getString("kind")),
                bolusTenthsIU = if (json.isNull("bolusTenthsIU")) null else json.getInt("bolusTenthsIU"),
                tbrPercentage = if (json.isNull("tbrPercentage")) null else json.getInt("tbrPercentage"),
                abandoned = json.optBoolean("abandoned", false)
            )
        }
    }

    private fun loadOutbox(): EventOutbox {
        val persist = { events: List<PumpEvent>, nextSeq: Long ->
            files.write(OUTBOX_FILE, PumpEvent.listToJson(events).put("nextSeq", nextSeq))
        }
        if (!files.exists(OUTBOX_FILE)) return EventOutbox(persist = persist)
        val saved = files.read(OUTBOX_FILE)
        return EventOutbox(PumpEvent.listFromJson(saved), saved.getLong("nextSeq"), persist = persist)
    }

    private fun loadLease(): ControlLease? =
        if (files.exists(LEASE_FILE)) runCatching { ControlLease.fromJson(files.read(LEASE_FILE)) }.getOrNull() else null

    companion object {

        const val ACTION_FROM_PHONE = "app.aaps.combo.action.FROM_PHONE"
        const val ACTION_TO_PHONE = "app.aaps.combo.action.TO_PHONE"
        const val PERMISSION_RELAY = "app.aaps.combo.permission.RELAY"
        const val EXTRA_PATH = "path"
        const val EXTRA_PAYLOAD = "payload"

        /** The relay lives in the AAPS watch app, the only app the phone's AAPS can reach. */
        private const val RELAY_PACKAGE = "info.nightscout.androidaps"
        private const val RELAY_RECEIVER = "app.aaps.wear.combo.ComboRelayReceiver"

        private const val JOURNAL_FILE = "controller-journal.json"
        private const val OUTBOX_FILE = "controller-outbox.json"
        private const val LEASE_FILE = "controller-lease.json"
        private const val LIMITS_FILE = "controller-limits.json"
        const val RESULT_FILE = "controller-result.json"
        const val HEARTBEAT_FILE = "controller-heartbeat.json"
        const val REHEARSAL_FILE = "autonomy-rehearsal.json"
        private const val PHONE_HEARD_FILE = "controller-phone-heard.json"
        private const val LEADERSHIP_FILE = "leadership-log.json"

        /** How far back the face's graph reaches. */
        private const val FACE_HISTORY_MS = 90 * 60_000L
        private const val CARBS_CHANNEL = "combo-autonomy-carbs"
        /** The phone's carbs ask buzzes the wrist only when wanted within this many minutes, and once per figure per this long. */
        private const val PHONE_CARBS_ASK_URGENT_MIN = 60
        private const val PHONE_CARBS_ASK_REPEAT_MS = 20 * 60_000L


        /** The longest the pump is held back for the sensor; a window and a session, with room to spare. */
        private const val MAX_SENSOR_WAIT_MS = 2 * 60_000L
        private const val SENSOR_WAIT_SLICE_MS = 5_000L
        private const val CARBS_NOTIFICATION_ID = 42
        private const val ACTIVITY_CHANNEL = "combo-activity"
        private const val ACTIVITY_NOTIFICATION_ID = 44
        private const val SENSING_PERMISSION_NOTIFICATION_ID = 45
        private const val SENSING_FILE = "autonomy-activity-sensing.json"
        private const val INSULIN_CHANNEL = "combo-insulin-check"
        private const val INSULIN_NOTIFICATION_ID = 43

        /** After a set change the new insulin needs time before anything can be said; the look comes an hour on. */
        private const val QUIET_AFTER_SET_CHANGE_MIN = 90
        private const val RECHECK_AFTER_SET_CHANGE_MS = 60 * 60_000L
        private const val SET_CHANGE_FALL_MGDL = 30
        private const val QUIET_AFTER_RESISTANCE_MIN = 90

        /** Written by the driver session each time it reads the profile off the pump. */
        private const val BASAL_PROFILE_FILE = "manual-basal-profile.json"

        /** Far below any real generation, which is a wall-clock timestamp from the phone. */
        private const val DEBUG_GENERATION = 1L

        @Volatile private var instance: ControllerHost? = null

        fun get(context: Context): ControllerHost =
            instance ?: synchronized(this) { instance ?: ControllerHost(context).also { instance = it } }
    }
}
