package app.aaps.combobench

import android.app.Activity
import org.junit.Assert.*
import org.junit.Test

class DiscoverabilityResultTest {
    @Test fun acceptsWearResultOk() {
        assertTrue(DiscoverabilityResult.accepted(Activity.RESULT_OK))
    }

    @Test fun acceptsPositiveDiscoverableDuration() {
        listOf(1, 120, 180, 300).forEach { assertTrue(DiscoverabilityResult.accepted(it)) }
    }

    @Test fun doesNotOverrideUserCancellation() {
        assertFalse(DiscoverabilityResult.accepted(Activity.RESULT_CANCELED))
    }

    @Test fun rejectsUnknownNegativeResultAndLaunchFailure() {
        assertFalse(DiscoverabilityResult.accepted(-2))
        assertFalse(DiscoverabilityResult.accepted(Int.MIN_VALUE))
        assertFalse(DiscoverabilityResult.accepted(null))
    }

    @Test fun wearZeroResultAcceptsActualTransitionAfterAllow() {
        assertTrue(DiscoverabilityResult.accepted(0, wearDialog = true, wasDiscoverable = false, isDiscoverable = true))
    }

    @Test fun wearDenialWithoutTransitionRemainsDenial() {
        assertFalse(DiscoverabilityResult.accepted(0, wearDialog = true, wasDiscoverable = false, isDiscoverable = false))
    }

    @Test fun alreadyVisibleDoesNotTurnUserCancellationIntoApproval() {
        assertFalse(DiscoverabilityResult.accepted(0, wearDialog = true, wasDiscoverable = true, isDiscoverable = true))
    }

    @Test fun phoneCancellationIsNotOverriddenByWearWorkaround() {
        assertFalse(DiscoverabilityResult.accepted(0, wearDialog = false, wasDiscoverable = false, isDiscoverable = true))
    }

    @Test fun visibilityDoesNotOverrideUnknownError() {
        assertFalse(DiscoverabilityResult.accepted(-2, wearDialog = true, wasDiscoverable = false, isDiscoverable = true))
    }

    @Test fun visibilityDoesNotOverrideLaunchFailure() {
        assertFalse(DiscoverabilityResult.accepted(null, wearDialog = true, wasDiscoverable = false, isDiscoverable = true))
    }
}
