package app.aaps.plugins.main.general.overview

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AimiMealAssist
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.PendingWizardTreatment
import app.aaps.core.objects.aps.ApsDecisionSnapshot
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Single
import org.json.JSONObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class OverviewDecisionStatusTest {
    private val now = 10_000_000L
    private lateinit var fragment: OverviewFragment

    @BeforeEach fun prepare() {
        // Exercise the real display functions without starting the fragment's Android thread.
        fragment = mock(defaultAnswer = CALLS_REAL_METHODS)
        fragment.config = mock()
        fragment.dateUtil = mock()
        fragment.rh = mock()
        fragment.loop = mock()
        fragment.aimiMealAssist = mock<AimiMealAssist>()
        fragment.persistenceLayer = mock()
        whenever(fragment.config.APS).thenReturn(true)
        whenever(fragment.dateUtil.now()).thenReturn(now)
        whenever(fragment.persistenceLayer.getTherapyEventDataFromToTime(any(), any())).thenReturn(Single.just(emptyList()))
        whenever(fragment.aimiMealAssist.pendingTreatment()).thenReturn(
            PendingWizardTreatment(now - 600_000, now - 600_000, 1.4, now - 600_000, 18.0)
        )
    }

    @Test fun oldManualRequestDoesNotReplaceCurrentAutomaticLimit() {
        val result = result(floor = 55.0)
        whenever(fragment.loop.lastRun).thenReturn(Loop.LastRun().apply { constraintsProcessed = result })
        val manualSnapshot = ApsDecisionSnapshot.fromLoop(fragment.loop, fragment.aimiMealAssist, fragment.persistenceLayer, now)
        assertThat(manualSnapshot.state).isEqualTo(ApsDecisionSnapshot.State.REVIEW_DELIVERY)
        assertThat(manualSnapshot.insulin).isNull()

        val overview = deliveryOverview(result)
        assertThat(property(overview, "label")).isEqualTo("ЛИМИТ")
        assertThat(property(overview, "detailText").toString()).contains("риск низкой глюкозы")
    }

    @Test fun oldManualRequestDoesNotReplaceAutomaticNoRequest() {
        assertThat(property(deliveryOverview(result(floor = 120.0)), "label")).isEqualTo("СТОП")
    }

    @Test fun staleAutomaticResultIsNotPresentedAsCurrent() {
        assertThat(property(deliveryOverview(result(date = now - 16 * 60_000)), "label")).isEqualTo("Расчет устарел")
    }

    @Test fun missingAutomaticResultIsNotPresentedAsCurrent() {
        assertThat(property(deliveryOverview(null), "label")).isEqualTo("Нет расчета")
    }

    @Test fun unverifiedCarbWarningRemainsVisibleWithoutInventingGrams() {
        val snapshot = ApsDecisionSnapshot.from(result(carbs = 4), now, pending = true, reviewDelivery = true)
        val line = OverviewFragment::class.java.getDeclaredMethod("apsDecisionLine", ApsDecisionSnapshot::class.java)
            .apply { isAccessible = true }.invoke(fragment, snapshot)!!
        assertThat(property(line, "text")).isEqualTo("Углеводы")
        assertThat(property(line, "colorAttr")).isEqualTo(app.aaps.core.ui.R.attr.carbsColor)
        assertThat(snapshot.insulin).isNull()
        assertThat(snapshot.carbs).isNull()
    }

    private fun result(floor: Double = 120.0, date: Long = now, carbs: Int = 0): APSResult {
        val json = mock<JSONObject>()
        whenever(json.optDouble(any(), any())).thenAnswer { it.getArgument<Double>(1) }
        whenever(json.optDouble("insulinReq", 0.0)).thenReturn(0.0)
        whenever(json.optDouble("predictedBG", Double.NaN)).thenReturn(floor)
        return mock<APSResult>().also {
            whenever(it.date).thenReturn(date)
            whenever(it.reason).thenReturn("")
            whenever(it.carbsReq).thenReturn(carbs)
            whenever(it.json()).thenReturn(json)
        }
    }

    private fun deliveryOverview(result: APSResult?): Any =
        OverviewFragment::class.java.getDeclaredMethod("apsInsulinDeliveryOverview", APSResult::class.java)
            .apply { isAccessible = true }.invoke(fragment, result)!!

    private fun property(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
}
