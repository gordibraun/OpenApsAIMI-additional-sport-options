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
        carbSensitivityMgdlPerGram: Double? = null,
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
        targetBG: Double? = null,
        carbImpactTimelineMgdlPer5m: List<Double>? = null,
        horizonMinutes: Int = 240
    ): List<Double> {
        val predictions = mutableListOf(currentBG)
        if (horizonMinutes <= 0) return predictions

        val steps = maxOf(1, horizonMinutes / 5)
        val now = System.currentTimeMillis()
        val carbRatio = profile.carb_ratio.takeIf { it > 0 } ?: 10.0
        val csf = carbSensitivityMgdlPerGram
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: (finalSensitivity / carbRatio)
        val totalCarbEffectMgDl = (cobG * csf).coerceAtLeast(0.0)
        val belowTargetUnannouncedRise = targetBG
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { target -> currentBG < target && delta > 0.0 && cobG <= 0.0 && !explicitCarbEntry }
            ?: false
        val rescueFastModel = rescueFastActive && cobG <= 15.0
        val shortReboundModel = rescueFastModel || belowTargetUnannouncedRise

        // Innovation: Dynamic IOB Damping during active meal rise
        // If BG is rising while COB exists OR during unannounced meals (UAM), the "effective" insulin pulling down is dampened by the inflow of glucose.
        // We reduce the impact of IOB in the prediction to avoid false hypo alarms.
        val normalizedUamConfidence = uamConfidence.coerceIn(0.0, 1.0)
        val baseIobDampingFactor = if (shortReboundModel) {
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

        val effectiveFoodType = if (rescueFastModel && selectedFoodType == null) "fast" else selectedFoodType
        val effectiveFoodTypeName = effectiveFoodType?.lowercase()
        val explicitTypedCarbs = explicitCarbEntry || cobG > 0.0
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
        val explicitCarbTimeline = carbImpactTimelineMgdlPer5m
            ?.takeIf { it.isNotEmpty() }
            ?.map { it.coerceAtLeast(0.0) }
        val observedCarbImpact = observedCarbImpactMgdlPer5m.coerceAtLeast(0.0)
        val typedObservedTailFactor = when (effectiveFoodTypeName) {
            "fast" -> 0.45
            "slow" -> 1.0
            else -> 0.75
        }
        val remainingObservedPeak = if (rescueFastModel) {
            remainingCiPeakMgdlPer5m.coerceIn(0.0, observedCarbImpact * 0.6)
        } else if (belowTargetUnannouncedRise) {
            remainingCiPeakMgdlPer5m.coerceAtLeast(0.0) * normalizedUamConfidence * 0.35
        } else if (cobG <= 0.0 && !explicitCarbEntry) {
            remainingCiPeakMgdlPer5m.coerceAtLeast(0.0) * normalizedUamConfidence
        } else if (explicitTypedCarbs) {
            remainingCiPeakMgdlPer5m.coerceAtLeast(0.0) * typedObservedTailFactor
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
        val protectiveSafety = isProtectiveSafety(safetyMechanism)
        val hypoSafety = isHypoSafety(safetyMechanism)
        val hasFinalDeliveryDecision = plannedSmbU > 0.0 || plannedRateUph != null
        val additionalInsulinSuppression = when {
            hypoSafety -> 0.0
            protectiveSafety -> 0.15
            else -> 1.0
        }
        val finalDeliveryVisibility = when {
            !hasFinalDeliveryDecision -> decisionTrust
            hypoSafety -> 0.0
            protectiveSafety -> 0.50
            else -> 1.0
        }
        val finalBasalVisibility = if (hasFinalDeliveryDecision) 1.0 else decisionTrust.coerceAtLeast(0.6)
        val reducedInsulinSupport = when {
            hypoSafety -> 1.0
            protectiveSafety || hasFinalDeliveryDecision -> 1.0
            else -> 0.75
        }
        val decisionDropTotalMgDl = additionalInsulinUnits * finalSensitivity * finalDeliveryVisibility * additionalInsulinSuppression
        val freshSmbPressureDropMgDl = freshSmbPressureU.coerceAtLeast(0.0) * finalSensitivity * 0.35
        val decisionLiftTotalMgDl = reducedInsulinUnits * finalSensitivity * finalBasalVisibility * reducedInsulinSupport

        var lastBg = currentBG
        val sortedIob = iobArray.sortedBy { it.time }
        val baselineTime = sortedIob.firstOrNull()?.time ?: now

        // Innovation: Momentum (Delta Decay)
        // If BG is rising, assume it continues to rise for a while (inertia).
        // This is crucial for UAM where no COB is entered.
        var momentum = initialMomentum(
            delta = delta,
            rescueFastActive = rescueFastModel,
            belowTargetUnannouncedRise = belowTargetUnannouncedRise,
            explicitCarbEntry = explicitCarbEntry || cobG > 0.0,
            selectedFoodType = effectiveFoodType,
            normalizedUamConfidence = normalizedUamConfidence
        )

        repeat(steps) { stepIndex ->
            val minutesInFuture = (stepIndex + 1) * 5
            val targetTime = baselineTime + minutesInFuture * 60_000L
            val futureIobEntry = sortedIob.minByOrNull { kotlin.math.abs(it.time - targetTime) } ?: sortedIob.lastOrNull()
            var insulinImpactPer5min = ((futureIobEntry?.activity ?: 0.0).coerceAtLeast(0.0) * finalSensitivity * 5.0)

            val dampingProgress = (minutesInFuture / 90.0).coerceIn(0.0, 1.0)
            val stepIobDampingFactor = baseIobDampingFactor + (1.0 - baseIobDampingFactor) * dampingProgress
            insulinImpactPer5min *= stepIobDampingFactor
            val baseCarbImpactPer5Min = explicitCarbTimeline
                ?.getOrNull(stepIndex)
                ?.let { it * mealCurveBoost }
                ?: (effectiveCarbEffectMgDl * carbWeights[stepIndex])
            val liveDecayMinutes = when {
                shortReboundModel -> 12.0
                explicitTypedCarbs -> (carbParameters.peakMinutes * 1.1).coerceIn(12.0, 45.0)
                else -> 45.0
            }
            val residualDecayMinutes = when {
                shortReboundModel -> 18.0
                explicitTypedCarbs -> (carbParameters.absorptionMinutes * 0.45).coerceIn(18.0, 120.0)
                else -> 90.0
            }
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
            momentum *= momentumDecay(
                rescueFastActive = rescueFastModel,
                belowTargetUnannouncedRise = belowTargetUnannouncedRise,
                explicitCarbEntry = explicitCarbEntry || cobG > 0.0,
                selectedFoodType = effectiveFoodType,
                normalizedUamConfidence = normalizedUamConfidence
            )

            lastBg = nextBg
            predictions.add(lastBg)
        }

        return predictions
    }

    private fun initialMomentum(
        delta: Double,
        rescueFastActive: Boolean,
        belowTargetUnannouncedRise: Boolean,
        explicitCarbEntry: Boolean,
        selectedFoodType: String?,
        normalizedUamConfidence: Double
    ): Double {
        if (delta <= 0.0) return 0.0
        if (rescueFastActive) return delta.coerceAtMost(2.0)
        if (belowTargetUnannouncedRise) return (delta * normalizedUamConfidence).coerceAtMost(1.5)

        if (explicitCarbEntry) {
            val foodType = selectedFoodType?.lowercase()
            val cap = when (foodType) {
                "fast" -> 2.5
                "slow" -> 4.0
                else -> 3.0
            }
            return delta.coerceAtMost(cap)
        }

        val confidenceCap = when {
            normalizedUamConfidence >= 0.75 -> 8.0
            normalizedUamConfidence >= 0.40 -> 5.0
            else -> 2.5
        }
        return (delta * normalizedUamConfidence).coerceAtMost(confidenceCap)
    }

    private fun momentumDecay(
        rescueFastActive: Boolean,
        belowTargetUnannouncedRise: Boolean,
        explicitCarbEntry: Boolean,
        selectedFoodType: String?,
        normalizedUamConfidence: Double
    ): Double {
        if (rescueFastActive) return 0.35
        if (belowTargetUnannouncedRise) return 0.35

        if (explicitCarbEntry) {
            return when (selectedFoodType?.lowercase()) {
                "fast" -> 0.40
                "slow" -> 0.70
                else -> 0.55
            }
        }

        return if (normalizedUamConfidence >= 0.75) 0.75 else 0.55
    }

    private fun isProtectiveSafety(safetyMechanism: String?): Boolean {
        val safety = safetyMechanism?.lowercase() ?: return false
        return safety.contains("guard") ||
            safety.contains("защит") ||
            safety.contains("перелив") ||
            safety.contains("early overdelivery")
    }

    private fun isHypoSafety(safetyMechanism: String?): Boolean {
        val safety = safetyMechanism?.lowercase() ?: return false
        return safety.contains("hypo") ||
            safety.contains("гипо") ||
            safety.contains("низк")
    }

}
