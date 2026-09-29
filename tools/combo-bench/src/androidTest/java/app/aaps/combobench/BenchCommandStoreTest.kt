package app.aaps.combobench

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** No Activity, BenchRuntime, Bluetooth, pairing store or pump is used by these tests. */
@RunWith(AndroidJUnit4::class)
class BenchCommandStoreTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private val identity = BenchIdentity("test-session", "phone", "watch", "synthetic-pump")
    private val request = BenchCommandRequest("request-1", 0, "a".repeat(64))
    private var sends = 0

    @Before fun setup() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(base.cacheDir, "command-journal-test-${UUID.randomUUID()}")
        check(directory.mkdir())
        context = object : ContextWrapper(base) { override fun getFilesDir(): File = directory }
        BenchFiles(context).write("test-ownership.json", OwnershipState("phone").toJson())
    }

    @After fun cleanup() { if (::directory.isInitialized) directory.deleteRecursively() }

    private fun journal(): BenchCommandJournal {
        val files = BenchFiles(context)
        val ownership = DiagnosticOwnership(identity, object : OwnershipStore {
            override fun load() = files.read("test-ownership.json").toOwnershipState()
            override fun save(state: OwnershipState) = files.write("test-ownership.json", state.toJson())
        })
        return BenchCommandJournal(identity, ownership, AndroidBenchCommandStore(context, identity))
    }

    @Test fun acknowledgedCommandSurvivesReopeningBothStores() {
        AndroidBenchCommandStore(context, identity).provision()
        journal().executeSimulated(request) { sends++ }
        val reopened = journal()
        assertEquals(BenchCommandOutcome.ACKNOWLEDGED, reopened.snapshot().records[request.id]?.outcome)
        assertThrows(Exception::class.java) { reopened.executeSimulated(request) { sends++ } }
        assertEquals(1, sends)
    }

    @Test fun lostAcknowledgementSurvivesReopeningBothStores() {
        AndroidBenchCommandStore(context, identity).provision()
        assertThrows(Exception::class.java) {
            journal().executeSimulated(request) { sends++; error("Simulated lost receipt") }
        }
        val reopened = journal()
        assertEquals(BenchCommandOutcome.UNKNOWN, reopened.snapshot().records[request.id]?.outcome)
        assertThrows(Exception::class.java) { reopened.executeSimulated(request) { sends++ } }
        assertThrows(Exception::class.java) { reopened.executeSimulated(request.copy(id = "new-request")) { sends++ } }
        assertNotNull(BenchFiles(context).read("test-ownership.json").toOwnershipState().operation)
        assertEquals(1, sends)
    }

    @Test fun missingJournalDoesNotCreateAnEmptyHistory() {
        assertThrows(Exception::class.java) { journal().executeSimulated(request) { sends++ } }
        assertEquals(0, sends)
        assertFalse(File(directory, "bench-command-journal-v1.json").exists())
    }

    @Test fun provisioningCannotOverwriteHistory() {
        val store = AndroidBenchCommandStore(context, identity)
        store.provision()
        journal().executeSimulated(request) { sends++ }
        assertThrows(Exception::class.java) { store.provision() }
        assertThrows(Exception::class.java) {
            AndroidBenchCommandStore(context, identity.copy(local = "watch", peer = "phone")).load()
        }
        assertEquals(BenchCommandOutcome.ACKNOWLEDGED, store.load().records[request.id]?.outcome)
    }

    @Test fun unfinishedAtomicWritePreservesLastAcknowledgement() {
        AndroidBenchCommandStore(context, identity).provision()
        journal().executeSimulated(request) { sends++ }
        File(directory, "bench-command-journal-v1.json.new").writeText("incomplete write")
        val reopened = journal()
        assertEquals(BenchCommandOutcome.ACKNOWLEDGED, reopened.snapshot().records[request.id]?.outcome)
        assertThrows(Exception::class.java) { reopened.executeSimulated(request) { sends++ } }
        assertEquals(1, sends)
    }
}
