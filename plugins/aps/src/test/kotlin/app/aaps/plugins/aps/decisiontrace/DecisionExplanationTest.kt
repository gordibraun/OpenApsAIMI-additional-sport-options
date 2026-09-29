package app.aaps.plugins.aps.decisiontrace

import app.aaps.core.interfaces.aps.DecisionBranch
import app.aaps.core.interfaces.aps.DecisionStage
import app.aaps.core.interfaces.aps.DecisionTraceStep
import app.aaps.core.interfaces.aps.DecisionValue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DecisionExplanationTest {
    private fun dose(seq: Int, before: String?, after: String, name: String = "SMB", unit: String = "Е",
                     stage: DecisionStage = DecisionStage.SMB, title: String = "Изменение", detail: String = "") =
        DecisionTraceStep(seq, stage, title, detail, values = listOf(DecisionValue(name, after, before, unit)))
    private fun final(seq: Int, smb: String = "0.0", basal: String = "1.1") =
        DecisionTraceStep(seq, DecisionStage.FINAL, "Итог", "", values = listOf(
            DecisionValue("SMB", smb, unit = "Е"), DecisionValue("Базал", basal, unit = "Е/ч")),
            branch = DecisionBranch("final", "basal"))
    private fun branch(seq: Int, id: String, outcome: String) = DecisionTraceStep(seq, DecisionStage.DELIVERY,
        id, "Записанное условие", branch = DecisionBranch(id, outcome))
    private fun story(vararg steps: DecisionTraceStep, context: DecisionContext? = null) =
        DecisionExplanation.from(TraceRun(100, steps.toList(), TraceRunState.CALCULATED, context))

    @Test fun breakfastChangesKeepNumericOrderAndBindingReasonWithoutDoubleCounting() {
        val result = story(dose(1, null, "0.3996"), dose(2, "0.3996", "1.975"),
            dose(3, "0.3996", "1.975", name = "Запрос микродозы"),
            dose(4, "1.975", "2.07375"), dose(5, "2.07375", "1.770083"),
            dose(6, "1.770083", "1.75"), dose(7, "1.75", "0.0"),
            dose(8, "1.75", "0.0", title = "Ограничение микродозы", detail = "Прогноз 94 при цели 117"), final(9))
        assertEquals(listOf(0.3996, 1.975, 2.07375, 1.770083, 1.75, 0.0), result.changes.map { it.after })
        assertEquals("Прогноз 94 при цели 117", result.changes.last().reason)
        assertTrue(result.changes.last().stopped)
        assertTrue(result.changes.none { it.gapBefore })
    }

    @Test fun onlyBasalReductionIsNotCalledAnSmbCancellation() {
        val result = story(dose(1, null, "0.0"),
            dose(2, "2.64", "1.1", "Временный базал", "Е/ч", DecisionStage.SAFETY), final(3))
        assertFalse(result.changes.any { it.stopped })
        assertTrue(result.basalChanges.single().reduced)
        assertEquals("Почему микродоза не запрошена", result.title)
    }

    @Test fun finalForecastCheckIsAttachedOnlyToItsRecordedVariantChange() {
        val check = DecisionTraceStep(1, DecisionStage.FORECAST, "Проверка", "Минимум=46; порог=107",
            branch = DecisionBranch("final_forecast", "reduce"))
        val result = story(check, dose(2, "2.64", "1.1", "Временный базал", "Е/ч", DecisionStage.SAFETY,
            title = "Выбран вариант по прогнозу"), final(3))
        assertEquals(check.detail, result.basalChanges.single().reason)
    }

    @Test fun unrecordedTransitionIsFlaggedInsteadOfInventingACause() {
        val result = story(dose(1, null, "0.4"), dose(2, "1.7", "0.0"), final(3))
        assertTrue(result.changes.last().gapBefore)
    }

    @Test fun passFloatingNoiseAndCandidateDoseAreNotRealChanges() {
        val result = story(dose(1, null, "0.4"), dose(2, "0.4", "0.40000002"),
            dose(3, "0.4", "0.0", name = "Микродоза-кандидат"), final(4, "0.4"))
        assertEquals(1, result.changes.size)
        assertEquals(0.4, result.smb)
    }

    @Test fun forecastDataNeverBecomesADoseOrExplanationOfAHistoricalGuard() {
        val result = story(dose(1, null, "0.0"), final(2), context = DecisionContext(forecast = listOf(130, 250, 40)))
        assertFalse(result.changes.any { it.stopped })
        assertTrue(result.blockers.isEmpty())
        assertEquals(0.0, result.smb)
    }

    @Test fun requestAndSuccessfulTransportDoNotBecomeConfirmedDelivery() {
        assertTrue(story(final(1, "0.5"), branch(2, "delivery_smb", "requested")).delivery.contains("Подтверждения"))
        assertTrue(story(final(1), branch(2, "delivery_result", "accepted")).delivery.contains("не подтверждён"))
        assertTrue(story(final(1), branch(2, "delivery_result", "failed")).delivery.contains("не подтвердила"))
        assertTrue(story(final(1), branch(2, "delivery_result", "success")).delivery.contains("подтвердила введение"))
    }

    @Test fun laterConstraintsWarnThatChartBelongsToOriginalProposal() {
        val result = story(final(1, "0.5"), dose(2, "0.5", "0.0", stage = DecisionStage.CONSTRAINTS),
            context = DecisionContext(forecast = listOf(140, 120), forecastSmb = 0.5, forecastBasal = 1.1))
        assertTrue(result.forecastDiffersFromRequest)
        assertEquals(0.0, result.smb)
    }

    @Test fun missingFailedAndInProgressResultsAreNotZeroRecommendations() {
        val context = DecisionContext(forecast = listOf(140, 120), forecastSmb = 0.5)
        for (state in TraceRunState.entries) {
            val result = DecisionExplanation.from(TraceRun(100, listOf(dose(1, null, "0.5")), state, context))
            assertNull(result.smb)
            assertNull(result.basal)
            assertTrue(result.context.forecast.isEmpty())
        }
        assertTrue(story().changes.isEmpty())
        assertNull(story().smb)
    }

    @Test fun capsBasalPercentagesAndInsulinOnBoardAreNotBolusDeltas() {
        val result = story(dose(1, "0.4", "0.2", name = "Потолок"),
            dose(2, "110", "0", name = "Базал в процентах", unit = "%", stage = DecisionStage.CONSTRAINTS),
            dose(3, "1", "2", name = "Активный инсулин", stage = DecisionStage.INSULIN), final(4))
        assertEquals(1, result.changes.size)
        assertFalse(result.changes.single().stopped)
        assertEquals(1, result.basalChanges.size)
    }

    @Test fun contextEnrichmentDoesNotMixRunsOrReplaceFrozenHistory() {
        val history = DecisionTraceHistory()
        history.merge(100, listOf(final(1)))
        val old = history.snapshots().single()
        val context = DecisionContext(bg = 130.0, forecast = listOf(130, 94))
        history.merge(100, listOf(final(1)), context)
        history.merge(200, listOf(final(1)), DecisionContext(bg = 160.0))
        assertNull(old.context)
        assertEquals(context, history.snapshots().last().context)
        assertEquals(160.0, history.snapshots().first().context?.bg)
        history.merge(100, listOf(dose(1, null, "99")), DecisionContext(bg = 999.0))
        assertEquals(context, history.snapshots().last().context)
    }

    @Test fun invalidNumbersAreNotConvertedToZero() {
        val result = story(dose(1, "0.5", "NaN"), dose(2, "NaN", "0.0"))
        assertTrue(result.changes.isEmpty())
        assertNull(result.smb)
    }

    @Test fun legacySafetyReasonIsTranslatedWithoutInventingADifferentThreshold() {
        val source = "\uD83D\uDED1 Safety condition ✔ : BG<90 → SMB=0\n\u2705 Final SMB: 0,00 U"
        val result = readableDecisionReason(source)
        assertEquals("Сработало условие защиты : Глюкоза ниже 90 мг/дл: микродоза отменена\nИтоговая микродоза: 0,00 Е", result)
        assertTrue(source.contains("BG<90"))
        assertEquals("Условие защиты false : Глюкоза ниже 100", readableDecisionReason("Safety condition false : Глюкоза ниже 100"))
        assertEquals("Поправки: 0,05 → 0,03 Е", readableDecisionReason("\uD83C\uDF9B\uFE0F Adjustments: 0,05 → 0,03 U"))
    }

    @Test fun failedFinalBranchDoesNotLookLikeACompletedZeroDose() {
        val failed = final(1).copy(branch = DecisionBranch("final", "error"))
        val result = story(failed, context = DecisionContext(forecast = listOf(100, 110)))
        assertEquals("Ошибка расчёта", result.title)
        assertNull(result.smb)
        assertTrue(result.context.forecast.isEmpty())
    }
}
