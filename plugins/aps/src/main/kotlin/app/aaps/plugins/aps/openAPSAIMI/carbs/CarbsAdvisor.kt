package app.aaps.plugins.aps.openAPSAIMI.carbs

import kotlin.math.ceil

object CarbsAdvisor {
    /**
     * Estime la quantité de glucides à consommer pour éviter une hypo à court terme.
     * Logique identique à ton helper existant.
     */
    fun estimateRequiredCarbs(
        bg: Double,
        targetBG: Double,
        slope: Double,
        iob: Double,
        csf: Double,
        isf: Double,
        cob: Double
    ): Int {
        val timeAhead = 20.0
        val projectedDrop = slope * timeAhead
        val insulinEffect = iob * isf
        val totalPredictedDrop = projectedDrop + insulinEffect
        val futureBG = bg - totalPredictedDrop
        if (futureBG < targetBG) {
            val bgDiff = targetBG - futureBG
            val gramsNeeded = (bgDiff / csf) - (cob * 0.2)
            return ceil(kotlin.math.max(0.0, gramsNeeded)).toInt()
        }
        return 0
    }

    fun estimatePressureRescueCarbs(
        bg: Double,
        targetBG: Double,
        eventualBG: Double,
        minPredictedBG: Double,
        iob: Double,
        csf: Double,
        isf: Double,
        cob: Double,
        recentSmb30: Double,
        nightNoMeal: Boolean
    ): Int {
        if (csf <= 0.0 || isf <= 0.0) return 0
        val forecastFloor = listOf(bg, eventualBG, minPredictedBG)
            .filter { it.isFinite() && it > 0.0 }
            .minOrNull() ?: return 0
        val insulinPressureMgdl = iob.coerceAtLeast(0.0) * isf * 0.20 +
            recentSmb30.coerceAtLeast(0.0) * isf * 0.35
        val pressuredFloor = forecastFloor - insulinPressureMgdl
        val pressureActive = recentSmb30 >= 0.8 ||
            iob >= 2.0 ||
            (nightNoMeal && iob >= 1.5)
        if (!pressureActive) return 0

        val bufferMgdl = when {
            recentSmb30 >= 1.5 -> 10.0
            nightNoMeal -> 8.0
            else -> 5.0
        }
        val desiredFloor = targetBG + bufferMgdl
        if (pressuredFloor >= desiredFloor) return 0

        val gramsNeeded = ((desiredFloor - pressuredFloor) / csf) - (cob.coerceAtLeast(0.0) * 0.15)
        return ceil(gramsNeeded).toInt().coerceAtLeast(0)
    }
}
