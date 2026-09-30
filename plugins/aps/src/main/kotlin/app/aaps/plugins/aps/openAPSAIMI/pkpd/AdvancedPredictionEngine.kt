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
    @Suppress("UNUSED_PARAMETER") // Legacy callers still supply decision metadata, not physical model inputs.
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
        horizonMinutes: Int = 240,
        plannedInsulinAction: PlannedInsulinAction? = null
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
        // A retained meal label is history, not remaining carbohydrate mass.
        val declaredCarbsActive = cobG.isFinite() && cobG > 0.0
        val belowTargetUnannouncedRise = targetBG
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { target -> currentBG < target && delta > 0.0 && !declaredCarbsActive }
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
            if (!declaredCarbsActive) {
                1.0 - ((1.0 - normalDamping) * normalizedUamConfidence)
            } else {
                normalDamping
            }
        } else {
            1.0
        }

        val activeFoodType = selectedFoodType.takeIf { declaredCarbsActive }
        val effectiveFoodType = if (rescueFastModel && activeFoodType == null) "fast" else activeFoodType
        val effectiveFoodTypeName = effectiveFoodType?.lowercase()
        val explicitTypedCarbs = declaredCarbsActive
        val carbParameters = CarbAbsorptionModel.resolveParameters(cobG = cobG, delta = delta, selectedFoodType = effectiveFoodType)
        val carbWeights = CarbAbsorptionModel.buildWeights(
            steps = steps,
            peakMinutes = carbParameters.peakMinutes,
            absorptionMinutes = carbParameters.absorptionMinutes
        )

        val normalizedMealFactor = mealFactorApplied.coerceIn(0.7, 1.5)
        val mealCurveBoost = 1.0 + (normalizedMealFactor - 1.0) * 0.8
        val effectiveCarbEffectMgDl = totalCarbEffectMgDl * mealCurveBoost
        val explicitCarbTimeline = carbImpactTimelineMgdlPer5m
            ?.takeIf { declaredCarbsActive && it.isNotEmpty() }
            ?.map { it.coerceAtLeast(0.0) }
        val explicitFastCarbs = explicitTypedCarbs && effectiveFoodTypeName == "fast"
        val typedCarbImpactCapMgdlPer5m = when {
            explicitFastCarbs -> 28.0
            explicitTypedCarbs && effectiveFoodTypeName == "balanced" ->
                (totalCarbEffectMgDl * 0.065).coerceIn(12.0, 18.0)
            explicitTypedCarbs && effectiveFoodTypeName == "slow" ->
                (totalCarbEffectMgDl * 0.05).coerceIn(8.0, 14.0)
            else -> Double.POSITIVE_INFINITY
        }
        val observedCarbImpact = observedCarbImpactMgdlPer5m.coerceAtLeast(0.0)
        val typedObservedTailFactor = when (effectiveFoodTypeName) {
            "fast" -> 0.25
            "slow" -> 1.0
            else -> 0.75
        }
        val remainingObservedPeak = if (rescueFastModel) {
            remainingCiPeakMgdlPer5m.coerceIn(0.0, observedCarbImpact * 0.6)
        } else if (belowTargetUnannouncedRise) {
            remainingCiPeakMgdlPer5m.coerceAtLeast(0.0) * normalizedUamConfidence * 0.35
        } else if (!declaredCarbsActive) {
            remainingCiPeakMgdlPer5m.coerceAtLeast(0.0) * normalizedUamConfidence
        } else if (explicitTypedCarbs) {
            remainingCiPeakMgdlPer5m.coerceAtLeast(0.0) * typedObservedTailFactor
        } else {
            remainingCiPeakMgdlPer5m.coerceAtLeast(0.0)
        }

        val effectivePlannedRate = plannedRateUph ?: profileBasalUph ?: 0.0
        val effectiveProfileBasal = profileBasalUph ?: 0.0
        val rateDeltaUph = effectivePlannedRate - effectiveProfileBasal
        // Delivered SMBs are already in iobArray. Only the proposed, not-yet-delivered
        // dose is added here; a safety label must not change the action of that dose.
        val decisionEffects = if (plannedSmbU != 0.0 || (rateDeltaUph != 0.0 && plannedDurationMin > 0)) {
            requireNotNull(plannedInsulinAction) { "Active insulin model required for a planned dose" }
                .effectsPer5Minutes(plannedSmbU, rateDeltaUph, plannedDurationMin.coerceAtLeast(0), steps)
        } else DoubleArray(steps)

        var remainingDeclaredCarbEffect = effectiveCarbEffectMgDl
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
            explicitCarbEntry = declaredCarbsActive,
            selectedFoodType = effectiveFoodType,
            normalizedUamConfidence = normalizedUamConfidence
        )

        repeat(steps) { stepIndex ->
            val minutesInFuture = (stepIndex + 1) * 5
            val targetTime = baselineTime + minutesInFuture * 60_000L
            val futureIobEntry = sortedIob.minByOrNull { kotlin.math.abs(it.time - targetTime) } ?: sortedIob.lastOrNull()
            // Activity is net of scheduled basal. Negative activity represents an
            // earlier basal deficit and must raise, not leave unchanged, the forecast.
            var insulinImpactPer5min = (futureIobEntry?.activity ?: 0.0) * finalSensitivity * 5.0

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
            val carbImpactPer5Min = if (explicitTypedCarbs) {
                // Declared food already has an absorption curve. Extrapolating its observed
                // rise again creates additional grams and discards the selected food type.
                baseCarbImpactPer5Min.coerceAtMost(typedCarbImpactCapMgdlPer5m)
                    .coerceAtMost(remainingDeclaredCarbEffect).also { remainingDeclaredCarbEffect -= it }
            } else {
                maxOf(baseCarbImpactPer5Min, liveCarbImpactPer5Min, residualCarbImpactPer5Min)
            }
            val decisionDropPer5Min = decisionEffects[stepIndex] * finalSensitivity

            // Apply Momentum
            // We add the current 'inertia' to the BG change, then decay it.
            val nextBg = (lastBg - insulinImpactPer5min + carbImpactPer5Min - decisionDropPer5Min + momentum).coerceIn(39.0, 401.0)

            // Linear/Exp decay of momentum
            momentum *= momentumDecay(
                rescueFastActive = rescueFastModel,
                belowTargetUnannouncedRise = belowTargetUnannouncedRise,
                explicitCarbEntry = declaredCarbsActive,
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
