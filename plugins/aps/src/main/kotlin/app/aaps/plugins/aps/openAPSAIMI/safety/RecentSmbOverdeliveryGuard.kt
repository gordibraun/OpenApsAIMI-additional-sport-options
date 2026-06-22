package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.math.max
import kotlin.math.min

internal object RecentSmbOverdeliveryGuard {

    data class Input(
        val noActiveMealMode: Boolean,
        val visibleCobG: Double,
        val explicitFoodActive: Boolean,
        val bg: Double,
        val iobU: Double,
        val maxSmbU: Double,
        val highBgMaxSmbU: Double,
        val recentSmb15U: Double,
        val recentSmb30U: Double,
        val proposedSmbU: Double = 0.0,
        val nightNoMeal: Boolean = false,
        val delta: Double = 0.0,
        val eventualBg: Double = Double.NaN,
        val predictedBg: Double = Double.NaN,
        val minGuardBg: Double = Double.NaN,
        val targetBg: Double = 117.0
    )

    data class Decision(
        val blockSmb: Boolean,
        val reason: String = "",
        val maxAllowedSmbU: Double? = null
    )

    data class CorrectionLimit(
        val maxSmbU: Double,
        val reason: String = ""
    )

    fun correctionLimit(input: Input): CorrectionLimit {
        val highBgLimit = max(input.maxSmbU, input.highBgMaxSmbU).coerceAtLeast(0.1)
        unsafeForecastBlockReason(input, highBgLimit)?.let { reason ->
            return CorrectionLimit(0.0, reason)
        }

        val visibleCarbsTooSmall = input.visibleCobG <= 6.0 && !input.explicitFoodActive
        val noFoodContext = input.noActiveMealMode && visibleCarbsTooSmall
        if (!noFoodContext) return CorrectionLimit(highBgLimit)

        val extremeHighStillHigh = input.bg >= 260.0 &&
            (!input.eventualBg.isFinite() || input.eventualBg >= 260.0)
        if (extremeHighStillHigh) return CorrectionLimit(highBgLimit)

        val budget15 = when {
            input.nightNoMeal -> max(0.5, highBgLimit * 0.35)
            input.bg < 180.0 -> max(0.8, highBgLimit * 0.5)
            else -> max(1.2, highBgLimit * 0.75)
        }
        val budget30 = when {
            input.nightNoMeal -> max(0.8, highBgLimit * 0.55)
            input.bg < 180.0 -> max(1.2, highBgLimit * 0.75)
            else -> max(2.0, highBgLimit)
        }
        val bgBudget = when {
            input.nightNoMeal && input.bg < 170.0 -> 0.20
            input.nightNoMeal && input.bg < 220.0 -> 0.50
            input.bg < 170.0 -> 0.50
            input.bg < 220.0 -> 0.75
            else -> highBgLimit
        }

        var allowed = min(
            min(max(0.0, budget15 - input.recentSmb15U), max(0.0, budget30 - input.recentSmb30U)),
            bgBudget
        ).coerceAtMost(highBgLimit)

        val forecastStronglyHigh = input.eventualBg.isFinite() && input.eventualBg >= 220.0
        if (input.nightNoMeal && input.bg < 170.0 && (input.iobU >= 2.5 || input.recentSmb30U >= 0.5)) {
            allowed = 0.0
        } else if (input.nightNoMeal && input.bg < 220.0 && input.iobU >= 5.0 && !forecastStronglyHigh) {
            allowed = min(allowed, 0.15)
        } else if (input.bg < 180.0 && input.iobU >= max(2.5, highBgLimit)) {
            allowed = min(allowed, 0.30)
        }

        return if (allowed < highBgLimit) {
            CorrectionLimit(
                maxSmbU = allowed,
                reason = "расчет SMB приглушен: нет активной еды" +
                    if (input.nightNoMeal) "/ночь" else "" +
                    ", лимит ${"%.2f".format(highBgLimit)}U→${"%.2f".format(allowed)}U, " +
                    "15м=${"%.2f".format(input.recentSmb15U)}U, " +
                    "30м=${"%.2f".format(input.recentSmb30U)}U, " +
                    "BG=${"%.0f".format(input.bg)}, IOB=${"%.2f".format(input.iobU)}U"
            )
        } else {
            CorrectionLimit(highBgLimit)
        }
    }

    fun evaluate(input: Input): Decision {
        val highBgLimit = max(input.maxSmbU, input.highBgMaxSmbU).coerceAtLeast(0.1)
        val highIobPressure = input.iobU >= max(3.0, highBgLimit * 1.5)
        val proposed = input.proposedSmbU.coerceAtLeast(0.0)
        val recent15After = input.recentSmb15U + proposed
        val recent30After = input.recentSmb30U + proposed
        val burst15Limit = max(2.0, highBgLimit * 0.8)
        val burst30Limit = max(3.0, highBgLimit * 1.2)
        val recentBurst15 = recent15After >= burst15Limit
        val recentBurst30 = recent30After >= burst30Limit
        val visibleCarbsTooSmall = input.visibleCobG <= 6.0 && !input.explicitFoodActive
        val noFoodContext = input.noActiveMealMode && visibleCarbsTooSmall
        val unsafeForecastReason = unsafeForecastBlockReason(input, highBgLimit)
        val highBgButNotExtreme = input.bg in 170.0..260.0
        val moderateBgAlreadyLoaded = input.bg >= 140.0 &&
            input.bg < 170.0 &&
            input.iobU >= max(5.0, highBgLimit * 2.5)
        val forecastNotSafelyHigh = input.eventualBg.isFinite() && input.eventualBg < 180.0
        val nightLoaded = input.nightNoMeal &&
            input.iobU >= max(3.0, highBgLimit * 1.25) &&
            input.bg < 260.0 &&
            (
                proposed >= 0.3 ||
                    recent30After >= max(1.5, highBgLimit * 0.6) ||
                    forecastNotSafelyHigh
                )

        val block = unsafeForecastReason != null ||
            noFoodContext &&
            (
                highIobPressure &&
                    (
                        (highBgButNotExtreme && (recentBurst15 || recentBurst30)) ||
                            moderateBgAlreadyLoaded
                        ) ||
                    nightLoaded
                )
        val maxAllowed = if (!block && noFoodContext && highIobPressure && highBgButNotExtreme) {
            min(
                max(0.0, burst15Limit - input.recentSmb15U),
                max(0.0, burst30Limit - input.recentSmb30U)
            ).coerceAtMost(highBgLimit)
        } else {
            null
        }

        return Decision(
            blockSmb = block,
            reason = if (block) {
                unsafeForecastReason ?: (
                    "накопительный SMB без активной еды: " +
                        "15м=${"%.2f".format(input.recentSmb15U)}U→${"%.2f".format(recent15After)}U, " +
                        "30м=${"%.2f".format(input.recentSmb30U)}U→${"%.2f".format(recent30After)}U, " +
                        "BG=${"%.0f".format(input.bg)}, " +
                        "IOB=${"%.2f".format(input.iobU)}U" +
                        if (input.nightNoMeal) ", ночь/сон без еды" else ""
                    )
            } else {
                ""
            },
            maxAllowedSmbU = maxAllowed
        )
    }

    private fun unsafeForecastBlockReason(input: Input, highBgLimit: Double): String? {
        val forecastFloor = listOf(input.eventualBg, input.predictedBg, input.minGuardBg)
            .filter { it.isFinite() && it > 0.0 }
            .minOrNull() ?: return null
        val target = if (input.targetBg.isFinite() && input.targetBg > 0.0) input.targetBg else 117.0
        val forecastBelowTarget = forecastFloor <= max(70.0, target - 15.0)
        val forecastSevereLow = forecastFloor <= 80.0
        val hardIobPressure = input.iobU >= max(4.0, highBgLimit * 2.0)
        val moderateBg = input.bg < 220.0
        val recentPressure = input.recentSmb30U >= 0.5 || input.proposedSmbU > 0.0

        if (!moderateBg) return null
        if (!forecastSevereLow && !(forecastBelowTarget && (hardIobPressure || recentPressure))) return null
        if (input.iobU < 1.5 && input.recentSmb30U < 0.5) return null

        return "SMB заблокирован: прогноз ниже цели/опасный минимум " +
            "${"%.0f".format(forecastFloor)} при цели ${"%.0f".format(target)}, " +
            "BG=${"%.0f".format(input.bg)}, IOB=${"%.2f".format(input.iobU)}U, " +
            "30м SMB=${"%.2f".format(input.recentSmb30U)}U"
    }
}
