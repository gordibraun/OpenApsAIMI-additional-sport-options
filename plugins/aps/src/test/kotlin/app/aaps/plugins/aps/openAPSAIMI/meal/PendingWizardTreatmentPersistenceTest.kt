package app.aaps.plugins.aps.openAPSAIMI.meal

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.PendingWizardTreatment
import app.aaps.core.interfaces.aps.TreatmentInputStamp
import app.aaps.core.interfaces.sharedPreferences.SP
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PendingWizardTreatmentPersistenceTest {
    private val values = mutableMapOf<String, String>()
    private val sp = mockk<SP>()
    private val editor = mockk<SP.Editor>()
    private val acceptedAt = 1_000_000L
    private val pending = PendingWizardTreatment(acceptedAt, acceptedAt, 0.7, 0, 0.0)
    private var now = acceptedAt + 60_000
    private fun service() = AimiMealAssistImpl(mockk(relaxed = true), sp).apply { nowProvider = { now } }

    @BeforeEach fun setUp() {
        every { sp.getString(any<String>(), any()) } answers { values[firstArg<String>()] ?: secondArg<String>() }
        every { editor.putString(any<String>(), any()) } answers { values[firstArg()] = secondArg() }
        every { editor.remove(any<String>()) } answers { values.remove(firstArg<String>()); Unit }
        every { sp.edit(any(), any()) } answers { secondArg<SP.Editor.() -> Unit>().invoke(editor) }
    }

    @Test fun survivesServiceRecreationAndANewerTreatmentSupersedesIt() {
        assertTrue(service().beginTreatment(pending))
        val restarted = service()
        assertEquals(pending, restarted.pendingTreatment())
        // The owner is never refused a dose over the status of the previous one (30.10.2026).
        val newer = pending.copy(acceptedAt = acceptedAt + 1)
        assertTrue(restarted.beginTreatment(newer))
        assertEquals(newer, restarted.pendingTreatment())
        restarted.clearPendingTreatment()
        assertEquals(newer, restarted.pendingTreatment())
    }

    @Test fun pendingTreatmentAgesOutInsteadOfAskingForAManualReview() {
        val sut = service()
        sut.beginTreatment(pending)
        sut.completeTreatment(acceptedAt, acceptedAt, 0.0, false) // outcome unknown: never confirmed
        now = acceptedAt + 14 * 60_000
        assertEquals(pending, sut.pendingTreatment())
        now = acceptedAt + 16 * 60_000
        assertNull(sut.pendingTreatment())
        assertNull(service().pendingTreatment(), "an aged-out treatment is gone from storage too")
    }

    @Test fun successfulDeliveryWaitsForActualInputInclusion() {
        val sut = service()
        sut.beginTreatment(pending)
        sut.completeTreatment(acceptedAt, acceptedAt + 1000, 0.7, true)
        sut.acknowledgeTreatment(IobTotal(acceptedAt + 60_000), null)
        assertNotNull(sut.pendingTreatment())
        sut.acknowledgeTreatment(IobTotal(acceptedAt + 120_000).apply {
            bolusInputs = listOf(TreatmentInputStamp(acceptedAt + 1000, 0.7, acceptedAt + 2000))
        }, null)
        assertNull(sut.pendingTreatment())
    }

    @Test fun ambiguousFailureOrInvalidReceiptNeverUnlocks() {
        val sut = service()
        sut.beginTreatment(pending)
        listOf(0.0, Double.NaN, -1.0, Double.POSITIVE_INFINITY).forEach {
            sut.completeTreatment(acceptedAt, acceptedAt, it, false)
            sut.acknowledgeTreatment(IobTotal(acceptedAt + 60_000), null)
            assertEquals(pending, sut.pendingTreatment())
        }
    }

    @Test fun partialFailedDeliveryTracksActualInsulinNotRequestedAmount() {
        val sut = service()
        sut.beginTreatment(pending.copy(carbsTimestamp = acceptedAt, carbs = 13.0))
        sut.completeTreatment(acceptedAt, acceptedAt + 1000, 0.3, false)
        assertEquals(0.3, sut.pendingTreatment()!!.insulin, 0.0)
        assertEquals(13.0, sut.pendingTreatment()!!.carbs, 0.0)
        val iob = IobTotal(acceptedAt + 60_000).apply {
            bolusInputs = listOf(TreatmentInputStamp(acceptedAt + 1000, 0.3, acceptedAt + 2000))
        }
        sut.acknowledgeTreatment(iob, null)
        assertNotNull(sut.pendingTreatment())
        sut.acknowledgeTreatment(iob, MealData(carbInputs = listOf(TreatmentInputStamp(acceptedAt, 13.0, acceptedAt + 3000))))
        assertNull(sut.pendingTreatment())
    }

    @Test fun storingCarbsDoesNotResolveAnUnknownBolusReceipt() {
        val sut = service()
        val entered = pending.copy(carbsTimestamp = acceptedAt, carbs = 18.0)
        sut.beginTreatment(entered)
        sut.completeTreatment(acceptedAt, acceptedAt + 1000, 0.0, false)
        sut.acknowledgeTreatment(IobTotal(acceptedAt + 60_000),
            MealData(carbInputs = listOf(TreatmentInputStamp(acceptedAt, 18.0, acceptedAt + 3000))))
        assertEquals(entered, sut.pendingTreatment())
    }

    @Test fun combinedTreatmentWaitsForCarbsEvenAfterBolusWasIncluded() {
        val sut = service()
        sut.beginTreatment(pending.copy(carbsTimestamp = acceptedAt, carbs = 13.0))
        sut.completeTreatment(acceptedAt, acceptedAt + 1000, 0.7, true)
        val iob = IobTotal(acceptedAt + 60_000).apply {
            bolusInputs = listOf(TreatmentInputStamp(acceptedAt + 1000, 0.7, acceptedAt + 2000))
        }
        sut.acknowledgeTreatment(iob, MealData())
        assertNotNull(service().pendingTreatment())
        sut.acknowledgeTreatment(iob, MealData(carbInputs = listOf(TreatmentInputStamp(acceptedAt, 13.0, acceptedAt + 3000))))
        assertNull(sut.pendingTreatment())
    }

    @Test fun unrelatedCallbackAndRejectionCannotClearCurrentTreatment() {
        val sut = service()
        sut.beginTreatment(pending)
        sut.completeTreatment(acceptedAt - 1, acceptedAt, 0.0, true)
        sut.rejectTreatment(acceptedAt - 1)
        assertEquals(pending, sut.pendingTreatment())
        sut.rejectTreatment(acceptedAt)
        assertNull(sut.pendingTreatment())
    }

    @Test fun corruptPersistedStateIsDroppedRatherThanKeptAsAGate() {
        service().beginTreatment(pending)
        values[values.keys.single()] = "not-json"
        val restarted = service()
        assertNull(restarted.pendingTreatment())
        assertTrue(values.isEmpty(), "the unreadable entry is removed")
        assertTrue(restarted.beginTreatment(pending))
        assertEquals(pending, restarted.pendingTreatment())
    }

    @Test fun explicitReviewSurvivesRestartAndStillRequiresNewCalculation() {
        val sut = service()
        sut.beginTreatment(pending)
        assertFalse(sut.confirmManualTreatmentReview(acceptedAt + 1, acceptedAt + 600_000))
        assertFalse(sut.confirmManualTreatmentReview(acceptedAt, acceptedAt + 1))
        val reviewedAt = acceptedAt + 600_000
        assertTrue(sut.confirmManualTreatmentReview(acceptedAt, reviewedAt))
        val restarted = service()
        restarted.acknowledgeTreatment(IobTotal(reviewedAt - 1), MealData())
        assertNotNull(restarted.pendingTreatment())
        restarted.acknowledgeTreatment(IobTotal(reviewedAt), null)
        assertNotNull(restarted.pendingTreatment())
        restarted.acknowledgeTreatment(IobTotal(reviewedAt), MealData())
        assertNull(restarted.pendingTreatment())
    }
}
