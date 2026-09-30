package app.aaps.pump.combowatch

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
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
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.combowatch.protocol.CommandKind
import app.aaps.pump.combowatch.protocol.ComboResult
import app.aaps.pump.combowatch.protocol.Outcome
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
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Drives an Accu-Chek Combo that is paired to the watch rather than to this phone.
 *
 * This plugin exists next to the direct driver, never in place of it. Which of the two is active
 * is AAPS's own single-choice among [PluginType.PUMP] plugins, so returning to driving the pump
 * from the phone is a plugin switch and needs no change here — and nothing in this file is
 * reachable while the direct driver is the active one.
 *
 * What this plugin can express is deliberately smaller than [Pump]: the protocol to the watch
 * carries reads and the temporary-basal pair only. Boluses are refused here rather than
 * half-implemented, because a bolus whose outcome is unclear cannot be made safe by the phone.
 */
@Singleton
class ComboWatchPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    preferences: Preferences,
    commandQueue: CommandQueue,
    private val link: ComboWatchLink,
    private val pumpSync: PumpSync,
    private val dateUtil: DateUtil,
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

    private var scopeJob = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.Default + scopeJob)
    private var leaseJob: Job? = null

    private val _pumpDescription = PumpDescription().also { it.fillFor(PumpType.ACCU_CHEK_COMBO) }

    /** Serial of the pump the watch holds. Read back from the watch, never assumed by the phone. */
    private val pumpSerial: String get() = link.lastHeartbeat?.snapshot?.pumpSerial ?: UNKNOWN_SERIAL

    override fun onStart() {
        super.onStart()
        scopeJob = SupervisorJob()
        scope = CoroutineScope(Dispatchers.Default + scopeJob)
        // Renew well inside the lease's life so a single missed message does not stand the watch
        // down, while a phone that stops running lets it lapse within one lease length.
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
        // Tell a watch that is in contact to stand down now rather than at lease expiry. If the
        // message does not get through, the lapsing lease does the same thing a minute later.
        runCatching { runBlocking { link.revokeLease(pumpSerial) } }
        scope.cancel()
        super.onStop()
    }

    // ---- what the watch reports -------------------------------------------------------------

    override fun isInitialized(): Boolean = link.lastHeartbeat != null

    override fun isConnected(): Boolean = link.watchFresh(HEARTBEAT_FRESH_MS) &&
        link.lastHeartbeat?.pumpReachable == true

    override fun isConnecting(): Boolean = false
    override fun isHandshakeInProgress(): Boolean = false
    override fun isBusy(): Boolean = link.lastHeartbeat?.executorBusy == true

    override fun isSuspended(): Boolean =
        link.lastHeartbeat?.snapshot?.let { it.tbrRunning && it.tbrPercentage == 0 } == true

    override val lastDataTime: Long get() = link.lastHeartbeat?.atEpochMs ?: 0L

    override val baseBasalRate: Double get() = 0.0
    override val reservoirLevel: Double get() = link.lastHeartbeat?.snapshot?.reservoirUnits?.toDouble() ?: 0.0
    override val batteryLevel: Int? get() = null
    override val lastBolusTime: Long? get() = null
    override val lastBolusAmount: Double? get() = null

    override fun connect(reason: String) { /* the watch owns the link; nothing for the phone to open */ }
    override fun disconnect(reason: String) { /* same */ }
    override fun stopConnecting() { /* same */ }

    override fun getPumpStatus(reason: String) {
        val result = dispatch(CommandKind.STATUS)
        result.snapshot?.let { aapsLogger.debug(LTag.PUMP, "combowatch: status tbr=${it.tbrPercentage} res=${it.reservoirUnits}") }
    }

    // ---- the commands this plugin can actually express ---------------------------------------

    override fun setTempBasalPercent(
        percent: Int, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult {
        val result = dispatch(CommandKind.SET_TBR, percentage = percent, durationMinutes = durationInMinutes)
        return toEnactResult(result, percent, durationInMinutes, tbrType)
    }

    override fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult {
        val result = dispatch(CommandKind.CANCEL_TBR)
        return toEnactResult(result, percent = 100, durationInMinutes = 0, tbrType = PumpSync.TemporaryBasalType.NORMAL)
    }

    override fun setTempBasalAbsolute(
        absoluteRate: Double, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult {
        val baseRate = profile.getBasal()
        if (baseRate <= 0.0) return refuse(R.string.combowatch_not_supported)
        val percent = ((absoluteRate / baseRate) * 100).toInt()
        return setTempBasalPercent(percent, durationInMinutes, profile, enforceNew, tbrType)
    }

    // ---- refused rather than approximated ----------------------------------------------------

    override fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult = refuse(R.string.combowatch_not_supported)
    override fun stopBolusDelivering() { /* no bolus can be running, since none can be started */ }
    override fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult = refuse(R.string.combowatch_not_supported)
    override fun cancelExtendedBolus(): PumpEnactResult = refuse(R.string.combowatch_not_supported)
    override fun setNewBasalProfile(profile: Profile): PumpEnactResult = refuse(R.string.combowatch_not_supported)
    override fun isThisProfileSet(profile: Profile): Boolean = true
    override fun loadTDDs(): PumpEnactResult = refuse(R.string.combowatch_not_supported)

    // ---- identity -----------------------------------------------------------------------------

    override fun manufacturer(): ManufacturerType = ManufacturerType.Roche
    override fun model(): PumpType = PumpType.ACCU_CHEK_COMBO
    override fun serialNumber(): String = pumpSerial
    override val pumpDescription: PumpDescription get() = _pumpDescription
    override val isFakingTempsByExtendedBoluses: Boolean = false
    override fun canHandleDST(): Boolean = false

    override fun updateExtendedJsonStatus(extendedStatus: JSONObject) {
        val heartbeat = link.lastHeartbeat ?: return
        extendedStatus.put("ComboWatchLeaseLive", heartbeat.leaseLive)
        extendedStatus.put("ComboWatchPumpReachable", heartbeat.pumpReachable)
        heartbeat.watchBatteryPercent?.let { extendedStatus.put("ComboWatchBattery", it) }
    }

    /** Shown wherever AAPS shows a pump's own status, so the active mode is visible at a glance. */
    override fun pumpSpecificShortStatus(veryShort: Boolean): String {
        val heartbeat = link.lastHeartbeat ?: return rh.gs(R.string.combowatch_watch_unreachable)
        if (!link.watchFresh(HEARTBEAT_FRESH_MS)) return rh.gs(R.string.combowatch_watch_unreachable)
        if (heartbeat.awaitingReconciliation) return rh.gs(R.string.combowatch_awaiting_reconciliation)
        val tbr = heartbeat.snapshot
            ?.takeIf { it.tbrRunning }
            ?.let { " TBR ${it.tbrPercentage}% ${it.tbrRemainingMinutes}мин" }
            ?: ""
        val battery = heartbeat.watchBatteryPercent?.let { " ⌚$it%" } ?: ""
        return "через часы$tbr$battery"
    }

    // ---- plumbing ------------------------------------------------------------------------------

    private fun dispatch(kind: CommandKind, percentage: Int? = null, durationMinutes: Int? = null): ComboResult =
        runBlocking {
            link.execute(
                kind = kind,
                percentage = percentage,
                durationMinutes = durationMinutes,
                validForMs = COMMAND_VALID_MS,
                timeoutMs = COMMAND_TIMEOUT_MS
            )
        }

    /**
     * Turn the watch's answer into AAPS's own.
     *
     * [Outcome.UNKNOWN] is reported as *not* enacted. That is the safe direction for a stop —
     * AAPS keeps believing delivery continues — and the watch is meanwhile reading the pump back,
     * so the next heartbeat corrects the record either way.
     */
    private fun toEnactResult(
        result: ComboResult, percent: Int, durationInMinutes: Int, tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult {
        val enact = pumpEnactResultProvider.get()
        when (result.outcome) {
            Outcome.DONE    -> {
                pumpSync.syncTemporaryBasalWithPumpId(
                    timestamp = result.completedAtEpochMs,
                    rate = percent.toDouble(),
                    duration = durationInMinutes * 60L * 1000L,
                    isAbsolute = false,
                    type = tbrType,
                    pumpId = result.completedAtEpochMs,
                    pumpType = PumpType.ACCU_CHEK_COMBO,
                    pumpSerial = pumpSerial
                )
                enact.success = true
                enact.enacted = true
                enact.percent = percent
                enact.duration = durationInMinutes
                enact.isPercent = true
            }

            Outcome.REFUSED,
            Outcome.FAILED,
            Outcome.UNKNOWN -> {
                enact.success = false
                enact.enacted = false
                enact.comment = result.reason ?: result.outcome.name
                aapsLogger.warn(LTag.PUMP, "combowatch: ${result.outcome} ${result.reason ?: ""}")
            }
        }
        return enact
    }

    private fun refuse(comment: Int): PumpEnactResult = pumpEnactResultProvider.get().apply {
        success = false
        enacted = false
        this.comment = rh.gs(comment)
    }

    companion object {

        private const val UNKNOWN_SERIAL = "неизвестна"

        // A lease outlives several renewals, so one lost message changes nothing, but a phone
        // that stops renewing stands the watch down within this long.
        private const val LEASE_VALID_MS = 5 * 60_000L
        private const val LEASE_RENEW_INTERVAL_MS = 60_000L

        private const val HEARTBEAT_FRESH_MS = 5 * 60_000L

        // Measured on the bench: a session takes 45-70 s, and the watch's alarms were seen
        // arriving up to 224 s late. A command older than this is dropped by the watch instead
        // of applied, because a stop that lands that late is a dosing error, not a slow success.
        private const val COMMAND_VALID_MS = 4 * 60_000L
        private const val COMMAND_TIMEOUT_MS = 6 * 60_000L
    }
}
