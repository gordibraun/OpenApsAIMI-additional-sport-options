package app.aaps.database.persistence.converters

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.GlucoseStatusSMB
import app.aaps.core.interfaces.aps.RT
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import javax.inject.Provider

class AimiApsResultPersistenceTest {
    private fun row(glucoseJson: String) = app.aaps.database.entities.APSResult(
        timestamp = 1_800_000_000_000L,
        algorithm = app.aaps.database.entities.APSResult.Algorithm.AIMI,
        glucoseStatusJson = glucoseJson, currentTempJson = null, iobDataJson = null,
        profileJson = null, autosensDataJson = null, mealDataJson = null,
        resultJson = RT(algorithm = APSResult.Algorithm.AIMI, runningDynamicIsf = true, variable_sens = 42.0).serialize()
    )

    @Test fun aimiSpecificGlucoseFieldsSurviveDatabaseReload() {
        val glucose = GlucoseStatusAIMI(glucose = 158.0, combinedDelta = 2.3, bgAcceleration = 0.4, duraISFminutes = 15.0)
        val result = mock<APSResult>()
        whenever(result.with(any<RT>())).thenReturn(result)
        row(Json.encodeToString(glucose)).fromDb(Provider { result })
        verify(result).glucoseStatus = glucose
        verify(result).with(argThat<RT> { algorithm == APSResult.Algorithm.AIMI && runningDynamicIsf && variable_sens == 42.0 })
    }

    @Test fun legacyCommonGlucoseFieldsStillLoadAsAimi() {
        val result = mock<APSResult>()
        whenever(result.with(any<RT>())).thenReturn(result)
        row(Json.encodeToString(GlucoseStatusSMB(glucose = 158.0, delta = 2.0))).fromDb(Provider { result })
        verify(result).glucoseStatus = GlucoseStatusAIMI(glucose = 158.0, delta = 2.0)
    }

    @Test fun corruptOptionalGlucoseDoesNotDiscardTheWholeDecision() {
        val result = mock<APSResult>()
        whenever(result.with(any<RT>())).thenReturn(result)
        row("broken-json").fromDb(Provider { result })
        verify(result).glucoseStatus = null
        verify(result).date = 1_800_000_000_000L
    }
}
