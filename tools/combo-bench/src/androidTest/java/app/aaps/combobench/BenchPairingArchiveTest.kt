package app.aaps.combobench

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import info.nightscout.comboctl.base.Cipher
import info.nightscout.comboctl.base.CurrentTbrState
import info.nightscout.comboctl.base.InvariantPumpData
import info.nightscout.comboctl.base.toBluetoothAddress
import kotlinx.datetime.UtcOffset
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class BenchPairingArchiveTest {
    private fun isolated(test: (Context, File) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "pairing-archive-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = root
        }
        try { test(context, root) } finally { root.deleteRecursively() }
    }

    @Test fun archivePreservesEncryptedBytesAndNeverRestoresThemAutomatically() = isolated { context, root ->
        val address = "00:11:22:33:44:55".toBluetoothAddress()
        val store = BenchPairingStore(context, address, "PUMP_TEST")
        val data = InvariantPumpData(Cipher(ByteArray(16) { 1 }), Cipher(ByteArray(16) { 2 }), 1, "PUMP_TEST")
        store.createPumpState(address, data, UtcOffset.ZERO, CurrentTbrState.NoTbrOngoing)
        val encrypted = File(root, "combo-pairing.enc").readBytes()
        val archive = store.archiveForRePairing()
        assertArrayEquals(encrypted, File(root, archive).readBytes())
        assertFalse(store.hasPumpState(address))
        assertFalse(BenchPairingStore(context, address, "PUMP_TEST").hasPumpState(address))
        store.createPumpState(address, data, UtcOffset.ZERO, CurrentTbrState.NoTbrOngoing)
        assertEquals("PUMP_TEST", store.getInvariantPumpData(address).pumpID)
        assertArrayEquals(encrypted, File(root, archive).readBytes())
    }

    @Test fun wrongPumpCannotRetireAnExistingPairing() = isolated { context, root ->
        val address = "00:11:22:33:44:55".toBluetoothAddress()
        val store = BenchPairingStore(context, address, "PUMP_TEST")
        store.createPumpState(address,
            InvariantPumpData(Cipher(ByteArray(16) { 1 }), Cipher(ByteArray(16) { 2 }), 1, "PUMP_TEST"),
            UtcOffset.ZERO, CurrentTbrState.NoTbrOngoing)
        val before = File(root, "combo-pairing.enc").readBytes()
        assertThrows(IllegalStateException::class.java) {
            BenchPairingStore(context, address, "PUMP_OTHER").archiveForRePairing()
        }
        assertTrue(store.hasPumpState(address))
        assertArrayEquals(before, File(root, "combo-pairing.enc").readBytes())
        assertEquals(1, root.listFiles()!!.size)
    }

    @Test fun differentSerialFromProtocolNeverCreatesPairingState() = isolated { context, root ->
        val address = "00:0E:2F:12:34:56".toBluetoothAddress()
        val store = BenchPairingStore(context, address, "PUMP_10392647")
        assertThrows(IllegalStateException::class.java) {
            store.createPumpState(address,
                InvariantPumpData(Cipher(ByteArray(16)), Cipher(ByteArray(16)), 1, "PUMP_41056642"),
                UtcOffset.ZERO, CurrentTbrState.NoTbrOngoing)
        }
        assertFalse(store.hasPumpState(address))
        assertEquals(0, root.listFiles()!!.size)
    }
}
