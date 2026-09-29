package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.core.interfaces.aps.MealData

internal object MealDeviationInput {
    fun normalize(input: MealData): MealData = input.copy(
        // Autosens uses 999 as its uninitialized minimum, not as an observed rising trend.
        slopeFromMinDeviation = input.slopeFromMinDeviation.takeIf { it.isFinite() && it != 999.0 } ?: 0.0,
        slopeFromMaxDeviation = input.slopeFromMaxDeviation.takeIf { it.isFinite() } ?: 0.0
    )
}
