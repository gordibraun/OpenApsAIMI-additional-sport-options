package app.aaps.combobench

import info.nightscout.comboctl.base.*
import kotlinx.datetime.UtcOffset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class TherapySessionStoreTbrTest {
    private val address = "00:0E:2F:E7:D1:95".toBluetoothAddress()

    private class KeysOnlyStore(private val present: Boolean = true) : PumpStateStore {
        var nonce = Nonce.nullNonce()
        override fun createPumpState(pumpAddress: BluetoothAddress, invariantPumpData: InvariantPumpData,
                                     utcOffset: UtcOffset, tbrState: CurrentTbrState) = error("unused")
        override fun deletePumpState(pumpAddress: BluetoothAddress) = error("unused")
        override fun hasPumpState(pumpAddress: BluetoothAddress) = present
        override fun getAvailablePumpStateAddresses() = if (present) setOf("00:0E:2F:E7:D1:95".toBluetoothAddress()) else emptySet()
        override fun getInvariantPumpData(pumpAddress: BluetoothAddress) = error("unused")
        override fun getCurrentTxNonce(pumpAddress: BluetoothAddress) = nonce
        override fun setCurrentTxNonce(pumpAddress: BluetoothAddress, currentTxNonce: Nonce) { nonce = currentTxNonce }
        override fun getCurrentUtcOffset(pumpAddress: BluetoothAddress) = UtcOffset.ZERO
        override fun setCurrentUtcOffset(pumpAddress: BluetoothAddress, utcOffset: UtcOffset) = Unit
        override fun getCurrentTbrState(pumpAddress: BluetoothAddress) = error("must not be consulted")
        override fun setCurrentTbrState(pumpAddress: BluetoothAddress, currentTbrState: CurrentTbrState) = error("must not be written")
    }

    /** In-memory stand-in for BenchFiles: durable copies, same verification semantics. */
    private class MemoryFiles : JsonFiles {
        val stored = HashMap<String, String>()
        var writes = 0
        override fun exists(name: String) = stored.containsKey(name)
        override fun read(name: String) = JSONObject(stored.getValue(name))
        override fun write(name: String, value: JSONObject) { writes++; stored[name] = value.toString() }
    }

    private fun rejected(block: () -> Unit) = assertTrue(runCatching(block).isFailure)

    @Test fun missingFileMeansNoTbr() {
        val store = TherapySessionStore(KeysOnlyStore(), MemoryFiles())
        assertEquals(CurrentTbrState.NoTbrOngoing, store.getCurrentTbrState(address))
    }

    @Test fun startedTbrSurvivesRoundTripWithMillisecondTimestamp() {
        val files = MemoryFiles()
        val store = TherapySessionStore(KeysOnlyStore(), files)
        val started = Tbr(Instant.fromEpochMilliseconds(1_790_642_000_123L) + kotlin.time.Duration.parse("456us"),
            0, 30, Tbr.Type.EMULATED_COMBO_STOP)
        store.setCurrentTbrState(address, CurrentTbrState.TbrStarted(started))
        val reloaded = TherapySessionStore(KeysOnlyStore(), files).getCurrentTbrState(address) as CurrentTbrState.TbrStarted
        assertEquals(0, reloaded.tbr.percentage)
        assertEquals(30, reloaded.tbr.durationInMinutes)
        assertEquals(Tbr.Type.EMULATED_COMBO_STOP, reloaded.tbr.type)
        assertEquals(1_790_642_000_123L, reloaded.tbr.timestamp.toEpochMilliseconds())
        assertEquals(1, files.writes)
    }

    @Test fun clearingTbrIsPersistedAndReadBack() {
        val files = MemoryFiles()
        val store = TherapySessionStore(KeysOnlyStore(), files)
        store.setCurrentTbrState(address, CurrentTbrState.TbrStarted(Tbr(Instant.fromEpochMilliseconds(1), 0, 15, Tbr.Type.NORMAL)))
        store.setCurrentTbrState(address, CurrentTbrState.NoTbrOngoing)
        assertEquals(CurrentTbrState.NoTbrOngoing, store.getCurrentTbrState(address))
        assertFalse(JSONObject(files.stored.getValue("manual-tbr-state.json")).getBoolean("started"))
    }

    @Test fun stateOfAnotherPumpIsRefused() {
        val files = MemoryFiles()
        files.write("manual-tbr-state.json", JSONObject().put("address", "00:0E:2F:25:24:BC").put("started", true)
            .put("timestampMs", 1).put("percentage", 0).put("durationMinutes", 15).put("type", "normal"))
        rejected { TherapySessionStore(KeysOnlyStore(), files).getCurrentTbrState(address) }
    }

    @Test fun corruptStateIsAnErrorNotEmptyHistory() {
        val files = MemoryFiles()
        files.write("manual-tbr-state.json", JSONObject().put("address", "00:0E:2F:E7:D1:95").put("started", true)
            .put("timestampMs", 1).put("percentage", 0).put("durationMinutes", 15).put("type", "no-such-type"))
        rejected { TherapySessionStore(KeysOnlyStore(), files).getCurrentTbrState(address) }
    }

    @Test fun tbrStateNeedsAStoredPairing() {
        val store = TherapySessionStore(KeysOnlyStore(present = false), MemoryFiles())
        rejected { store.getCurrentTbrState(address) }
        rejected { store.setCurrentTbrState(address, CurrentTbrState.NoTbrOngoing) }
    }
}
