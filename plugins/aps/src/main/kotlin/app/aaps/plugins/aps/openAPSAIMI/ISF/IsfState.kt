package app.aaps.plugins.aps.openAPSAIMI.ISF

import kotlinx.serialization.Serializable

internal const val ISF_STATE_MAX_AGE_MS = 8 * 60 * 60 * 1000L

internal fun recentIsfState(timestamp: Long, now: Long): Boolean =
    timestamp > 0 && timestamp <= now && now - timestamp <= ISF_STATE_MAX_AGE_MS

@Serializable
data class RateLimitedIsfState(val isf: Double, val timestamp: Long) {
    fun isValid(): Boolean = isf.isFinite() && isf > 0 && timestamp > 0
}

@Serializable
data class KalmanIsfState(val estimate: Double, val error: Double) {
    fun isValid(): Boolean = estimate.isFinite() && estimate in 5.0..300.0 && error.isFinite() && error >= 0
}

@Serializable
data class AdaptiveIsfState(
    val schema: Int = 1,
    val timestamp: Long,
    val context: String,
    val kalman: KalmanIsfState,
    val blender: RateLimitedIsfState,
    val adjustment: RateLimitedIsfState,
    val tddEma: Double,
    val pkpdScale: Double
) {
    fun isUsable(expectedContext: String, now: Long): Boolean =
        schema == 1 && context == expectedContext && recentIsfState(timestamp, now) &&
            kalman.isValid() && blender.isValid() && adjustment.isValid() &&
            blender.timestamp == timestamp && adjustment.timestamp == timestamp &&
            tddEma.isFinite() && tddEma > 0 && pkpdScale.isFinite() && pkpdScale in 0.8..1.5
}

@Serializable
data class FusionIsfState(val schema: Int = 1, val context: String, val value: RateLimitedIsfState) {
    fun isUsable(expectedContext: String, now: Long): Boolean =
        schema == 1 && context == expectedContext && value.isValid() && recentIsfState(value.timestamp, now)
}
