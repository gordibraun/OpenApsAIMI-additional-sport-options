package app.aaps.combobench.controller

import app.aaps.combobench.JsonFiles
import info.nightscout.comboctl.base.BluetoothAddress
import info.nightscout.comboctl.base.CurrentTbrState
import info.nightscout.comboctl.base.InvariantPumpData
import info.nightscout.comboctl.base.Nonce
import info.nightscout.comboctl.base.NUM_NONCE_BYTES
import info.nightscout.comboctl.base.PumpStateStore
import info.nightscout.comboctl.base.Tbr
import kotlinx.datetime.UtcOffset
import org.json.JSONObject
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Pump state for the controller's driver sessions, on top of the bench pairing.
 *
 * The keys stay in the pairing store, encrypted. What differs from the bench's own session store
 * is how the transmit nonce is persisted. Every packet sent to the pump carries the next nonce,
 * and the pairing store encrypts, syncs and re-reads its file on each write - through the Android
 * keystore, which is an IPC to a system service. Profiled on the watch, that was more than a
 * quarter of all CPU time during a session and made sending a single button press take
 * 0.5-1.2 s, long enough for the pump to see a tap as a hold.
 *
 * A nonce only has to be unique and increasing, so it does not need to be on disk before each
 * packet; what must never happen is that one is used twice after a restart. This store therefore
 * persists a mark that lies ahead of every nonce it hands out, and writes a new one only when the
 * session reaches it. After a crash the next session starts from the mark and simply skips the
 * few that were reserved and not used - the same thing the driver's own recovery does when it
 * jumps the nonce ahead by 500.
 *
 * The TBR state lives in the same file and format the bench's session store uses, so the two can
 * take turns on the same pump.
 */
@OptIn(ExperimentalTime::class)
internal class ControllerPumpStore(
    private val pairing: PumpStateStore,
    private val files: JsonFiles,
    private val tbrFile: String = "manual-tbr-state.json"
) : PumpStateStore by pairing {

    /** The nonce in use, in memory. Null until first read from the pairing store. */
    private var current: Nonce? = null

    /** What is on disk: no nonce at or above this has been used. */
    private var persistedMark: Nonce? = null

    /** How many packets this session sent, for the session record. */
    var advances = 0
        private set

    /** How many times the mark was written, i.e. how many slow writes the session cost. */
    var markWrites = 0
        private set

    @Synchronized override fun getCurrentTxNonce(pumpAddress: BluetoothAddress): Nonce =
        current ?: pairing.getCurrentTxNonce(pumpAddress).also {
            current = it
            persistedMark = it
        }

    @Synchronized override fun setCurrentTxNonce(pumpAddress: BluetoothAddress, currentTxNonce: Nonce) {
        val previous = getCurrentTxNonce(pumpAddress)
        check(compare(currentTxNonce, previous) > 0) { "Nonce rollback rejected" }
        check(advances < NONCE_BUDGET) { "Session packet budget exceeded" }
        advances++
        // The mark has to be on disk before a nonce beyond it is used.
        if (compare(currentTxNonce, checkNotNull(persistedMark)) >= 0) {
            val mark = currentTxNonce.getIncrementedNonce(NONCE_BLOCK)
            pairing.setCurrentTxNonce(pumpAddress, mark)
            check(pairing.getCurrentTxNonce(pumpAddress) == mark) { "Nonce persistence not verified" }
            persistedMark = mark
            markWrites++
        }
        current = currentTxNonce
    }

    @Synchronized override fun getCurrentTbrState(pumpAddress: BluetoothAddress): CurrentTbrState {
        check(pairing.hasPumpState(pumpAddress))
        if (!files.exists(tbrFile)) return CurrentTbrState.NoTbrOngoing
        val saved = files.read(tbrFile)
        check(saved.getString("address").equals(pumpAddress.toString(), ignoreCase = true)) { "TBR state belongs to another pump" }
        if (!saved.getBoolean("started")) return CurrentTbrState.NoTbrOngoing
        return CurrentTbrState.TbrStarted(
            Tbr(
                Instant.fromEpochMilliseconds(saved.getLong("timestampMs")),
                saved.getInt("percentage"), saved.getInt("durationMinutes"),
                checkNotNull(Tbr.Type.fromStringId(saved.getString("type")))
            )
        )
    }

    @Synchronized override fun setCurrentTbrState(pumpAddress: BluetoothAddress, currentTbrState: CurrentTbrState) {
        check(pairing.hasPumpState(pumpAddress))
        val value = JSONObject().put("address", pumpAddress.toString()).put("savedAt", System.currentTimeMillis())
        when (currentTbrState) {
            CurrentTbrState.NoTbrOngoing  -> value.put("started", false)
            is CurrentTbrState.TbrStarted -> value.put("started", true)
                .put("timestampMs", currentTbrState.tbr.timestamp.toEpochMilliseconds())
                .put("percentage", currentTbrState.tbr.percentage)
                .put("durationMinutes", currentTbrState.tbr.durationInMinutes)
                .put("type", currentTbrState.tbr.type.stringId)
        }
        files.write(tbrFile, value)
    }

    override fun createPumpState(
        pumpAddress: BluetoothAddress, invariantPumpData: InvariantPumpData, utcOffset: UtcOffset, tbrState: CurrentTbrState
    ): Unit = error("Pairing is not done through the controller's session store")

    override fun deletePumpState(pumpAddress: BluetoothAddress): Boolean =
        error("Pairing is not removed through the controller's session store")

    private companion object {

        /** Nonces reserved ahead per write. A status read sends ~60 packets, a TBR a few hundred. */
        const val NONCE_BLOCK = 256

        /** Far above what any single session sends; a runaway driver is stopped here. */
        const val NONCE_BUDGET = 6_000

        /** Nonces are little-endian: the last byte is the most significant. */
        fun compare(a: Nonce, b: Nonce): Int {
            for (index in NUM_NONCE_BYTES - 1 downTo 0) {
                val difference = (a[index].toInt() and 0xFF) - (b[index].toInt() and 0xFF)
                if (difference != 0) return difference
            }
            return 0
        }
    }
}
