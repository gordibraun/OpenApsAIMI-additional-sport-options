package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.math.max

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
        val recentSmb30U: Double
    )

    data class Decision(
        val blockSmb: Boolean,
        val reason: String = ""
    )

    fun evaluate(input: Input): Decision {
        val highBgLimit = max(input.maxSmbU, input.highBgMaxSmbU).coerceAtLeast(0.1)
        val highIobPressure = input.iobU >= max(3.0, highBgLimit * 1.5)
        val recentBurst15 = input.recentSmb15U >= max(2.0, highBgLimit * 0.8)
        val recentBurst30 = input.recentSmb30U >= max(3.0, highBgLimit * 1.2)
        val visibleCarbsTooSmall = input.visibleCobG <= 6.0 && !input.explicitFoodActive
        val highBgButNotExtreme = input.bg in 170.0..260.0
        val moderateBgAlreadyLoaded = input.bg >= 140.0 &&
            input.bg < 170.0 &&
            input.iobU >= max(5.0, highBgLimit * 2.5)

        val block = input.noActiveMealMode &&
            visibleCarbsTooSmall &&
            highIobPressure &&
            (
                (highBgButNotExtreme && (recentBurst15 || recentBurst30)) ||
                    moderateBgAlreadyLoaded
                )

        return Decision(
            blockSmb = block,
            reason = if (block) {
                "накопительный SMB без активной еды: " +
                    "15м=${"%.2f".format(input.recentSmb15U)}U, " +
                    "30м=${"%.2f".format(input.recentSmb30U)}U, " +
                    "BG=${"%.0f".format(input.bg)}, " +
                    "IOB=${"%.2f".format(input.iobU)}U"
            } else {
                ""
            }
        )
    }
}
