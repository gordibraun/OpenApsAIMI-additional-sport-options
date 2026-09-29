package app.aaps.core.interfaces.aps

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** A database treatment actually consumed by the calculation, not just seen by a later DB query. */
@Serializable
data class TreatmentInputStamp(val timestamp: Long, val amount: Double, val createdAt: Long)

@Serializable
data class PendingWizardTreatment(
    val acceptedAt: Long,
    val bolusTimestamp: Long,
    val insulin: Double,
    val carbsTimestamp: Long,
    val carbs: Double,
    val deliveryConfirmed: Boolean = false,
    val manuallyReviewedAt: Long? = null
) {
    fun includedIn(iob: IobTotal?, meal: MealData?): Boolean {
        manuallyReviewedAt?.let { reviewedAt ->
            // The operator has reconciled pump history and DB entries, but old calculations remain invalid.
            return reviewedAt > acceptedAt && (iob?.time ?: 0L) >= reviewedAt && meal != null
        }
        if (!deliveryConfirmed) return false
        return (insulin <= 0.0 || matches(iob?.bolusInputs, bolusTimestamp, insulin)) &&
            carbsIncludedIn(meal)
    }

    fun carbsIncludedIn(meal: MealData?): Boolean =
        carbs <= 0.0 || matches(meal?.carbInputs, carbsTimestamp, carbs)

    private fun matches(inputs: List<TreatmentInputStamp>?, timestamp: Long, amount: Double): Boolean =
        inputs?.any { it.timestamp == timestamp && it.createdAt >= acceptedAt && abs(it.amount - amount) < 0.001 } == true
}
