package app.aaps.plugins.aps.openAPSAIMI.safety

internal object EarlyOverdeliveryGuard {
    data class Input(
        val noActiveMealMode: Boolean,
        val cobG: Double,
        val bg: Double,
        val delta: Double,
        val shortAvgDelta: Double,
        val iobU: Double,
        val lastSmbMinutes: Int,
        val lastSmbU: Double,
        val turningDown: Boolean,
        val minForecastBg: Double,
        val explicitlySlowCarbs: Boolean,
        val cumulativeSmbBlocked: Boolean
    )

    data class Decision(val maxSmbUnits: Double?) {
        // A positive cap only limits SMB; it does not request the zero-basal path.
        val requiresBasalHold: Boolean get() = maxSmbUnits == 0.0
        fun limitSmb(proposedUnits: Double): Double = maxSmbUnits?.let { minOf(proposedUnits, it) } ?: proposedUnits
    }

    fun evaluate(input: Input): Decision = with(input) {
        val freshSmb = lastSmbMinutes in 0..35 && lastSmbU >= 0.3
        val strongRecentSmb = lastSmbMinutes in 0..60 && lastSmbU >= 0.8
        val insulinPressure = iobU >= 1.0 || freshSmb || strongRecentSmb
        val falling = delta <= -1.0 || shortAvgDelta <= -0.5 || turningDown
        val smallTail = cobG > 0.0 && cobG <= 6.0
        val hold = cumulativeSmbBlocked ||
            (noActiveMealMode && cobG <= 5.0 && insulinPressure && bg in 90.0..170.0 &&
                (falling || minForecastBg < 125.0)) ||
            (noActiveMealMode && smallTail && !explicitlySlowCarbs && strongRecentSmb &&
                bg < 180.0 && (delta >= 0.0 || shortAvgDelta >= 0.0))
        val softCap = noActiveMealMode && smallTail && !explicitlySlowCarbs &&
            bg in 100.0..170.0 && delta >= 3.0 && shortAvgDelta >= 2.0
        Decision(when {
            hold -> 0.0
            softCap -> 0.3
            else -> null
        })
    }
}
