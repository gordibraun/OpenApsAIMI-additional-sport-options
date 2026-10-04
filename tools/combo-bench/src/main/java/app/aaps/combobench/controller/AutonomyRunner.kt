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

        val now = nowEpochMs()
        val snapshot = store.snapshot()
        val decision = regulator.decide(
            WatchRegulator.Inputs(
                nowEpochMs = now,
                readings = store.readings(),
                snapshot = snapshot,
                pumpBasalUph = pumpBasalUph(),
                delivery = store.delivery(),
                hourOfDay = hourOfDay
            )
        )
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
            store.addToJournal(entry)
            // What the face draws while the watch is alone: the forecast with what runs now left to
            // run its course, and insulin on board by the same model.
            (decision.forecastAsRunningMgdl ?: decision.forecastMgdl)?.let { series ->
                store.saveFaceForecast(
                    JSONObject().put("at", now).put("byWatch", true).put("series", JSONArray(series))
                        .put("iobU", decision.iobNowU ?: JSONObject.NULL)
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

    private fun runOwn(action: WatchRegulator.Action.SetTbr): ComboResult? {
        val held = heldPump() ?: return null
        val own = policy.ownTemporaryBasal("auto-${UUID.randomUUID()}", action.percent, action.durationMinutes, held)
        beforePumpSession()
        ownCommandInFlight = true
        return try {
            executor.execute(own.command, own.lease)
        } finally {
            ownCommandInFlight = false
        }
    }

    companion object {

        private const val OBSERVED_REPEAT_MS = 30 * 60_000L
        private const val FAILURE_REPEAT_MS = 30 * 60_000L
        private const val CARBS_HINT_REPEAT_MS = 20 * 60_000L
    }
}
