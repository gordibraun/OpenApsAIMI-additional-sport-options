package app.aaps.core.objects.aps

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AimiMealAssist
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.PendingWizardTreatment
import app.aaps.core.interfaces.aps.TreatmentInputStamp
import app.aaps.core.interfaces.db.PersistenceLayer
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Single
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ApsDecisionSnapshotTest {
    private val now = 10_000_000L

    private fun result(insulin: Double = 0.0, carbs: Int = 0, date: Long = now): APSResult {
        val json = mock<JSONObject>()
        whenever(json.optDouble("insulinReq", Double.NaN)).thenReturn(insulin)
        whenever(json.optDouble("finalForecastInsulinDeficit", Double.NaN)).thenReturn(18.66)
        return mock<APSResult>().also {
            whenever(it.date).thenReturn(date)
            whenever(it.carbsReq).thenReturn(carbs)
            whenever(it.json()).thenReturn(json)
        }
    }

    @Test fun zeroApsRequestDoesNotBecomeLegacyForecastDeficit() {
        val snapshot = ApsDecisionSnapshot.from(result(), now, false)
        assertThat(snapshot.state).isEqualTo(ApsDecisionSnapshot.State.CURRENT)
        assertThat(snapshot.insulin).isEqualTo(0.0)
    }

    @Test fun usesApsRequestAndPreservesRequiredCarbs() {
        val snapshot = ApsDecisionSnapshot.from(result(0.8, 4), now, false)
        assertThat(snapshot.insulin).isEqualTo(0.8)
        assertThat(snapshot.carbs).isEqualTo(4)
        assertThat(snapshot.unverifiedCarbWarning).isFalse()
    }

    @Test fun invalidInsulinIsNotPresentedAsZeroOrAValidDose() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0).forEach {
            assertThat(ApsDecisionSnapshot.from(result(it), now, false).insulin).isNull()
        }
    }

    @Test fun pendingHidesBothOldInsulinAndOldCarbs() {
        val snapshot = ApsDecisionSnapshot.from(result(1.0, 4), now, true)
        assertThat(snapshot.state).isEqualTo(ApsDecisionSnapshot.State.WAITING_FOR_TREATMENT)
        assertThat(snapshot.insulin).isNull()
        assertThat(snapshot.carbs).isNull()
        assertThat(snapshot.unverifiedCarbWarning).isTrue()
        assertThat(snapshot.requirementLabel).isEqualTo("Углеводы")
    }

    @Test fun elapsedTimeRequiresReviewAndNeverUnlocksPendingTreatment() {
        val snapshot = ApsDecisionSnapshot.from(result(1.0), now, true, true)
        assertThat(snapshot.state).isEqualTo(ApsDecisionSnapshot.State.REVIEW_DELIVERY)
        assertThat(snapshot.insulin).isNull()
        assertThat(snapshot.unverifiedCarbWarning).isFalse()
        assertThat(snapshot.requirementLabel).isEqualTo("Доза: н/д")
    }

    @Test fun futureMissingAndOldDatesAreNotCurrent() {
        listOf(0L, now + 1, now - 15 * 60_000 - 1).forEach {
            val snapshot = ApsDecisionSnapshot.from(result(date = it), now, false)
            assertThat(snapshot.state).isEqualTo(ApsDecisionSnapshot.State.STALE)
            assertThat(snapshot.insulin).isNull()
        }
        assertThat(ApsDecisionSnapshot.from(null, now, false).state).isEqualTo(ApsDecisionSnapshot.State.UNAVAILABLE)
    }

    @Test fun futureMealTimestampDoesNotMeanFutureDatabaseChange() {
        assertThat(ApsDecisionSnapshot.treatmentChangedAt(now + 60_000, now - 100, now)).isEqualTo(now - 100)
        assertThat(ApsDecisionSnapshot.treatmentChangedAt(now + 60_000, -1, now)).isEqualTo(0L)
        assertThat(ApsDecisionSnapshot.treatmentChangedAt(now - 100, -1, now)).isEqualTo(now - 100)
    }

    @Test fun newerCompletionTimeCannotHideTreatmentMissingFromInputs() {
        val result = result(0.8)
        whenever(result.iobData).thenReturn(arrayOf(IobTotal(now - 20_000)))
        val loop = mock<Loop>()
        whenever(loop.lastRun).thenReturn(Loop.LastRun().apply {
            constraintsProcessed = result
            lastAPSRun = now
        })
        val persistence = mock<PersistenceLayer>()
        whenever(persistence.getTherapyEventDataFromToTime(any(), any())).thenReturn(Single.just(emptyList()))
        whenever(persistence.getNewestBolus()).thenReturn(BS(
            timestamp = now - 10_000, dateCreated = now - 5_000, amount = 0.7, type = BS.Type.NORMAL
        ))
        val snapshot = ApsDecisionSnapshot.fromLoop(loop, mock(), persistence, now)
        assertThat(snapshot.state).isEqualTo(ApsDecisionSnapshot.State.WAITING_FOR_TREATMENT)
        assertThat(snapshot.insulin).isNull()
    }

    @Test fun persistedUnresolvedTreatmentRemainsBlockedAfterNewCalculation() {
        val loop = mock<Loop>()
        val lastRun = Loop.LastRun().apply { constraintsProcessed = result(0.8) }
        whenever(loop.lastRun).thenReturn(lastRun)
        val assist = mock<AimiMealAssist>()
        whenever(assist.pendingTreatment()).thenReturn(PendingWizardTreatment(now - 600_000, now - 600_000, 0.7, 0, 0.0))
        val persistence = mock<PersistenceLayer>()
        whenever(persistence.getTherapyEventDataFromToTime(any(), any())).thenReturn(Single.just(emptyList()))
        assertThat(ApsDecisionSnapshot.fromLoop(loop, assist, persistence, now).state)
            .isEqualTo(ApsDecisionSnapshot.State.REVIEW_DELIVERY)
    }

    @Test fun freshCarbWarningSurvivesInsulinPendingAndDeliveryReview() {
        listOf(false, true).forEach { review ->
            val snapshot = ApsDecisionSnapshot.from(result(1.0, 4), now, pending = true, reviewDelivery = review, carbsInputsCurrent = true)
            assertThat(snapshot.insulin).isNull()
            assertThat(snapshot.carbs).isEqualTo(4)
            assertThat(snapshot.unverifiedCarbWarning).isFalse()
            assertThat(snapshot.state).isEqualTo(if (review) ApsDecisionSnapshot.State.REVIEW_DELIVERY else ApsDecisionSnapshot.State.WAITING_FOR_TREATMENT)
        }
    }

    @Test fun staleOrFutureCarbWarningIsNeverShownEvenWhenInputsMatch() {
        listOf(0L, now + 1, now - 15 * 60_000 - 1).forEach { date ->
            listOf(false, true).forEach { inputsCurrent ->
                val snapshot = ApsDecisionSnapshot.from(result(carbs = 4, date = date), now, true, true, inputsCurrent)
                assertThat(snapshot.carbs).isNull()
                assertThat(snapshot.unverifiedCarbWarning).isFalse()
            }
        }
        assertThat(ApsDecisionSnapshot.from(null, now, true, true).unverifiedCarbWarning).isFalse()
    }

    @Test fun newerCalculationCanShowCarbsWhileInsulinReceiptIsStillUnknown() {
        val pending = PendingWizardTreatment(now - 600_000, now - 600_000, 0.7, 0, 0.0)
        val snapshot = snapshotWithPendingCarbs(pending, MealData())
        assertThat(snapshot.state).isEqualTo(ApsDecisionSnapshot.State.REVIEW_DELIVERY)
        assertThat(snapshot.insulin).isNull()
        assertThat(snapshot.carbs).isEqualTo(4)
    }

    @Test fun acceptedCarbsMustBeInCalculationBeforeShowingExactGrams() {
        val pending = PendingWizardTreatment(now - 600_000, now - 600_000, 0.7, now - 600_000, 13.0)
        val missingInputs = snapshotWithPendingCarbs(pending, MealData())
        assertThat(missingInputs.carbs).isNull()
        assertThat(missingInputs.unverifiedCarbWarning).isTrue()
        assertThat(missingInputs.requirementLabel).isEqualTo("Углеводы")
        assertThat(missingInputs.requirementExplanation).contains("Количество не подтверждено")
        assertThat(missingInputs.pendingTreatment).isEqualTo(pending)
        val meal = MealData(carbInputs = listOf(TreatmentInputStamp(pending.carbsTimestamp, 13.0, pending.acceptedAt + 1000)))
        val snapshot = snapshotWithPendingCarbs(pending, meal)
        assertThat(snapshot.carbs).isEqualTo(4)
        assertThat(snapshot.unverifiedCarbWarning).isFalse()
        assertThat(snapshot.insulin).isNull()
    }

    @Test fun reviewNamesTheUnresolvedRequestInsteadOfImplyingBackgroundProgress() {
        val pending = PendingWizardTreatment(now - 600_000, now - 600_000, 1.4, now - 600_000, 18.0)
        val snapshot = snapshotWithPendingCarbs(pending, MealData())
        assertThat(snapshot.pendingTreatment).isEqualTo(pending)
        assertThat(snapshot.statusLabel).isEqualTo("Сверка")
        assertThat(snapshot.explanation).contains("1.4 Е, 18.0 г")
        assertThat(snapshot.explanation).contains("Это не продолжающееся обновление")
        assertThat(snapshot.explanation).contains("Не подтверждайте сверку")
        assertThat(snapshot.explanation).contains("«Сверить предыдущую дозу»")
        assertThat(snapshot.insulin).isNull()
    }

    @Test fun completedReviewWaitsForCalculationWithoutAskingForAnotherReview() {
        val pending = PendingWizardTreatment(now - 600_000, now - 600_000, 1.4, now - 600_000, 18.0,
            manuallyReviewedAt = now - 500)
        val snapshot = snapshotWithPendingCarbs(pending, MealData())
        assertThat(snapshot.state).isEqualTo(ApsDecisionSnapshot.State.WAITING_FOR_TREATMENT)
        assertThat(snapshot.explanation).contains("Сверка завершена")
        assertThat(snapshot.explanation).contains("Повторять сверку не нужно")
        assertThat(snapshot.pendingTreatment).isEqualTo(pending)
        assertThat(snapshot.insulin).isNull()
    }

    private fun snapshotWithPendingCarbs(pending: PendingWizardTreatment, meal: MealData): ApsDecisionSnapshot {
        val result = result(0.8, 4)
        whenever(result.iobData).thenReturn(arrayOf(IobTotal(now - 1000)))
        whenever(result.mealData).thenReturn(meal)
        val loop = mock<Loop>()
        whenever(loop.lastRun).thenReturn(Loop.LastRun().apply { constraintsProcessed = result })
        val assist = mock<AimiMealAssist>()
        whenever(assist.pendingTreatment()).thenReturn(pending)
        whenever(assist.lastTreatmentAcceptedAt()).thenReturn(pending.acceptedAt)
        val persistence = mock<PersistenceLayer>()
        whenever(persistence.getTherapyEventDataFromToTime(any(), any())).thenReturn(Single.just(emptyList()))
        return ApsDecisionSnapshot.fromLoop(loop, assist, persistence, now)
    }
}
