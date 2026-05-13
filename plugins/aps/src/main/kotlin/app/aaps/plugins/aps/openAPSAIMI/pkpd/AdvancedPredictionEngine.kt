package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi

/**
 * Provides a unified prediction model that mirrors the same parameters used during SMB/basal decisions.
 */
object AdvancedPredictionEngine {

    /**
     * Predict the BG evolution using the final ISF/sensitivity applied by the decision engine.
     *
     * @param currentBG Current glucose in mg/dL.
     * @param iobArray Active insulin entries.
     * @param finalSensitivity Final ISF after all adjustments.
     * @param cobG Active carbs in grams.
     * @param profile User profile used for insulin timing and carb ratio.
     * @param horizonMinutes Prediction horizon (defaults to 4h).
     */
    fun predict(
        currentBG: Double,
        iobArray: Array<IobTotal>,
        finalSensitivity: Double,
        cobG: Double,
        profile: OapsProfileAimi,
        selectedFoodType: String? = null,
        delta: Double = 0.0, // Innovation: Carb impact awareness
        plannedSmbU: Double = 0.0,
        plannedRateUph: Double? = null,
        profileBasalUph: Double? = null,
        plannedDurationMin: Int = 30,
        mealFactorApplied: Double = 1.0,
        mpcShare: Double = 0.0,
        piShare: Double = 0.0,
        highBgOverrideUsed: Boolean = false,
        safetyMechanism: String? = null,
        observedCarbImpactMgdlPer5m: Double = 0.0,
        remainingCiPeakMgdlPer5m: Double = 0.0,
        rescueFastActive: Boolean = false,
        uamConfidence: Double = 1.0,
        explicitCarbEntry: Boolean = false,
        freshSmbPressureU: Double = 0.0,
        horizonMinutes: Int = 240
    ): List<Double> {
        val predictions = mutableListOf(currentBG)
        if (horizonMinutes <= 0) return predictions

        val steps = maxOf(1, horizonMinutes / 5)
        val now = System.currentTimeMillis()
        val carbRatio = profile.carb_ratio.takeIf { it > 0 } ?: 10.0
        val csf = finalSensitivity / carbRatio
        val totalCarbEffectMgDl = (cobG * csf).coerceAtLeast(0.0)

        // Innovation: Dynamic IOB Damping during active meal rise
        // If BG is rising while COB exists OR during unannounced meals (UAM), the "effective" insulin pulling down is dampened by the inflow of glucose.
        // We reduce the impact of IOB in the prediction to avoid false hypo alarms.
        val normalizedUamConfidence = uamConfidence.coerceIn(0.0, 1.0)
        val baseIobDampingFactor = if (rescueFastActive) {
            0.95
        } else if (cobG > 0 || delta > 2.0) {
            val normalDamping = when {
                delta > 5.0 -> 0.50 // Intense rise: IOB 50% effective in prediction
                delta > 2.0 -> 0.70 // Moderate rise
                delta > 0.0 -> if (cobG > 0) 0.85 else 1.0 // Slight rise: damp only if explicit COB
                cobG > 0 -> 0.92 // Even without visible rise, active COB should soften early insulin dominance a bit
                else -> 1.0
            }
            if (cobG <= 0.0 && !explicitCarbEntry) {
                1.0 - ((1.0 - normalDamping) * normalizedUamConfidence)
            } else {
                normalDamping
            }
        } else {
            1.0
        }

        val effectiveFoodType = if (rescueFastActive && selectedFoodType == null) "fast" else selectedFoodType
        val carbParameters = CarbAbsorptionModel.resolveParameters(cobG = cobG, delta = delta, selectedFoodType = effectiveFoodType)
        val carbWeights = CarbAbsorptionModel.buildWeights(
            steps = steps,
            peakMinutes = carbParameters.peakMinutes,
            absorptionMinutes = carbParameters.absorptionMinutes
        )
        val decisionWeights = CarbAbsorptionModel.buildWeights(
            steps = steps,
            peakMinutes = (carbParameters.peakMinutes * 0.8).coerceAtLeast(15.0),
            absorptionMinutes = (carbParameters.absorptionMinutes * 0.75).coerceAtLeast(45.0)
        )

        val normalizedMealFactor = mealFactorApplied.coerceIn(0.7, 1.5)
        val mealCurveBoost = 1.0 + (normalizedMealFactor - 1.0) * 0.8
        val effectiveCarbEffectMgDl = totalCarbEffectMgDl * mealCurveBoost
        val observedCarbImpact = observedCarbImpactMgdlPer5m.coerceAtLeast(0.0)
        val remainingObservedPeak = if (rescueFastActive) {
            remainingCiPeakMgdlPer5m.coerceIn(0.0, observedCarbImpact * 0.6)
        } else if (cobG <= 0.0 && !explicitCarbEntry) {
            remainingCiPeakMgdlPer5m.coerceAtLeast(0.0) * normalizedUamConfidence
        } else {
            remainingCiPeakMgdlPer5m.coerceAtLeast(0.0)
        }

        val effectivePlannedRate = plannedRateUph ?: profileBasalUph ?: 0.0
        val effectiveProfileBasal = profileBasalUph ?: 0.0
        val effectiveDurationHours = (plannedDurationMin.coerceAtLeast(0) / 60.0)
        val rateDeltaUph = effectivePlannedRate - effectiveProfileBasal
        val plannedBasalUnits = rateDeltaUph * effectiveDurationHours
        val additionalInsulinUnits = plannedSmbU.coerceAtLeast(0.0) + plannedBasalUnits.coerceAtLeast(0.0)
        val reducedInsulinUnits = (-plannedBasalUnits).coerceAtLeast(0.0)
        val decisionTrust = (
            0.35 +
                0.30 * mpcShare.coerceIn(0.0, 1.0) +
                0.15 * piShare.coerceIn(0.0, 1.0) +
                0.15 * (normalizedMealFactor - 1.0).coerceAtLeast(0.0) +
                if (highBgOverrideUsed) 0.15 else 0.0
            ).coerceIn(0.0, 1.2)
        val additionalInsulinSuppression = when {
            safetyMechanism?.contains("Hypo", ignoreCase = true) == true -> 0.0
            safetyMechanism?.contains("guard", ignoreCase = true) == true -> 0.15
            else -> 1.0
        }
        val reducedInsulinSupport = when {
            safetyMechanism?.contains("Hypo", ignoreCase = true) == true -> 1.0
            safetyMechanism?.contains("guard", ignoreCase = true) == true -> 0.9
            else -> 0.75
        }
        val decisionDropTotalMgDl = additionalInsulinUnits * finalSensitivity * decisionTrust * additionalInsulinSuppression
        val freshSmbPressureDropMgDl = freshSmbPressureU.coerceAtLeast(0.0) * finalSensitivity * 0.35
        val decisionLiftTotalMgDl = reducedInsulinUnits * finalSensitivity * decisionTrust.coerceAtLeast(0.6) * reducedInsulinSupport

        var lastBg = currentBG
        val sortedIob = iobArray.sortedBy { it.time }
        val baselineTime = sortedIob.firstOrNull()?.time ?: now

        // Innovation: Momentum (Delta Decay)
        // If BG is rising, assume it continues to rise for a while (inertia).
        // This is crucial for UAM where no COB is entered.
        var momentum = if (delta > 0) {
            if (rescueFastActive) delta.coerceAtMost(2.0) else delta * normalizedUamConfidence.coerceAtLeast(if (explicitCarbEntry) 1.0 else 0.0)
        } else 0.0

        repeat(steps) { stepIndex ->
            val minutesInFuture = (stepIndex + 1) * 5
            val targetTime = baselineTime + minutesInFuture * 60_000L
            val futureIobEntry = sortedIob.minByOrNull { kotlin.math.abs(it.time - targetTime) } ?: sortedIob.lastOrNull()
            var insulinImpactPer5min = ((futureIobEntry?.activity ?: 0.0).coerceAtLeast(0.0) * finalSensitivity * 5.0)

            val dampingProgress = (minutesInFuture / 90.0).coerceIn(0.0, 1.0)
            val stepIobDampingFactor = baseIobDampingFactor + (1.0 - baseIobDampingFactor) * dampingProgress
            insulinImpactPer5min *= stepIobDampingFactor
            val baseCarbImpactPer5Min = effectiveCarbEffectMgDl * carbWeights[stepIndex]
            val liveDecayMinutes = if (rescueFastActive) 12.0 else 45.0
            val residualDecayMinutes = if (rescueFastActive) 18.0 else 90.0
            val liveDecay = kotlin.math.exp(-(minutesInFuture.toDouble() / liveDecayMinutes))
            val residualRamp = when {
                carbParameters.peakMinutes <= 0.0 -> 1.0
                minutesInFuture <= carbParameters.peakMinutes ->
                    (0.55 + 0.45 * (minutesInFuture / carbParameters.peakMinutes)).coerceIn(0.55, 1.0)
                else -> kotlin.math.exp(-((minutesInFuture - carbParameters.peakMinutes) / residualDecayMinutes))
            }
            val liveCarbImpactPer5Min = observedCarbImpact * liveDecay
            val residualCarbImpactPer5Min = remainingObservedPeak * residualRamp
            val carbImpactPer5Min = maxOf(
                baseCarbImpactPer5Min,
                liveCarbImpactPer5Min,
                residualCarbImpactPer5Min
            )
            val decisionLiftPer5Min = decisionLiftTotalMgDl * decisionWeights[stepIndex]
            val decisionDropPer5Min = decisionDropTotalMgDl * decisionWeights[stepIndex]
            val freshSmbDropPer5Min = freshSmbPressureDropMgDl * decisionWeights[stepIndex]

            // Apply Momentum
            // We add the current 'inertia' to the BG change, then decay it.
            val nextBg = (lastBg - insulinImpactPer5min + carbImpactPer5Min + decisionLiftPer5Min - decisionDropPer5Min - freshSmbDropPer5Min + momentum).coerceIn(39.0, 401.0)

            // Linear/Exp decay of momentum
            momentum *= if (rescueFastActive) 0.35 else 0.85 // Быстрые спасательные углеводы не должны давать длинный хвост роста.

            lastBg = nextBg
            predictions.add(lastBg)
        }

        return predictions
    }

}
