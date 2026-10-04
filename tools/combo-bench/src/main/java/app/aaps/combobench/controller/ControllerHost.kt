package app.aaps.combobench.controller

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import app.aaps.combobench.BenchFiles
import app.aaps.combobench.BuildConfig
import app.aaps.combobench.ManualPumpRuntime
import app.aaps.combobench.ManualPumpTarget
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
import app.aaps.pump.combowatch.regulation.CarbsRecord
import app.aaps.pump.combowatch.regulation.GlucoseReading
import app.aaps.pump.combowatch.regulation.GlucoseTrend
import org.json.JSONArray
import org.json.JSONObject

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

    private val session = DriverPumpSession(
        context, files,
        onEvent = { event ->
            // Stamped here, where the pump is known for certain, rather than left for the phone to infer.
            outbox.append(event.copy(pumpSerial = heldPump()))
            // The watch's own record of what the pump delivered, whoever asked for it.
            runCatching { autonomy.onPumpEvent(event, byWatch = runner.ownCommandInFlight) }
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
        beforePumpSession = ::leaveTheSensorItsWindow
    )

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

    /** A notification that vibrates: the one thing the watch asks of its wearer by itself. */
    private fun notifyCarbs(grams: Int, why: String) {
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
                    .setContentText("Одной остановки базала не хватит")
                    .setStyle(android.app.Notification.BigTextStyle().bigText("Одной остановки базала не хватит. $why"))
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
            runCatching { autonomy.saveSnapshot(RegulationSnapshot.fromJson(saved)) }
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
        }.getOrNull()
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
        return runner.wantsToRun() || runner.needsSettling()
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
        if (result.outcome == Outcome.FAILED && result.reason?.startsWith("pump not reached") == true) {
            pumpReachable = false
            pumpNotReachedAtEpochMs = System.currentTimeMillis()
        }
        result.snapshot?.let { lastSnapshot = it }
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
        val iob = if (alone) own?.optDouble("iobU")?.takeIf { it.isFinite() } else phoneSnapshot?.iobU
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
            cobG = if (alone) null else phoneSnapshot?.cobG,
            targetMgdl = phoneSnapshot?.targetMgdl?.takeIf { it.isFinite() && it > 0 }
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


        /** The longest the pump is held back for the sensor; a window and a session, with room to spare. */
        private const val MAX_SENSOR_WAIT_MS = 2 * 60_000L
        private const val SENSOR_WAIT_SLICE_MS = 5_000L
        private const val CARBS_NOTIFICATION_ID = 42

        /** Written by the driver session each time it reads the profile off the pump. */
        private const val BASAL_PROFILE_FILE = "manual-basal-profile.json"

        /** Far below any real generation, which is a wall-clock timestamp from the phone. */
        private const val DEBUG_GENERATION = 1L

        @Volatile private var instance: ControllerHost? = null

        fun get(context: Context): ControllerHost =
            instance ?: synchronized(this) { instance ?: ControllerHost(context).also { instance = it } }
    }
}
