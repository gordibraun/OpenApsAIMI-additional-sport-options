package app.aaps.core.objects.wizard

import app.aaps.core.data.model.TE
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * The therapy events a displayed dose is compared by, for [WizardCalculationInputs.historyIds]: of each its id,
 * its version and whether it is valid, so that a new event, an edit and a removal all show.
 *
 * Left out are the notes the pump driver calls its own narration ([onlyNarrates], see
 * [app.aaps.core.interfaces.pump.PumpNarration]): the watch telling what it noticed is no treatment and nothing
 * the owner typed, and a dose he has just confirmed is not to be refused because such a note landed. Everything
 * else counts as before: activities, notes typed by hand, announcements (an unclear bolus is one), and the
 * driver's notes about insulin.
 *
 * 15 Nov 2026: a note the loop filed about its own decision, three seconds into a calculation, had the phone
 * refuse the 3.7 U confirmed on the watch; the owner dosed on the pump by hand. That note is no longer written;
 * the watch's notes arrive the same way, a dozen a day.
 */
fun therapyEventsThatCount(events: List<TE>, onlyNarrates: (TE) -> Boolean = { false }): List<Long> =
    events.filterNot(onlyNarrates)
        .sortedBy { it.id }
        .flatMap { listOf(it.id, it.version.toLong(), if (it.isValid) 1L else 0L) }

/**
 * What the displayed dose was computed from; [matches] says whether a later snapshot still
 * describes the same situation.
 *
 * Strict - any change invalidates the dose: the newest bolus, carbs and extended bolus (a treatment
 * filed since the calculation), the temporary target, profile switches, the running mode and the
 * therapy events of the last day but the pump driver's own narration, see [therapyEventsThatCount]
 * ([historyIds]); the profile itself; the carb ratio, basal and targets (all of [profileValues] but
 * the last entry); the temporary target text; the meal assist's last accepted treatment.
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

        /** Therapy events are compared this far back from the calculation: a day covers any activity and its tail. */
        const val THERAPY_EVENTS_LOOKBACK_MS = 24 * 3_600_000L
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
