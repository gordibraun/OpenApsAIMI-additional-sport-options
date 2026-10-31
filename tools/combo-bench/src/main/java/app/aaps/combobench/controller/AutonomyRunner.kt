package app.aaps.combobench.controller

import app.aaps.pump.combowatch.executor.AutonomyPolicy
import app.aaps.pump.combowatch.executor.ComboExecutor
import app.aaps.pump.combowatch.protocol.ComboResult
import app.aaps.pump.combowatch.protocol.ControlLease
import app.aaps.pump.combowatch.protocol.Outcome
import app.aaps.pump.combowatch.regulation.WatchRegulator
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/**
 * Runs the watch's own care of basal: on each sensor reading, if the watch is on its own, ask the
 * regulator what to do and - when the owner has switched that on - do it.
 *
 * It decides nothing itself. Whether the watch is alone is [AutonomyPolicy]'s answer, what to do
 * is [WatchRegulator]'s, and the command goes through the same executor, journal and gate as a
 * command from the phone, so that everything that makes those safe holds here too.
 */
internal class AutonomyRunner(
    private val store: AutonomyStore,
    private val executor: ComboExecutor,
    private val phoneLease: () -> ControlLease?,
    /** When anything last arrived from the phone, by this watch's clock. */
    private val phoneLastHeardEpochMs: () -> Long,
    private val heldPump: () -> String?,
    /** What the pump delivers at 100 % in each hour, U/h; empty when the watch has not read it. */
    private val pumpBasalUph: () -> List<Double>,
    /** Put a line for the phone's treatment records in the queue of pump events. */
    private val note: (text: String, atEpochMs: Long) -> Unit,
    /** Tell the wearer to eat: grams, and why. */
    private val askForCarbs: (grams: Int, why: String) -> Unit,
    /** Something else on this watch holds the pump's Bluetooth link right now - the bench's own screens. */
    private val pumpInOtherUse: () -> Boolean = { false },
    /** Called right before the pump is used; may wait, for the glucose sensor's window. */
    private val beforePumpSession: () -> Unit = {},
    /** When the pump was last read, by any session; null if never. Alone, the watch reads it now and then. */
    private val lastPumpReadEpochMs: () -> Long? = { null },
    /** Every answer the pump gives to the watch's own commands, for the face's "pump answers" mark. */
    private val onOwnResult: (ComboResult) -> Unit = {},
    /** The owner's bolus limit from the phone's last lease, tenths; the watch's own microboluses are held under it too. */
    private val ownerBolusLimitTenths: () -> Int? = { null },
    /** The hour of the pump's day at a moment. */
    private val hourOfDay: (Long) -> Int = { at -> Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.HOUR_OF_DAY) },
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {

    private val policy = AutonomyPolicy(nowEpochMs)
    private val regulator = WatchRegulator()

    /** True while a command the watch gave itself is running; what the driver reports then is the watch's doing. */
    @Volatile var ownCommandInFlight = false
        private set

    private var lastObserved: Pair<WatchRegulator.Rule, Int>? = null
    private var lastObservedAtEpochMs = 0L
    private var lastFailure: String? = null
    private var lastFailureNotedAtEpochMs = 0L
    private var lastCarbsHintAtEpochMs = 0L

    private fun standing(awaitingReconciliation: Boolean): AutonomyPolicy.Standing = policy.standing(
        phoneLease(), store.snapshot(), heldPump(), executor.isBusy || pumpInOtherUse(), awaitingReconciliation, phoneLastHeardEpochMs()
    )

    fun standing(): AutonomyPolicy.Standing = standing(executor.awaitingReconciliation)

    /** Whether a new reading is a reason to wake the regulator at all. With the phone in charge it never is. */
    fun wantsToRun(): Boolean = store.mode() != AutonomyPolicy.Mode.OFF && standing() == AutonomyPolicy.Standing.Alone

    /**
     * The watch would be on its own but for a command whose outcome is not known. With the phone
     * away nobody else will read the pump back, so the watch has to, before it decides anything.
     */
    fun needsSettling(): Boolean = store.mode() != AutonomyPolicy.Mode.OFF && executor.awaitingReconciliation &&
        standing(awaitingReconciliation = false) == AutonomyPolicy.Standing.Alone

    /**
     * Decide on the newest reading and act on the decision as far as the mode allows.
     *
     * With [rehearsal] the regulator is run whatever the standing and nothing is done or sent:
     * it only shows, in the returned entry, what the watch would make of the present moment.
     *
     * @return the journal entry, or null when there was nothing to decide.
     */
    fun onReading(rehearsal: Boolean = false): JSONObject? {
        val mode = store.mode()
        val standing = standing()
        if (!rehearsal && (mode == AutonomyPolicy.Mode.OFF || standing != AutonomyPolicy.Standing.Alone)) return null

        // A bolus given on the pump itself is known to the watch only once it has read the pump
        // (9 Oct: 3 U at 09:21, seen at 09:31, with insulin on board shown wrong until then). With
        // the phone away nobody else reads it, so the watch does, every half hour.
        if (!rehearsal) readPumpIfStale()

        val now = nowEpochMs()
        val snapshot = store.snapshot()
        val decision = regulator.decide(inputs(now))
        // Kept for later: did insulin do what this forecast said? See InsulinEffectiveness.
        if (!rehearsal) decision.forecastMgdl?.let { store.addForecastMade(now, it, byWatch = true) }
        val action = decision.action
        val entry = JSONObject()
            .put("at", now)
            .put("mode", if (rehearsal) "REHEARSAL" else mode.name)
            .put("rule", decision.rule.name)
            .put("wantedPercent", decision.wantedPercent)
            .put("text", decision.explanation)
            .put("glucose", decision.trend?.mgdl ?: JSONObject.NULL)
            .put("delta", decision.trend?.takeIf { it.known }?.delta ?: JSONObject.NULL)
            .put("forecastMin", decision.forecastMinMgdl ?: JSONObject.NULL)
            .put("forecastEnd", decision.forecastEndMgdl ?: JSONObject.NULL)
            .put("snapshotAgeMin", snapshot?.let { (now - it.madeAtEpochMs) / 60_000L } ?: JSONObject.NULL)
            .put("carbsHintG", decision.carbsHintG ?: JSONObject.NULL)
        store.resistance()?.let { entry.put("resistancePercent", it.extraPercent) }
        if (rehearsal) {
            entry.put("standing", (standing as? AutonomyPolicy.Standing.NotAlone)?.reason ?: "alone")
            // Side by side, for checking the watch's forecast against the phone's own from the
            // same snapshot. They differ where the phone counted its planned dose and basal.
            entry.put("watchForecast", decision.forecastMgdl?.let { JSONArray(it) } ?: JSONObject.NULL)
            entry.put("watchForecastAsRunning", decision.forecastAsRunningMgdl?.let { JSONArray(it) } ?: JSONObject.NULL)
            entry.put("runningPercent", store.delivery().percentAt(now))
            entry.put("phoneForecast", snapshot?.phoneForecast?.let { JSONArray(it) } ?: JSONObject.NULL)
            entry.put("readings", store.readings().size)
            entry.put("basalProfileKnown", pumpBasalUph().size == 24)
            snapshot?.let {
                entry.put(
                    "snapshot",
                    JSONObject().put("iob", it.iobU).put("cob", it.cobG).put("sensitivity", it.sensitivityMgdlPerU)
                        .put("target", it.targetMgdl).put("hypoThreshold", it.hypoThresholdMgdl)
                        .put("assumedTbrUph", it.assumedTbr?.rateUph ?: JSONObject.NULL)
                )
            }
        }

        entry.put("insulinReq", decision.insulinReqU ?: JSONObject.NULL).put("smb", decision.smbTenthsIU / 10.0)
        // The microbolus goes first: it is the part of the correction that cannot wait, and the
        // basal above profile follows in a session of its own.
        if (decision.smbTenthsIU > 0) {
            if (rehearsal || mode == AutonomyPolicy.Mode.OBSERVE) entry.put("smbDone", false)
            else {
                val result = runOwnSmb(decision.smbTenthsIU)
                val given = result?.bolus?.tenthsIU ?: 0
                val done = result?.outcome == Outcome.DONE && given > 0
                if (done) store.addSmb(app.aaps.pump.combowatch.regulation.BolusRecord(now, given / 10.0))
                entry.put("smbDone", done).put("smbOutcome", result?.outcome?.name ?: "NOT_SENT").put("smbReason", result?.reason ?: JSONObject.NULL)
                note(
                    "Часы без телефона: микродоза ${given / 10.0} ЕД" +
                        (if (done) "" else " — не выполнено (${result?.reason ?: "помпа не привязана"})") + ". ${decision.explanation}",
                    now
                )
            }
        }
        when (action) {
            WatchRegulator.Action.Leave     -> entry.put("action", "LEAVE").put("hold", decision.holdText ?: JSONObject.NULL)

            is WatchRegulator.Action.SetTbr -> {
                entry.put("action", "TBR ${action.percent} % ${action.durationMinutes} min")
                when {
                    rehearsal                            -> entry.put("done", false)

                    mode == AutonomyPolicy.Mode.OBSERVE -> {
                        entry.put("done", false)
                        // Said once per change of mind, not every five minutes.
                        val now30 = now - lastObservedAtEpochMs >= OBSERVED_REPEAT_MS
                        if (lastObserved != decision.rule to action.percent || now30) {
                            lastObserved = decision.rule to action.percent
                            lastObservedAtEpochMs = now
                            note("Часы без телефона (наблюдение, помпа не тронута): ${decision.explanation}", now)
                        }
                    }

                    else                                -> {
                        val result = runOwn(action)
                        val done = result?.outcome == Outcome.DONE
                        entry.put("done", done)
                            .put("outcome", result?.outcome?.name ?: "NOT_SENT")
                            .put("reason", result?.reason ?: JSONObject.NULL)
                        // Every change made is told to the phone. A failure that repeats at every
                        // reading - the pump out of reach - is told once, and again after a while.
                        val failure = if (done) null else (result?.reason ?: "помпа не привязана")
                        val failureKind = failure?.substringBefore(':')
                        if (done || failureKind != lastFailure || now - lastFailureNotedAtEpochMs >= FAILURE_REPEAT_MS) {
                            if (!done) lastFailureNotedAtEpochMs = now
                            note("Часы без телефона: ${decision.explanation}" + (failure?.let { " — не выполнено ($it)" } ?: ""), now)
                        }
                        lastFailure = failureKind
                    }
                }
            }
        }

        if (!rehearsal) {
            if (action == WatchRegulator.Action.Leave) lastObserved = null
            // Ticks without the sensor would say "no reading" every five minutes; once is enough.
            val repeatsNoGlucose = decision.rule == WatchRegulator.Rule.NO_GLUCOSE &&
                store.journal().lastOrNull()?.optString("rule") == WatchRegulator.Rule.NO_GLUCOSE.name
            if (!repeatsNoGlucose) store.addToJournal(entry)
            // What the face draws while the watch is alone: the forecast with what runs now left to
            // run its course, and insulin on board by the same model.
            (decision.forecastAsRunningMgdl ?: decision.forecastMgdl)?.let { series ->
                store.saveFaceForecast(
                    JSONObject().put("at", now).put("byWatch", true).put("series", JSONArray(series))
                        .put("iobU", decision.iobNowU ?: JSONObject.NULL)
                        .put("carbsToTargetG", decision.carbsToTargetG ?: JSONObject.NULL)
                        .put("carbsWithinMin", decision.carbsWithinMin ?: JSONObject.NULL)
                )
            }
            decision.carbsHintG?.let { grams ->
                if (now - lastCarbsHintAtEpochMs >= CARBS_HINT_REPEAT_MS) {
                    lastCarbsHintAtEpochMs = now
                    // Asked for while only observing too: the forecast is the same, and the pump
                    // has not even been slowed down.
                    askForCarbs(
                        grams,
                        decision.explanation + if (mode == AutonomyPolicy.Mode.OBSERVE) ". Часы в режиме наблюдения: базал они не остановили" else ""
                    )
                }
            }
        }
        return entry
    }

    private fun inputs(now: Long): WatchRegulator.Inputs {
        // The owner's word that insulin works worse today: the coefficients ask more insulin per
        // gram and per mg/dL, and the regulator widens its caps by the same factor.
        val factor = store.resistance()?.factor ?: 1.0
        val snapshot = store.snapshot()?.let { s ->
            if (factor > 1.0) s.copy(
                sensitivityMgdlPerU = s.sensitivityMgdlPerU / factor, carbRatioGPerU = s.carbRatioGPerU / factor,
                foodSensitivityMgdlPerU = s.foodSensitivityMgdlPerU?.let { it / factor }
            ) else s
        }
        return WatchRegulator.Inputs(
            nowEpochMs = now,
            readings = store.readings(),
            snapshot = snapshot,
            pumpBasalUph = pumpBasalUph(),
            delivery = store.delivery(),
            hourOfDay = hourOfDay,
            smbGivenLastHourU = store.smbs().filter { now - it.atEpochMs <= 60 * 60_000L }.sumOf { it.units },
            resistanceFactor = factor
        )
    }

    private fun readPumpIfStale() {
        val held = heldPump() ?: return
        // Never read at all means no session has happened yet; the first command will read it.
        val lastRead = lastPumpReadEpochMs() ?: return
        if (nowEpochMs() - lastRead < PUMP_READ_WHEN_ALONE_MS || executor.isBusy || pumpInOtherUse()) return
        val own = policy.ownRead("auto-read-${UUID.randomUUID()}", held)
        beforePumpSession()
        runCatching { executor.execute(own.command, own.lease) }.getOrNull()?.let(onOwnResult)
    }

    /** The dose for a meal the owner is about to eat, worked out here because the phone is away; see [WatchRegulator.adviseBolus]. */
    fun adviseBolus(carbsG: Int, maxTenthsIU: Int): WatchRegulator.BolusAdvice = regulator.adviseBolus(inputs(nowEpochMs()), carbsG, maxTenthsIU)

    /** A dose the owner typed in on the watch; see [WatchRegulator.adviseManualBolus]. */
    fun adviseManualBolus(tenthsIU: Int, maxTenthsIU: Int): WatchRegulator.BolusAdvice = regulator.adviseManualBolus(inputs(nowEpochMs()), tenthsIU, maxTenthsIU)

    /**
     * Give the bolus the owner confirmed on the watch. The pump's own receipt comes back as a
     * pump event, which the watch records and the phone files when it is in touch.
     */
    fun deliverOwnerBolus(tenthsIU: Int, maxBolusTenthsIU: Int?): ComboResult? {
        val held = heldPump() ?: return null
        val own = policy.ownerBolus("owner-bolus-${UUID.randomUUID()}", tenthsIU, held, maxBolusTenthsIU)
        beforePumpSession()
        ownCommandInFlight = true
        return try {
            executor.execute(own.command, own.lease).also(onOwnResult)
        } finally {
            ownCommandInFlight = false
        }
    }

    private fun runOwnSmb(tenthsIU: Int): ComboResult? {
        val held = heldPump() ?: return null
        val own = policy.ownMicroBolus("auto-smb-${UUID.randomUUID()}", tenthsIU, held, ownerBolusLimitTenths())
        beforePumpSession()
        ownCommandInFlight = true
        return try {
            executor.execute(own.command, own.lease).also(onOwnResult)
        } finally {
            ownCommandInFlight = false
        }
    }

    private fun runOwn(action: WatchRegulator.Action.SetTbr): ComboResult? {
        val held = heldPump() ?: return null
        val own = policy.ownTemporaryBasal("auto-${UUID.randomUUID()}", action.percent, action.durationMinutes, held)
        beforePumpSession()
        ownCommandInFlight = true
        return try {
            executor.execute(own.command, own.lease).also(onOwnResult)
        } finally {
            ownCommandInFlight = false
        }
    }

    companion object {

        private const val OBSERVED_REPEAT_MS = 30 * 60_000L

        /** Alone, the pump is read at least this often, for boluses given on it. */
        private const val PUMP_READ_WHEN_ALONE_MS = 30 * 60_000L
        private const val FAILURE_REPEAT_MS = 30 * 60_000L
        private const val CARBS_HINT_REPEAT_MS = 20 * 60_000L
    }
}
