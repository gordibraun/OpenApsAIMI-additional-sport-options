package app.aaps.combobench

import info.nightscout.comboctl.base.*
import kotlinx.datetime.UtcOffset
import org.json.JSONObject
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Pump state for a full ComboCtl [info.nightscout.comboctl.main.Pump] session on the bench pairing.
 * Keys stay in [BenchPairingStore]; the TBR state the driver needs for reconciliation lives in
 * [tbrFile] via [files]. Nonce advances are bounded and must be the driver's own +1 / +500 steps.
 */
@OptIn(ExperimentalTime::class)
internal class TherapySessionStore(private val store: PumpStateStore, private val files: JsonFiles,
                                   private val tbrFile: String = "manual-tbr-state.json") : PumpStateStore by store {
    var advances = 0
        private set
    var recoveryJumps = 0
        private set

    @Synchronized override fun setCurrentTxNonce(pumpAddress: BluetoothAddress, currentTxNonce: Nonce) {
        val previous = store.getCurrentTxNonce(pumpAddress)
        val step = when (currentTxNonce) {
            previous.getIncrementedNonce(1) -> 1L
            previous.getIncrementedNonce(TherapySessionPolicy.NONCE_RECOVERY_STEP) -> TherapySessionPolicy.NONCE_RECOVERY_STEP.toLong()
            else -> 0L
        }
        check(TherapySessionPolicy.nonceStepAllowed(step)) { "Nonce jump or rollback rejected" }
        check(advances < TherapySessionPolicy.NONCE_BUDGET) { "Session packet budget exceeded" }
        // Count before persistence: an uncertain write must never be treated as untouched state.
        advances++
        if (step != 1L) recoveryJumps++
        store.setCurrentTxNonce(pumpAddress, currentTxNonce)
        check(store.getCurrentTxNonce(pumpAddress) == currentTxNonce) { "Nonce persistence not verified" }
    }

    @Synchronized override fun getCurrentTbrState(pumpAddress: BluetoothAddress): CurrentTbrState {
        check(store.hasPumpState(pumpAddress))
        if (!files.exists(tbrFile)) return CurrentTbrState.NoTbrOngoing
        val saved = files.read(tbrFile)
        check(saved.getString("address").equals(pumpAddress.toString(), ignoreCase = true)) { "TBR state belongs to another pump" }
        if (!saved.getBoolean("started")) return CurrentTbrState.NoTbrOngoing
        return CurrentTbrState.TbrStarted(Tbr(Instant.fromEpochMilliseconds(saved.getLong("timestampMs")),
            saved.getInt("percentage"), saved.getInt("durationMinutes"),
            checkNotNull(Tbr.Type.fromStringId(saved.getString("type")))))
    }

    @Synchronized override fun setCurrentTbrState(pumpAddress: BluetoothAddress, currentTbrState: CurrentTbrState) {
        check(store.hasPumpState(pumpAddress))
        val value = JSONObject().put("address", pumpAddress.toString()).put("savedAt", System.currentTimeMillis())
        when (currentTbrState) {
            CurrentTbrState.NoTbrOngoing -> value.put("started", false)
            is CurrentTbrState.TbrStarted -> value.put("started", true)
                .put("timestampMs", currentTbrState.tbr.timestamp.toEpochMilliseconds())
                .put("percentage", currentTbrState.tbr.percentage)
                .put("durationMinutes", currentTbrState.tbr.durationInMinutes)
                .put("type", currentTbrState.tbr.type.stringId)
        }
        files.write(tbrFile, value)
        // Instant round-trips through epoch milliseconds, so compare the persisted fields, not the object.
        check(sameTbrState(getCurrentTbrState(pumpAddress), currentTbrState)) { "TBR state persistence not verified" }
    }

    private fun sameTbrState(a: CurrentTbrState, b: CurrentTbrState): Boolean = when {
        a is CurrentTbrState.TbrStarted && b is CurrentTbrState.TbrStarted ->
            a.tbr.percentage == b.tbr.percentage && a.tbr.durationInMinutes == b.tbr.durationInMinutes &&
                a.tbr.type == b.tbr.type && a.tbr.timestamp.toEpochMilliseconds() == b.tbr.timestamp.toEpochMilliseconds()
        else -> a == b
    }

    override fun createPumpState(pumpAddress: BluetoothAddress, invariantPumpData: InvariantPumpData,
                                 utcOffset: UtcOffset, tbrState: CurrentTbrState): Unit = error("Pairing forbidden")
    override fun deletePumpState(pumpAddress: BluetoothAddress): Boolean = error("Pairing deletion forbidden")
}
