package app.aaps.plugins.aps.openAPSAIMI

import android.os.Environment
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.ISF.IsfStateStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class IsfRestartTest {
    @TempDir lateinit var directory: File

    @BeforeEach fun androidStorage() {
        mockkStatic(Environment::class)
        every { Environment.getExternalStorageDirectory() } returns directory
    }

    @AfterEach fun clearAndroidStorage() { unmockkStatic(Environment::class) }

    private var now = 1_800_000_000_000L
    private val saved = mutableMapOf<String, String>()
    private val sp = mockk<SP>(relaxed = true).also { sp ->
        every { sp.getStringOrNull(any<String>(), null) } answers { saved[firstArg<String>()] }
        every { sp.putString(any<String>(), any()) } answers { saved[firstArg()] = secondArg(); Unit }
    }
    private val logger = mockk<AAPSLogger>(relaxed = true)
    private val preferences = mockk<Preferences>(relaxed = true).also {
        every { it.get(BooleanKey.ApsUseDynamicSensitivity) } returns true
        every { it.get(DoubleKey.OApsAIMITDD7) } returns 40.0
        every { it.get(DoubleKey.OApsAIMIIsfFusionMinFactor) } returns 0.75
        every { it.get(DoubleKey.OApsAIMIIsfFusionMaxFactor) } returns 1.25
    }
    private val profile = mockk<Profile>(relaxed = true).also {
        every { it.getProfileIsfMgdl() } returns 60.0
        every { it.percentage } returns 100
        every { it.getIsfsMgdlValues() } returns arrayOf(Profile.ProfileValue(0, 60.0))
    }
    private val profiles = mockk<ProfileFunction>(relaxed = true).also {
        every { it.getProfile() } returns profile
        every { it.getOriginalProfileName() } returns "current-profile"
    }
    private val persistence = mockk<PersistenceLayer>(relaxed = true).also {
        every { it.getApsResultCloseTo(any()) } returns null
        every { it.getApsResults(any(), any()) } returns emptyList()
    }
    private val glucose = mockk<GlucoseStatusProvider>(relaxed = true).also {
        every { it.glucoseStatusData } returns GlucoseStatusAIMI(160.0, delta = 2.0)
    }
    private val iob = mockk<IobCobCalculator>(relaxed = true).also {
        every { it.ads.getBucketedDataTableCopy() } returns null
    }
    private val tdd = mockk<TddCalculator>(relaxed = true).also {
        every { it.averageTDD(any()) } returns null
        every { it.calculateDaily(any(), any()) } returns null
    }
    private val date = mockk<DateUtil>(relaxed = true).also { every { it.now() } answers { now } }
    private fun plugin() = OpenAPSAIMIPlugin(
        injector = mockk(relaxed = true), aapsLogger = logger, rxBus = mockk(relaxed = true),
        constraintsChecker = mockk(relaxed = true), rh = mockk(relaxed = true),
        profileFunction = profiles, profileUtil = mockk(relaxed = true), config = mockk(relaxed = true),
        activePlugin = mockk(relaxed = true), iobCobCalculator = iob, hardLimits = mockk(relaxed = true),
        preferences = preferences, dateUtil = date, processedTbrEbData = mockk(relaxed = true),
        persistenceLayer = persistence, glucoseStatusProvider = glucose,
        glucoseStatusCalculatorAimi = mockk(relaxed = true), tddCalculator = tdd,
        bgQualityCheck = mockk(relaxed = true), uiInteraction = mockk(relaxed = true),
        determineBasalaimiSMB2 = mockk(relaxed = true), profiler = mockk(relaxed = true),
        context = mockk(relaxed = true), apsResultProvider = mockk(relaxed = true),
        isfStateStore = IsfStateStore(sp, logger)
    )

    @Test fun realPluginContinuesTheSameDynamicCalculationAfterRestart() {
        val uninterrupted = plugin()
        repeat(12) { uninterrupted.getIsfMgdl(profile, "test"); now += 300_000 }
        val snapshotBeforeRestart = saved.toMap()
        val expected = uninterrupted.getIsfMgdl(profile, "test")!!
        saved.clear()
        saved.putAll(snapshotBeforeRestart)
        assertEquals(expected, plugin().getIsfMgdl(profile, "test")!!, 1e-12)
    }

    @Test fun foodIsfRestoresDatabaseSamplesInsteadOfProfileFallback() {
        val result = mockk<APSResult>(relaxed = true)
        every { result.algorithm } returns APSResult.Algorithm.AIMI
        every { result.date } returns now - 300_000
        every { result.variableSens } returns 42.0
        every { result.glucoseStatus } returns GlucoseStatusAIMI(158.0, combinedDelta = 2.0)
        every { persistence.getApsResults(any(), any()) } returns listOf(result)
        assertEquals(42.0, plugin().getAverageIsfMgdl(now, "test"))
        every { persistence.getApsResults(any(), any()) } returns emptyList()
        assertEquals(42.0, plugin().getAverageIsfMgdl(now, "test"))
    }
}
