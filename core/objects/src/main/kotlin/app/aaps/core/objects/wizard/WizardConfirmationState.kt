package app.aaps.core.objects.wizard

import java.util.concurrent.atomic.AtomicBoolean

data class WizardCalculationInputs(
    val historyIds: List<Long?>,
    val profile: String,
    val profileValues: List<Double>,
    val apsRun: Long,
    val target: String?,
    val lastAcceptedTreatment: Long
)

/** A changed input invalidates the displayed dose; it must never be silently recalculated and sent. */
class WizardConfirmationState {
    enum class Rejection { MISSING_INPUTS, PENDING_TREATMENT, CHANGED_INPUTS, EXPIRED, CLOCK_CHANGED, ALREADY_SUBMITTED }

    private val submitted = AtomicBoolean(false)

    fun rejection(calculatedInputs: WizardCalculationInputs?, currentInputs: WizardCalculationInputs?, pending: Boolean, calculatedAt: Long, now: Long): Rejection? = when {
        submitted.get() -> Rejection.ALREADY_SUBMITTED
        pending -> Rejection.PENDING_TREATMENT
        calculatedInputs == null || currentInputs == null -> Rejection.MISSING_INPUTS
        calculatedInputs != currentInputs -> Rejection.CHANGED_INPUTS
        now < calculatedAt -> Rejection.CLOCK_CHANGED
        now - calculatedAt > 5 * 60_000L -> Rejection.EXPIRED
        else -> null
    }

    fun claimCarbsOnly(carbs: Int, calculatedInsulin: Double, constrainedInsulin: Double): Boolean =
        carbs > 0 && calculatedInsulin == 0.0 && constrainedInsulin == 0.0 && submitted.compareAndSet(false, true)

    fun claim(calculatedInputs: WizardCalculationInputs?, currentInputs: WizardCalculationInputs?, pending: Boolean, calculatedAt: Long, now: Long): Boolean =
        rejection(calculatedInputs, currentInputs, pending, calculatedAt, now) == null && submitted.compareAndSet(false, true)
}
