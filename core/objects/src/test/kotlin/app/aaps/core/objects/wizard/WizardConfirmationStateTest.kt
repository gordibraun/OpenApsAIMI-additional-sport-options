package app.aaps.core.objects.wizard

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.IDs
import app.aaps.core.data.model.TE
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WizardConfirmationStateTest {
    private val now = 1_000_000L
    // profileValues: carb ratio, basal, target, and last the sensitivity (the one compared with tolerance).
    private val inputs = WizardCalculationInputs(listOf(1L, 2L), "profile", listOf(8.0, 1.0, 117.0, 60.0), 100.0, null, 0L)

    private fun event(id: Long, type: TE.Type, pumpSerial: String? = null, version: Int = 0, valid: Boolean = true) =
        TE(id = id, version = version, isValid = valid, ids = IDs(pumpSerial = pumpSerial), timestamp = now, type = type, glucoseUnit = GlucoseUnit.MGDL)

    // What the watch's driver answers: its own notes narrate, unless they speak of a dose (here: id 13).
    private val watchNarrates: (TE) -> Boolean = { it.type == TE.Type.NOTE && it.ids.pumpSerial != null && it.id != 13L }
    private val recordsAtCalculation = listOf(event(10, TE.Type.EXERCISE, pumpSerial = "PUMP"), event(11, TE.Type.NOTE))

    @Test fun aNoteThatOnlyNarratesDoesNotRefuseTheConfirmedDose() {
        // 15 Nov 2026 21:13: a note landed three seconds into a meal calculation; the 3.7 U confirmed on the watch were refused.
        val atConfirmation = recordsAtCalculation + event(12, TE.Type.NOTE, pumpSerial = "PUMP")
        val calculated = inputs.copy(historyIds = listOf(1L, 2L) + therapyEventsThatCount(recordsAtCalculation, watchNarrates))
        val current = inputs.copy(historyIds = listOf(1L, 2L) + therapyEventsThatCount(atConfirmation, watchNarrates))
        assertThat(WizardConfirmationState().claim(calculated, current, false, now, now)).isTrue()
        // Without a driver that says so, any note counts, as it always did.
        assertThat(therapyEventsThatCount(atConfirmation)).isNotEqualTo(therapyEventsThatCount(recordsAtCalculation))
    }

    @Test fun everyOtherTherapyEventStillRefusesTheDose() {
        val before = therapyEventsThatCount(recordsAtCalculation, watchNarrates)
        listOf(
            recordsAtCalculation + event(13, TE.Type.NOTE, pumpSerial = "PUMP"),      // the watch's note about a dose
            recordsAtCalculation + event(12, TE.Type.NOTE),                            // a note typed by hand
            recordsAtCalculation + event(12, TE.Type.EXERCISE, pumpSerial = "PUMP"),   // an activity the watch reported
            recordsAtCalculation + event(12, TE.Type.ANNOUNCEMENT),                    // an unclear bolus is announced this way
            listOf(event(10, TE.Type.EXERCISE, pumpSerial = "PUMP", version = 1), event(11, TE.Type.NOTE)),   // edited
            listOf(event(10, TE.Type.EXERCISE, pumpSerial = "PUMP", valid = false), event(11, TE.Type.NOTE)), // removed
            listOf(event(11, TE.Type.NOTE))
        ).forEach { assertThat(therapyEventsThatCount(it, watchNarrates)).isNotEqualTo(before) }
        // The order the records are read in does not matter.
        assertThat(therapyEventsThatCount(recordsAtCalculation.reversed(), watchNarrates)).isEqualTo(before)
    }

    @Test fun unchangedCalculationCanBeSubmittedOnlyOnce() {
        val state = WizardConfirmationState()
        assertThat(state.claim(inputs, inputs, false, now, now)).isTrue()
        assertThat(state.claim(inputs, inputs, false, now, now)).isFalse()
    }

    @Test fun carbsOnlyDoesNotRequireInsulinInputsAndCannotBeDuplicated() {
        val state = WizardConfirmationState()
        assertThat(state.claim(null, null, true, now, now + 600_000)).isFalse()
        assertThat(state.claimCarbsOnly(13, 0.0, 0.0)).isTrue()
        assertThat(state.claimCarbsOnly(13, 0.0, 0.0)).isFalse()
        assertThat(state.claim(inputs, inputs, false, now, now)).isFalse()
    }

    @Test fun carbsOnlyPathCannotAuthorizeEvenSmallOrInvalidInsulin() {
        listOf(0.01, 1.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { insulin ->
            assertThat(WizardConfirmationState().claimCarbsOnly(13, insulin, 0.0)).isFalse()
            assertThat(WizardConfirmationState().claimCarbsOnly(13, 0.0, insulin)).isFalse()
        }
        assertThat(WizardConfirmationState().claimCarbsOnly(0, 0.0, 0.0)).isFalse()
    }

    @Test fun missingOrPendingInputsRejectSubmission() {
        val state = WizardConfirmationState()
        assertThat(state.claim(null, inputs, false, now, now)).isFalse()
        assertThat(state.claim(inputs, null, false, now, now)).isFalse()
        assertThat(state.claim(inputs, inputs, true, now, now)).isFalse()
    }

    @Test fun databaseFailureAndRealHistoryChangesHaveDifferentReasons() {
        val state = WizardConfirmationState()
        assertThat(state.rejection(null, inputs, false, now, now)).isEqualTo(WizardConfirmationState.Rejection.MISSING_INPUTS)
        assertThat(state.rejection(inputs, inputs.copy(historyIds = listOf(1L, 3L)), false, now, now)).isEqualTo(WizardConfirmationState.Rejection.CHANGED_INPUTS)
        assertThat(state.rejection(inputs, inputs, true, now, now)).isEqualTo(WizardConfirmationState.Rejection.PENDING_TREATMENT)
        assertThat(state.rejection(inputs, inputs, false, now, now)).isNull()
    }

    @Test fun everyTrackedChangeInvalidatesTheDisplayedDose() {
        listOf(
            inputs.copy(historyIds = listOf(1, 3)),
            inputs.copy(profile = "another profile"),
            inputs.copy(profileValues = listOf(8.0, 0.8, 117.0, 60.0)),
            inputs.copy(profileValues = listOf(8.0, 1.0, 117.0, 70.0)),   // sensitivity off by more than 10 %
            inputs.copy(glucoseMgdl = 125.0),                              // glucose moved by more than 10 mg/dL
            inputs.copy(glucoseMgdl = null),
            inputs.copy(target = "temporary"), inputs.copy(lastAcceptedTreatment = 1)
        ).forEach { assertThat(WizardConfirmationState().claim(inputs, it, false, now, now)).isFalse() }
    }

    @Test fun smallDriftOfSensitivityOrGlucoseKeepsTheDisplayedDoseValid() {
        // Dynamic ISF drifts between loop runs and a new reading arrives every five minutes; neither
        // changes the dose the user is looking at within the five-minute confirmation window.
        listOf(
            inputs.copy(profileValues = listOf(8.0, 1.0, 117.0, 63.0)),
            inputs.copy(profileValues = listOf(8.0, 1.0, 117.0, 57.0)),
            inputs.copy(glucoseMgdl = 108.0),
            inputs.copy(glucoseMgdl = 92.0)
        ).forEach { assertThat(WizardConfirmationState().claim(inputs, it, false, now, now)).isTrue() }
    }

    @Test fun changedFieldsNameWhatMoved() {
        assertThat(inputs.changedFields(inputs)).isEmpty()
        assertThat(inputs.changedFields(inputs.copy(historyIds = listOf(1, 3), glucoseMgdl = 140.0)))
            .containsExactly("history", "glucose").inOrder()
        assertThat(inputs.changedFields(inputs.copy(profileValues = listOf(8.0, 1.0, 117.0, 80.0)))).containsExactly("sensitivity")
    }

    @Test fun staleAndClockRollbackRejectSubmission() {
        assertThat(WizardConfirmationState().claim(inputs, inputs, false, now, now + 300_001)).isFalse()
        assertThat(WizardConfirmationState().claim(inputs, inputs, false, now, now - 1)).isFalse()
    }

    @Test fun historicalSeptember11DelayedHistoryAndOldConfirmationAreRejected() {
        // Relative times from the saved incident, not a simulation of glucose physiology.
        val t145808 = now
        val oldDialog = WizardConfirmationState()
        val firstDialog = WizardConfirmationState()
        assertThat(firstDialog.claim(inputs, inputs, false, t145808, t145808)).isTrue()
        // 14:58:22: calculation finishes without the 14:58:15 delivery in its inputs.
        val missingDose = inputs.copy(lastAcceptedTreatment = t145808)
        assertThat(WizardConfirmationState().claim(missingDose, missingDose, true, t145808 + 14_000, t145808 + 44_000)).isFalse()
        // 14:58:53: new history and IOB arrive; the 14:58:52 dialog cannot send at 14:59:59.
        val includedDose = missingDose.copy(historyIds = listOf(3, 2))
        assertThat(oldDialog.claim(missingDose, includedDose, false, t145808 + 44_000, t145808 + 111_000)).isFalse()
    }
}
