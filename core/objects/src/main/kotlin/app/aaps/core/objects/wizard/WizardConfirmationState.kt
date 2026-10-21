package app.aaps.core.objects.wizard

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * What the displayed dose was computed from; [matches] says whether a later snapshot still
 * describes the same situation.
 *
 * Strict - any change invalidates the dose: the newest bolus, carbs and extended bolus (a treatment
 * filed since the calculation), the temporary target, profile switches, therapy events and the
 * running mode ([historyIds]); the profile itself; the carb ratio, basal and targets (all of
 * [profileValues] but the last entry); the temporary target text; the meal assist's last accepted
 * treatment.
 *
 * Tolerant: the sensitivity (last entry of [profileValues]; dynamic ISF drifts a little between
 * loop runs) within 10 %, and the glucose value within 10 mg/dL.
 *
 * Not compared at all: the glucose record id, temporary basal records and the loop's run counter.
 * With the watch driving the pump those move every minute (each command files a basal record, each
 * record triggers a forecast run) and none of them changes the dose the user is looking at, yet
 * they made the phone refuse nearly every meal confirmed from the watch (21 Oct 2026). Staleness
 * is bounded separately, by [WizardConfirmationState]'s five-minute limit.
 */
data class WizardCalculationInputs(
    val historyIds: List<Long?>,
    val profile: String,
    val profileValues: List<Double>,
    val glucoseMgdl: Double?,
    val target: String?,
    val lastAcceptedTreatment: Long
) {

    fun matches(other: WizardCalculationInputs): Boolean = changedFields(other).isEmpty()

    /** The inputs that differ beyond tolerance, by name, for the rejection log. */
    fun changedFields(other: WizardCalculationInputs): List<String> = buildList {
        if (historyIds != other.historyIds) add("history")
        if (profile != other.profile) add("profile")
        val n = profileValues.size
        if (n != other.profileValues.size) add("profileValues")
        else if ((0 until n - 1).any { profileValues[it] != other.profileValues[it] }) add("profileValues")
        else if (n > 0 && !within(profileValues[n - 1], other.profileValues[n - 1], SENSITIVITY_TOLERANCE)) add("sensitivity")
        if (!glucoseClose(glucoseMgdl, other.glucoseMgdl)) add("glucose")
        if (target != other.target) add("target")
        if (lastAcceptedTreatment != other.lastAcceptedTreatment) add("lastAcceptedTreatment")
    }

    private fun within(a: Double, b: Double, fraction: Double): Boolean =
        a.isFinite() && b.isFinite() && (a == b || abs(a - b) <= fraction * maxOf(abs(a), abs(b)))

    private fun glucoseClose(a: Double?, b: Double?): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else                   -> a.isFinite() && b.isFinite() && abs(a - b) <= GLUCOSE_TOLERANCE_MGDL
    }

    companion object {
        const val SENSITIVITY_TOLERANCE = 0.10
        const val GLUCOSE_TOLERANCE_MGDL = 10.0
    }
}

/** A changed input invalidates the displayed dose; it must never be silently recalculated and sent. */
class WizardConfirmationState {
    enum class Rejection { MISSING_INPUTS, PENDING_TREATMENT, CHANGED_INPUTS, EXPIRED, CLOCK_CHANGED, ALREADY_SUBMITTED }

    private val submitted = AtomicBoolean(false)

    fun rejection(calculatedInputs: WizardCalculationInputs?, currentInputs: WizardCalculationInputs?, pending: Boolean, calculatedAt: Long, now: Long): Rejection? = when {
        submitted.get() -> Rejection.ALREADY_SUBMITTED
        pending -> Rejection.PENDING_TREATMENT
        calculatedInputs == null || currentInputs == null -> Rejection.MISSING_INPUTS
        !calculatedInputs.matches(currentInputs) -> Rejection.CHANGED_INPUTS
        now < calculatedAt -> Rejection.CLOCK_CHANGED
        now - calculatedAt > 5 * 60_000L -> Rejection.EXPIRED
        else -> null
    }

    fun claimCarbsOnly(carbs: Int, calculatedInsulin: Double, constrainedInsulin: Double): Boolean =
        carbs > 0 && calculatedInsulin == 0.0 && constrainedInsulin == 0.0 && submitted.compareAndSet(false, true)

    fun claim(calculatedInputs: WizardCalculationInputs?, currentInputs: WizardCalculationInputs?, pending: Boolean, calculatedAt: Long, now: Long): Boolean =
        rejection(calculatedInputs, currentInputs, pending, calculatedAt, now) == null && submitted.compareAndSet(false, true)
}
