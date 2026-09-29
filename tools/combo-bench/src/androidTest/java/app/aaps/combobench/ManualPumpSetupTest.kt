package app.aaps.combobench

import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** No Start pairing click, socket, adapter change or pump access. */
class ManualPumpSetupTest {
    @Test fun serialAndOptionalAddressSurviveActivityRecreation() {
        assumeTrue(BuildConfig.MANUAL_TARGET)
        ActivityScenario.launch(ManualPumpSetupActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.findViewById<EditText>(R.id.manual_pump_serial).setText("10392647")
                it.findViewById<EditText>(R.id.manual_pump_address).setText("00:0E:2F:12:34:56")
            }
            scenario.recreate()
            scenario.onActivity {
                assertEquals("10392647", it.findViewById<EditText>(R.id.manual_pump_serial).text.toString())
                assertEquals("00:0E:2F:12:34:56", it.findViewById<EditText>(R.id.manual_pump_address).text.toString())
            }
        }
    }

    @Suppress("DEPRECATION")
    @Test fun manualPackageIsIsolatedFromOldBenchAndRemoteCommands() {
        assumeTrue(BuildConfig.MANUAL_TARGET)
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.aaps.combobench.manual", app.packageName)
        val info = app.packageManager.getPackageInfo(app.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS)
        assertFalse(info.services.orEmpty().any { it.name.endsWith("BenchListener") })
        assertFalse(info.receivers.orEmpty().any { it.name.endsWith("BenchCommandReceiver") })
        val service = info.services.orEmpty().single { it.name.endsWith("PairingForegroundService") }
        assertFalse(service.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, service.foregroundServiceType)
        assertTrue(info.activities.orEmpty().filter { it.name.startsWith("app.aaps.combobench.") }
            .all { it.taskAffinity == app.packageName })
    }
}
