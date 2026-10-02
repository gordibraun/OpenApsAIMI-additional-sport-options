package app.aaps.pump.combowatch

import app.aaps.core.data.model.BS
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpPluginBase
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.pump.combowatch.protocol.BolusKind
import app.aaps.pump.combowatch.protocol.ComboResult
import app.aaps.pump.combowatch.protocol.CommandKind
import app.aaps.pump.combowatch.protocol.Outcome
import app.aaps.pump.combowatch.protocol.PumpEvent
import app.aaps.pump.combowatch.protocol.PumpSnapshot
import app.aaps.pump.combowatch.protocol.TbrKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Drives an Accu-Chek Combo that is paired to the watch rather than to this phone.
 *
 * This plugin exists next to the direct driver, never in place of it. Which of the two is active
 * is AAPS's own single choice among [PluginType.PUMP] plugins, so returning to driving the pump
 * from the phone is a plugin switch and needs no change here - and nothing in this file is
 * reachable while the direct driver is the active one.
 *
 * The phone decides everything and touches nothing: each call below becomes one command to the
 * watch, which runs it with the same driver the direct mode uses and reports what the pump did.
 * The phone's treatment records are written from the driver events the watch forwards, with the
 * same mapping the direct driver applies, so insulin on board is computed from what the pump
 * actually delivered whether or not the command's own answer made it back.
 */
@Singleton
class ComboWatchPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    preferences: Preferences,
    commandQueue: CommandQueue,
    private val link: ComboWatchLink,
    private val pumpSync: PumpSync,
    private val constraintChecker: ConstraintsChecker,
    private val uiInteraction: UiInteraction,
    private val pumpEnactResultProvider: Provider<PumpEnactResult>
) : PumpPluginBase(
    pluginDescription = PluginDescription()
        .mainType(PluginType.PUMP)
        .pluginName(R.string.combowatch_plugin_name)
        .shortName(R.string.combowatch_plugin_shortname)
        .description(R.string.combowatch_plugin_description),
    ownPreferences = emptyList(),
    aapsLogger, rh, preferences, commandQueue
), Pump {

    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var leaseJob: Job? = null

    private val _pumpDescription = PumpDescription().also { it.fillFor(PumpType.ACCU_CHEK_COMBO) }

    /** The pump AAPS files its records under, or null while it has none registered. */
    private val registeredPump: String?
        get() = preferences.get(StringNonKey.ActivePumpSerialNumber).takeUnless { it.isEmpty() || it == UNKNOWN_SERIAL }

    /**
     * The pump every decision of this phone is about, and the only one the watch may act on.
     *
     * Once AAPS has a pump registered, that is the one: its records are what insulin on board is
     * computed from, and AAPS accepts further records from that pump alone. Until then - right
     * after this driver has been selected, which clears the registration - it is the pump the
     * watch holds, and the first record from it registers it.
     *
     * The watch is told this name in every lease and refuses to change delivery on any other
     * pump, so pairing the watch with a different pump can never make this phone's decisions land
     * on it unnoticed. Moving to the other pump is a deliberate act on the phone: selecting the
     * driver again.
     */
    private val boundPump: String? get() = registeredPump ?: link.watchPump

    /** Set when the watch holds a pump other than the one AAPS is bound to. */
    private val otherPumpOnWatch: String?
        get() = link.watchPump?.takeIf { held -> registeredPump?.let { it != held } == true }

    /** The last thing the watch read off the bound pump, from an answer or a heartbeat. */
    private val snapshot: PumpSnapshot? get() = link.lastSnapshot?.takeIf { it.pumpSerial == boundPump }

    /** What the lease names: the bound pump, or a placeholder that matches no pump. */
    private val pumpSerial: String get() = boundPump ?: UNKNOWN_SERIAL

    @Volatile private var reportedOtherPump: String? = null

    @Volatile private var lastBolus: Pair<Long, Double>? = null

    override fun onStart() {
        super.onStart()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        link.eventHandler = ::handlePumpEvent
        // Renewed well inside the lease's life so that a lost message changes nothing, while a
        // phone that stops running lets the lease lapse and the watch stand down by itself.
        leaseJob = scope.launch {
            while (isActive) {
                runCatching { link.renewLease(pumpSerial, LEASE_VALID_MS) }
                    .onFailure { aapsLogger.debug(LTag.PUMP, "combowatch: lease renewal failed: ${it.message}") }
                delay(LEASE_RENEW_INTERVAL_MS)
            }
        }
    }

    override fun onStop() {
        leaseJob = null
        link.eventHandler = null
        // Tell a watch that is in contact to stand down now rather than at lease expiry. If the
        // message does not get through, the lapsing lease does the same thing a little later.
        runCatching { runBlocking { link.revokeLease(pumpSerial) } }
        scope.cancel()
        super.onStop()
    }

    // ---- state, as far as the phone can know it -------------------------------------------------

    override fun isInitialized(): Boolean = snapshot != null

    // The link to the pump belongs to the watch; there is nothing for the phone to open or wait
    // for, and a command sent while the watch is away is answered as such.
    override fun isConnected(): Boolean = true
    override fun isConnecting(): Boolean = false
    override fun isHandshakeInProgress(): Boolean = false
    override fun isBusy(): Boolean = false
    override fun isSuspended(): Boolean = false
    override fun connect(reason: String) = Unit
    override fun disconnect(reason: String) = Unit
    override fun stopConnecting() = Unit
    override fun waitForDisconnectionInSeconds(): Int = 0

    override val lastDataTime: Long get() = link.lastContactEpochMs
    override val reservoirLevel: Double get() = snapshot?.reservoirUnits?.toDouble() ?: 0.0
    override val batteryLevel: Int?
        get() = when (snapshot?.batteryState) {
            "NO_BATTERY"   -> 5
            "LOW_BATTERY"  -> 25
            "FULL_BATTERY" -> 100
            else           -> null
        }
    override val lastBolusTime: Long? get() = lastBolus?.first
    override val lastBolusAmount: Double? get() = lastBolus?.second

    /** What the pump delivers as 100 % right now, from the profile the watch read off the pump. */
    override val baseBasalRate: Double
        get() = snapshot?.basalProfileFactors
            ?.getOrNull(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
            ?.let { it / 1000.0 } ?: 0.0

    override fun getPumpStatus(reason: String) {
        dispatch(CommandKind.STATUS)
        adoptWatchPumpIfNoneRegistered()
        // Raises the notice about a different pump on the watch as soon as it is known.
        whyNotThisPump()
    }

    // ---- temporary basal ---------------------------------------------------------------------

    override fun setTempBasalPercent(
        percent: Int, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult {
        val enact = pumpEnactResultProvider.get().also { it.isPercent = true }
        val rounded = ((percent + 5) / 10) * 10
        val limited = min(rounded, _pumpDescription.maxTempPercent)
        val kind = when (tbrType) {
            PumpSync.TemporaryBasalType.NORMAL                -> TbrKind.NORMAL
            PumpSync.TemporaryBasalType.EMULATED_PUMP_SUSPEND -> TbrKind.EMULATED_STOP
            PumpSync.TemporaryBasalType.SUPERBOLUS            -> TbrKind.SUPERBOLUS
            PumpSync.TemporaryBasalType.PUMP_SUSPEND          ->
                return enact.also { it.success = false; it.enacted = false; it.comment = rh.gs(app.aaps.core.ui.R.string.error) }
        }
        // Asking for 100 % is how AAPS ends a temporary basal; the driver has its own way of
        // doing that, and the watch must take the same path the direct driver would.
        val result =
            if (limited == 100) dispatch(CommandKind.CANCEL_TBR, force100Percent = false)
            else dispatch(CommandKind.SET_TBR, percentage = limited, durationMinutes = durationInMinutes, tbrKind = kind)
        return tbrResult(result, enact)
    }

    override fun setTempBasalAbsolute(
        absoluteRate: Double, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult {
        val base = baseBasalRate
        if (base == 0.0)
            return pumpEnactResultProvider.get().also {
                it.success = false; it.enacted = false
                it.comment = rh.gs(R.string.combowatch_cannot_set_absolute_tbr_if_basal_zero)
            }
        // The Combo only takes percentages, in steps of ten.
        val percent = (absoluteRate / base * 10).roundToInt() * 10
        return setTempBasalPercent(percent, durationInMinutes, profile, enforceNew, tbrType).also { it.isPercent = false }
    }

    override fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult {
        val enact = pumpEnactResultProvider.get().also { it.isPercent = true; it.isTempCancel = enforceNew }
        return tbrResult(dispatch(CommandKind.CANCEL_TBR, force100Percent = enforceNew), enact)
    }

    private fun tbrResult(result: ComboResult, enact: PumpEnactResult): PumpEnactResult {
        when (result.outcome) {
            Outcome.DONE    -> {
                enact.success = true
                // The driver deliberately leaves a short 90-110 % TBR running instead of
                // cancelling; that is a success in which nothing was enacted.
                enact.enacted = result.tbrOutcome != "LETTING_EMULATED_100_TBR_FINISH" && result.tbrOutcome != "IGNORED_REDUNDANT_100_TBR"
                enact.percent = result.tbrPercentage ?: 100
                enact.duration = result.tbrDurationMinutes ?: 0
                enact.comment = result.tbrOutcome ?: ""
            }

            Outcome.REFUSED,
            Outcome.FAILED  -> {
                enact.success = false
                enact.enacted = false
                enact.comment = result.reason ?: result.outcome.name
            }

            // Reported as not enacted. AAPS then keeps believing what its records say, and the
            // records are corrected from the pump's own events once the watch has read it back.
            Outcome.UNKNOWN -> {
                enact.success = false
                enact.enacted = false
                enact.comment = rh.gs(R.string.combowatch_outcome_unknown)
            }
        }
        if (result.outcome != Outcome.DONE)
            aapsLogger.warn(LTag.PUMP, "combowatch: TBR ${result.outcome} ${result.reason ?: ""}")
        return enact
    }

    // ---- bolus -------------------------------------------------------------------------------

    override fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        require(detailedBolusInfo.carbs == 0.0) { detailedBolusInfo.toString() }
        require(detailedBolusInfo.insulin > 0) { detailedBolusInfo.toString() }

        detailedBolusInfo.insulin = constraintChecker
            .applyBolusConstraints(ConstraintObject(detailedBolusInfo.insulin, aapsLogger))
            .value()

        val requestedTenths = (detailedBolusInfo.insulin * 10.0).toInt()
        val kind = when (detailedBolusInfo.bolusType) {
            BS.Type.NORMAL  -> BolusKind.NORMAL
            BS.Type.SMB     -> BolusKind.SMB
            BS.Type.PRIMING -> BolusKind.PRIMING
        }
        val enact = pumpEnactResultProvider.get()
        if (requestedTenths < 1)
            return enact.also { it.success = false; it.enacted = false; it.bolusDelivered = 0.0 }

        // A bolus that arrives late is a dosing error, so it is given less time than a TBR before
        // the watch must drop it instead of delivering it.
        val result = dispatch(
            CommandKind.DELIVER_BOLUS, bolusTenthsIU = requestedTenths, bolusKind = kind,
            validForMs = BOLUS_VALID_MS
        )
        val delivered = result.bolus
        if (delivered != null) {
            detailedBolusInfo.bolusTimestamp = delivered.timestampEpochMs
            lastBolus = delivered.timestampEpochMs to delivered.tenthsIU / 10.0
        }
        when (result.outcome) {
            Outcome.DONE    -> {
                enact.success = true
                enact.enacted = (delivered?.tenthsIU ?: 0) > 0
                enact.bolusDelivered = (delivered?.tenthsIU ?: 0) / 10.0
                enact.comment = rh.gs(R.string.combowatch_bolus_delivered, enact.bolusDelivered)
            }

            Outcome.FAILED  -> {
                enact.success = false
                enact.enacted = (delivered?.tenthsIU ?: 0) > 0
                enact.bolusDelivered = (delivered?.tenthsIU ?: 0) / 10.0
                enact.comment =
                    if (enact.enacted) rh.gs(R.string.combowatch_bolus_partial, enact.bolusDelivered, requestedTenths / 10.0)
                    else result.reason ?: result.outcome.name
            }

            Outcome.REFUSED -> {
                enact.success = false
                enact.enacted = false
                enact.bolusDelivered = 0.0
                enact.comment = result.reason ?: result.outcome.name
            }

            // Never treated as "not delivered, try again". The watch settles it from the pump's
            // history, and the bolus - if there was one - reaches the records as a pump event.
            Outcome.UNKNOWN -> {
                enact.success = false
                enact.enacted = false
                enact.bolusDelivered = 0.0
                enact.comment = rh.gs(R.string.combowatch_outcome_unknown)
            }
        }
        aapsLogger.debug(LTag.PUMP, "combowatch: bolus ${result.outcome} delivered=${enact.bolusDelivered} ${result.reason ?: ""}")
        return enact
    }

    // Once the watch has started a bolus it runs to its end; there is no channel to interrupt it.
    override fun stopBolusDelivering() = Unit

    // ---- not carried by this driver ------------------------------------------------------------

    override fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult = refuse()
    override fun cancelExtendedBolus(): PumpEnactResult = refuse()
    override fun loadTDDs(): PumpEnactResult = refuse()

    /**
     * Writing a basal profile is a walk through 24 setting screens, far beyond what the watch's
     * link to the pump carries reliably, so it is refused here and done in direct mode.
     */
    override fun setNewBasalProfile(profile: Profile): PumpEnactResult = refuse()

    /**
     * Compared against the profile the watch read off the pump, because every TBR percentage is
     * relative to what the pump itself delivers as 100 %. Until the watch has reported one there
     * is nothing to compare with, and AAPS is not sent into a profile write it cannot perform.
     */
    override fun isThisProfileSet(profile: Profile): Boolean {
        val onPump = snapshot?.basalProfileFactors ?: return true
        return (0 until 24).all { hour ->
            comboBasalFactor((profile.getBasalTimeFromMidnight(hour * 60 * 60) * 1000.0).toInt()) == onPump.getOrNull(hour)
        }
    }

    // ---- identity ----------------------------------------------------------------------------

    override fun manufacturer(): ManufacturerType = ManufacturerType.Roche
    override fun model(): PumpType = PumpType.ACCU_CHEK_COMBO
    override fun serialNumber(): String = pumpSerial
    override val pumpDescription: PumpDescription get() = _pumpDescription
    override val isFakingTempsByExtendedBoluses: Boolean = false
    override fun canHandleDST(): Boolean = true

    override fun updateExtendedJsonStatus(extendedStatus: JSONObject) {
        val heartbeat = link.lastHeartbeat ?: return
        extendedStatus.put("ComboWatchLeaseLive", heartbeat.leaseLive)
        extendedStatus.put("ComboWatchPumpReachable", heartbeat.pumpReachable)
        heartbeat.watchBatteryPercent?.let { extendedStatus.put("ComboWatchBattery", it) }
    }

    /** Shown wherever AAPS shows a pump's own status, so the active mode is visible at a glance. */
    override fun pumpSpecificShortStatus(veryShort: Boolean): String {
        val heartbeat = link.lastHeartbeat ?: return rh.gs(R.string.combowatch_watch_unreachable)
        if (System.currentTimeMillis() - link.lastContactEpochMs > WATCH_STALE_MS) return rh.gs(R.string.combowatch_watch_unreachable)
        if (link.watchPumpKnown && link.watchPump == null) return rh.gs(R.string.combowatch_no_pump_on_watch)
        otherPumpOnWatch?.let { return rh.gs(R.string.combowatch_other_pump_short, it) }
        if (heartbeat.awaitingReconciliation) return rh.gs(R.string.combowatch_awaiting_reconciliation)
        val tbr = heartbeat.snapshot
            ?.takeIf { it.tbrRunning }
            ?.let { " TBR ${it.tbrPercentage}% ${it.tbrRemainingMinutes}мин" }
            ?: ""
        val battery = heartbeat.watchBatteryPercent?.let { " ⌚$it%" } ?: ""
        return "через часы$tbr$battery"
    }

    // ---- what the watch saw on the pump ------------------------------------------------------

    /**
     * The same mapping the direct driver applies to its own events. Every record is keyed on an
     * id from the pump, so an event that arrives twice changes nothing.
     */
    internal fun handlePumpEvent(event: PumpEvent) {
        aapsLogger.debug(LTag.PUMP, "combowatch: pump event $event")
        // Until it is known which pump this phone is bound to, nothing can be filed. Throwing
        // leaves the event unacknowledged, so the watch keeps it and sends it again.
        val bound = checkNotNull(boundPump) { "pump event ${event.seq} arrived before the watch said which pump it holds" }
        // A record is filed only under the pump it was observed on, and only when that is the
        // bound pump. Handing AAPS a record of any other pump would be worse than useless: with
        // no pump registered yet it would register that one, and every record of the right pump
        // would then be turned away. Such an event is passed over, and the owner is told.
        if (event.pumpSerial != bound) {
            reportEventOfOtherPump(event)
            return
        }
        val serial: String = bound
        when (event.type) {
            PumpEvent.Type.BOLUS_INFUSED        -> {
                val amount = checkNotNull(event.bolusTenthsIU) / 10.0
                pumpSync.syncBolusWithPumpId(
                    event.timestampEpochMs,
                    amount,
                    when (event.bolusKind) {
                        BolusKind.SMB     -> BS.Type.SMB
                        BolusKind.PRIMING -> BS.Type.PRIMING
                        else              -> BS.Type.NORMAL
                    },
                    checkNotNull(event.bolusId),
                    PumpType.ACCU_CHEK_COMBO,
                    serial
                )
                lastBolus = event.timestampEpochMs to amount
            }

            PumpEvent.Type.TBR_STARTED          -> pumpSync.syncTemporaryBasalWithPumpId(
                timestamp = event.timestampEpochMs,
                rate = checkNotNull(event.tbrPercentage).toDouble(),
                duration = checkNotNull(event.tbrDurationMinutes).toLong() * 60 * 1000,
                isAbsolute = false,
                type = when (event.tbrType) {
                    "superbolus"        -> PumpSync.TemporaryBasalType.SUPERBOLUS
                    "emulatedComboStop" -> PumpSync.TemporaryBasalType.EMULATED_PUMP_SUSPEND
                    "comboStopped"      -> PumpSync.TemporaryBasalType.PUMP_SUSPEND
                    else                -> PumpSync.TemporaryBasalType.NORMAL
                },
                pumpId = event.timestampEpochMs,
                pumpType = PumpType.ACCU_CHEK_COMBO,
                pumpSerial = serial
            )

            PumpEvent.Type.TBR_ENDED            -> pumpSync.syncStopTemporaryBasalWithPumpId(
                timestamp = event.timestampEpochMs,
                endPumpId = event.timestampEpochMs,
                pumpType = PumpType.ACCU_CHEK_COMBO,
                pumpSerial = serial
            )

            PumpEvent.Type.UNKNOWN_TBR_DETECTED -> uiInteraction.addNotification(
                Notification.COMBO_UNKNOWN_TBR,
                text = rh.gs(R.string.combowatch_unknown_tbr_detected, event.tbrPercentage ?: 0, event.tbrDurationMinutes ?: 0),
                level = Notification.URGENT
            )

            PumpEvent.Type.BATTERY_LOW          -> uiInteraction.addNotification(
                Notification.COMBO_PUMP_ALARM, text = rh.gs(R.string.combowatch_battery_low), level = Notification.NORMAL
            )

            PumpEvent.Type.RESERVOIR_LOW        -> uiInteraction.addNotification(
                Notification.COMBO_PUMP_ALARM, text = rh.gs(R.string.combowatch_reservoir_low), level = Notification.NORMAL
            )
        }
    }

    /**
     * With no pump registered - this driver has just been selected - the pump the watch holds is
     * made the one AAPS keeps records for, before anything is asked of it.
     *
     * Left alone, AAPS registers the pump from the first record it is handed and discards that
     * very record if it is more than a minute old - which the record of a TBR that took a while
     * to set is, and then AAPS would not know about a TBR the pump is running. Registered
     * beforehand, every record made afterwards is accepted.
     *
     * AAPS has no call that only registers a pump. Reporting "no temporary basal is running as
     * of now" does, and changes nothing else: selecting a driver has already closed whatever the
     * records said was running.
     */
    private fun adoptWatchPumpIfNoneRegistered() {
        if (registeredPump != null) return
        val held = link.watchPump ?: return
        val now = System.currentTimeMillis()
        pumpSync.syncStopTemporaryBasalWithPumpId(timestamp = now, endPumpId = now, pumpType = PumpType.ACCU_CHEK_COMBO, pumpSerial = held)
        aapsLogger.info(LTag.PUMP, "combowatch: $held is now the pump AAPS keeps records for")
    }

    private fun reportEventOfOtherPump(event: PumpEvent) {
        aapsLogger.warn(LTag.PUMP, "combowatch: event ${event.seq} is from ${event.pumpSerial ?: "an unnamed pump"}, not from $boundPump; not recorded")
        // Only insulin is worth interrupting the owner for.
        if (event.type == PumpEvent.Type.BOLUS_INFUSED)
            uiInteraction.addNotification(
                Notification.WRONG_PUMP_DATA,
                text = rh.gs(
                    R.string.combowatch_bolus_of_other_pump,
                    (event.bolusTenthsIU ?: 0) / 10.0, event.pumpSerial ?: "?", boundPump ?: "?"
                ),
                level = Notification.NORMAL
            )
    }

    /**
     * Why a command that changes delivery cannot be sent right now, in the owner's language, or
     * null when it can. The watch enforces the same thing on its side from the name in the
     * lease; checking here as well gives a refusal that says what to do about it.
     */
    private fun whyNotThisPump(): String? {
        if (link.watchPumpKnown && link.watchPump == null) return rh.gs(R.string.combowatch_no_pump_on_watch)
        val other = otherPumpOnWatch ?: run { reportedOtherPump = null; return null }
        val text = rh.gs(R.string.combowatch_other_pump, other, registeredPump ?: "?")
        if (reportedOtherPump != other) {
            reportedOtherPump = other
            uiInteraction.addNotification(Notification.WRONG_PUMP_DATA, text = text, level = Notification.URGENT)
        }
        return text
    }

    // ---- plumbing ----------------------------------------------------------------------------

    private fun dispatch(
        kind: CommandKind,
        percentage: Int? = null,
        durationMinutes: Int? = null,
        tbrKind: TbrKind? = null,
        force100Percent: Boolean? = null,
        bolusTenthsIU: Int? = null,
        bolusKind: BolusKind? = null,
        validForMs: Long = COMMAND_VALID_MS
    ): ComboResult = runBlocking {
        // Reading the pump is always allowed: it is how the phone finds out which pump is there.
        if (kind != CommandKind.STATUS) {
            whyNotThisPump()?.let {
                return@runBlocking ComboResult("not-sent", Outcome.REFUSED, System.currentTimeMillis(), reason = it)
            }
            // Before the command, so that the record of what it does is not the one AAPS
            // spends on registering the pump.
            adoptWatchPumpIfNoneRegistered()
        }
        link.execute(
            kind = kind,
            pumpSerial = pumpSerial,
            leaseValidForMs = LEASE_VALID_MS,
            validForMs = validForMs,
            timeoutMs = COMMAND_TIMEOUT_MS,
            percentage = percentage,
            durationMinutes = durationMinutes,
            tbrKind = tbrKind,
            force100Percent = force100Percent,
            bolusTenthsIU = bolusTenthsIU,
            bolusKind = bolusKind
        )
    }

    private fun refuse(): PumpEnactResult = pumpEnactResultProvider.get().apply {
        success = false
        enacted = false
        comment = rh.gs(R.string.combowatch_not_supported)
    }

    companion object {

        /**
         * A basal rate as the Combo can hold it, in 0.001 U/h: in steps of 0.01 U/h up to 1 U/h,
         * of 0.05 up to 10 U/h and of 0.1 above. It is the rounding the driver applies whenever
         * it writes or compares a profile (comboctl's BasalProfile), repeated here because this
         * module does not depend on the driver. Without it a profile asking for 1.23 U/h never
         * equals the 1.25 the pump holds for it, and AAPS keeps asking for a profile write.
         */
        internal fun comboBasalFactor(factor: Int): Int {
            val granularity = when (factor) {
                in 0..50       -> 50
                in 50..1000    -> 10
                in 1000..10000 -> 50
                else           -> 100
            }
            return ((factor + granularity / 2) / granularity) * granularity
        }

        private const val UNKNOWN_SERIAL = "неизвестна"

        // The lease outlives several renewals, so one lost message changes nothing, but a phone
        // that stops renewing stands the watch down within this long.
        private const val LEASE_VALID_MS = 15 * 60_000L
        private const val LEASE_RENEW_INTERVAL_MS = 5 * 60_000L

        private const val WATCH_STALE_MS = 20 * 60_000L

        // A command older than this is dropped by the watch instead of applied: a change that
        // lands this late is a dosing error, not a slow success. A session on the watch takes
        // about a minute, a staged TBR up to a few.
        private const val COMMAND_VALID_MS = 4 * 60_000L
        private const val BOLUS_VALID_MS = 2 * 60_000L
        private const val COMMAND_TIMEOUT_MS = 12 * 60_000L
    }
}
