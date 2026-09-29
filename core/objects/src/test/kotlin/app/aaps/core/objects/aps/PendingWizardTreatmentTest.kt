package app.aaps.core.objects.aps

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.PendingWizardTreatment
import app.aaps.core.interfaces.aps.TreatmentInputStamp
import app.aaps.core.objects.extensions.copy
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PendingWizardTreatmentTest {
    private val acceptedAt = 1_000_000L
    private val pending = PendingWizardTreatment(acceptedAt, acceptedAt + 1000, 0.7, 0, 0.0, true)
    private val stamp = TreatmentInputStamp(pending.bolusTimestamp, 0.7, acceptedAt + 2000)
    private fun iob(vararg stamps: TreatmentInputStamp) = IobTotal(acceptedAt + 60_000).apply { bolusInputs = stamps.toList() }

    @Test fun requiresConfirmedDeliveryEvenWithMatchingInput() {
        assertThat(pending.copy(deliveryConfirmed = false).includedIn(iob(stamp), null)).isFalse()
    }

    @Test fun exactDeliveredTreatmentInInputAcknowledgesDelivery() {
        assertThat(pending.includedIn(iob(stamp), null)).isTrue()
    }

    @Test fun newerIobTimestampAloneIsNotEvidenceOfInclusion() {
        assertThat(pending.includedIn(iob(), null)).isFalse()
        assertThat(pending.includedIn(null, null)).isFalse()
    }

    @Test fun wrongAmountTimestampOrOldRecordCannotAcknowledge() {
        listOf(stamp.copy(amount = 0.8), stamp.copy(timestamp = stamp.timestamp + 1), stamp.copy(createdAt = acceptedAt - 1)).forEach {
            assertThat(pending.includedIn(iob(it), null)).isFalse()
        }
    }

    @Test fun bothCarbsAndInsulinMustBeIncludedWhenBothAreTracked() {
        val combined = pending.copy(carbsTimestamp = acceptedAt + 5000, carbs = 13.0)
        val meal = MealData(carbInputs = listOf(TreatmentInputStamp(combined.carbsTimestamp, 13.0, acceptedAt + 6000)))
        assertThat(combined.includedIn(iob(stamp), null)).isFalse()
        assertThat(combined.includedIn(iob(), meal)).isFalse()
        assertThat(combined.includedIn(iob(stamp), meal)).isTrue()
    }

    @Test fun copiedCachedIobRetainsOriginalInputProvenance() {
        assertThat(pending.includedIn(iob(stamp).copy(), null)).isTrue()
        assertThat(pending.includedIn(iob().copy(), null)).isFalse()
    }
}
