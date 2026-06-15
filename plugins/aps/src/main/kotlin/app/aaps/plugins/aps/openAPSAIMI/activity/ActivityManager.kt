package app.aaps.plugins.aps.openAPSAIMI.activity

import app.aaps.core.data.model.TE
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Manages the detection of physical activity using Steps and Heart Rate.
 * Replaces simple threshold logic with state-based scoring and recovery management.
 */
class ActivityManager @Inject constructor() {

    // Internal state for smoothing/hysteresis
    private var lastIntensityScore: Double = 0.0
    private var recoveryBucket: Double = 0.0 // Accumulates during effort, decays slowly
    private val RECOVERY_DECAY_RATE = 0.5 // Points lost per 5 min cycle
    private val RECOVERY_ACCUMULATION_FACTOR = 0.2 // Points gained per score point

    private enum class ManualMode(val label: String, val effectFraction: Double) {
        WALK("WALK", 0.20),
        SPORT("SPORT", 0.30)
    }

    private data class ManualActivity(
        val mode: ManualMode,
        val startMs: Long,
        val durationMinutes: Int,
        val tailMinutes: Int,
        val note: String?
    )

    companion object {

        const val NOTE_PREFIX = "AIMI_ACTIVITY_V2"

        fun buildNote(
            mode: String,
            effectPercent: Int,
            startOffsetMinutes: Int,
            durationMinutes: Int,
            tailMinutes: Int,
            requiredCarbs: Int,
            carbType: String?
        ): String =
            "$NOTE_PREFIX mode=$mode effect=$effectPercent startOffset=$startOffsetMinutes duration=$durationMinutes " +
                "tail=$tailMinutes requiredCarbs=$requiredCarbs carbType=${carbType ?: "none"}"
    }

    /**
     * Main processing function. Call this every loop cycle (~5 mins).
     *
     * @param steps5min Steps count in last 5 minutes.
     * @param steps10min Steps count in last 10 minutes (for trend).
     * @param avgHr Current average HR (e.g. 5 min avg).
     * @param avgHrResting Estimated resting HR (e.g. 60 min avg or user param).
     * @return The calculated ActivityContext.
     */
    fun process(
        steps5min: Int,
        steps10min: Int,
        avgHr: Double,
        avgHrResting: Double,
        therapyEvents: List<TE> = emptyList(),
        now: Long = System.currentTimeMillis(),
        profileBasalUph: Double = 0.0,
        profileIsfMgdl: Double = 0.0
    ): ActivityContext {
        // 1. Scoring (0.0 - 10.0+)
        // Base score from steps (approx 100 steps/min = moderate walk -> score ~3-4)
        val stepsPerMin = steps5min / 5.0
        val stepScore = (stepsPerMin / 25.0).coerceIn(0.0, 8.0) // 100 spm => 4.0

        // HR Reserve contribution (HRR)
        // If HR is unknown or low quality, we rely mostly on steps.
        // Assuming Max HR ~ 180 (simplified) or generic scaling.
        val safeResting = if (avgHrResting > 40) avgHrResting else 60.0
        val hrReserve = if (avgHr > safeResting) (avgHr - safeResting) else 0.0
        val hrScore = (hrReserve / 10.0).coerceIn(0.0, 8.0) // +30 bpm => 3.0

        // Context fusion
        // HR alone is suspicious (stress?), Steps alone is robust but maybe light.
        // If both are present, we boost confidence.
        var rawScore = 0.0
        if (stepsPerMin > 20) {
            rawScore = stepScore
            if (hrScore > 1.0) {
                // Confirming activity with HR
                rawScore += (hrScore * 0.5)
            }
        } else if (hrScore > 3.0) {
           // High HR without steps -> Stress or Resistance, or Cycling?
           // For safety, we count it but weight it differently (handled by existing stress logic usually)
           // Here we focus on "Activity" for ISF boost.
           // We'll ignore pure HR for *Activity* boost to avoid over-bolusing during stress.
           rawScore = 0.0
        }

        // 2. Smoothing (Simple Exponential Moving Average)
        // alpha 0.4 -> 40% new, 60% old. Avoids jitter.
        val smoothedScore = (rawScore * 0.4) + (lastIntensityScore * 0.6)
        lastIntensityScore = smoothedScore

        // 3. Recovery Bucket Management
        if (smoothedScore > 2.0) {
            // Accumulate fatigue/recovery debt
            recoveryBucket += (smoothedScore * RECOVERY_ACCUMULATION_FACTOR)
        } else {
            // Decay
            recoveryBucket = max(0.0, recoveryBucket - RECOVERY_DECAY_RATE)
        }
        // Cap bucket reasonable size (e.g. max 2-3 hours of decay)
        recoveryBucket = min(recoveryBucket, 40.0)

        // 4. State Determination
        val state = when {
            smoothedScore < 1.0 -> ActivityState.REST
            smoothedScore < 3.0 -> ActivityState.LIGHT
            smoothedScore < 6.0 -> ActivityState.MODERATE
            else -> ActivityState.INTENSE
        }

        // 5. Impact Calculation (ISF Multiplier)
        // Light: 1.1x - 1.2x
        // Moderate: 1.3x - 1.4x
        // Intense: 1.5x+
        val baseMultiplier = 1.0 + (smoothedScore * 0.08) // Score 5 => 1.4x
        val cappedMultiplier = baseMultiplier.coerceIn(1.0, 1.6) // Cap safety 60% boost

        // 6. Recovery Logic
        // If score is low (REST) but bucket is high -> Recovery Mode
        val isRecovery = (state == ActivityState.REST && recoveryBucket > 5.0)
        
        // During recovery, we might want to keep *some* sensitivity or protection
        // For now, let's flag it for safety protection (e.g. conservative SMBs)
        
        val description = if (isRecovery) "Recovery (Debt: ${"%.1f".format(recoveryBucket)})" else "${state.name} (Score: ${"%.1f".format(smoothedScore)})"

        val automaticContext = ActivityContext(
            state = state,
            intensityScore = smoothedScore,
            isRecovery = isRecovery,
            isfMultiplier = cappedMultiplier,
            protectionMode = isRecovery || (state == ActivityState.INTENSE),
            description = description
        )
        val manualContext = resolveManualContext(therapyEvents, now, profileBasalUph, profileIsfMgdl)
        return mergeContexts(automaticContext, manualContext)
    }

    private fun resolveManualContext(
        therapyEvents: List<TE>,
        now: Long,
        profileBasalUph: Double,
        profileIsfMgdl: Double
    ): ActivityContext? {
        val manual = therapyEvents
            .asSequence()
            .filter { it.isValid && it.type == TE.Type.EXERCISE }
            .mapNotNull { event -> parseManualActivity(event) }
            .filter { activity ->
                val endWithTail = activity.startMs + minutesToMs(activity.durationMinutes + activity.tailMinutes)
                activity.startMs <= now + minutesToMs(6 * 60) && endWithTail >= now - minutesToMs(5)
            }
            .maxByOrNull { it.startMs } ?: return null

        val activeEnd = manual.startMs + minutesToMs(manual.durationMinutes)
        val tailEnd = activeEnd + minutesToMs(manual.tailMinutes)
        val startOffset = ((manual.startMs - now) / 60_000.0).roundToInt()
        val currentPhase = when {
            now < manual.startMs        -> 0.0
            now <= activeEnd            -> 1.0
            now <= tailEnd              -> ((tailEnd - now).toDouble() / minutesToMs(manual.tailMinutes).toDouble()).coerceIn(0.0, 1.0)
            else                        -> 0.0
        }
        val isUpcoming = startOffset > 0
        val activeRemaining = when {
            now < manual.startMs -> manual.durationMinutes
            now <= activeEnd     -> ((activeEnd - now) / 60_000.0).roundToInt().coerceAtLeast(0)
            else                 -> 0
        }
        val tailRemaining = when {
            now <= activeEnd -> manual.tailMinutes
            now <= tailEnd   -> ((tailEnd - now) / 60_000.0).roundToInt().coerceAtLeast(0)
            else             -> 0
        }
        val effectNow = manual.mode.effectFraction * currentPhase
        val newInsulinOverlap = when {
            currentPhase > 0.0       -> currentPhase
            startOffset in 1..75     -> 1.0
            startOffset in 76..120   -> ((120 - startOffset).toDouble() / 45.0).coerceIn(0.0, 1.0)
            else                     -> 0.0
        }
        val newInsulinFactor = (1.0 - manual.mode.effectFraction * newInsulinOverlap).coerceIn(0.55, 1.0)
        val state = when {
            manual.mode == ManualMode.SPORT && currentPhase > 0.0 -> ActivityState.MODERATE
            manual.mode == ManualMode.WALK && currentPhase > 0.0  -> ActivityState.LIGHT
            else                                                  -> ActivityState.REST
        }
        val phaseDescription = when {
            isUpcoming       -> "старт через ${startOffset}м"
            now <= activeEnd -> "активна еще ${activeRemaining}м"
            tailRemaining > 0 -> "хвост еще ${tailRemaining}м"
            else             -> "завершена"
        }
        val glucoseUse = activityGlucoseUseMgdlPer5m(manual.mode, profileBasalUph, profileIsfMgdl)

        return ActivityContext(
            state = state,
            intensityScore = manual.mode.effectFraction * 10.0 * max(0.3, currentPhase),
            isRecovery = tailRemaining > 0 && now > activeEnd,
            isfMultiplier = 1.0 + effectNow,
            protectionMode = false,
            description = "${manual.mode.label} ${manual.durationMinutes}м: $phaseDescription, " +
                "ISF x${"%.2f".format(1.0 + effectNow)}, basal x${"%.2f".format(1.0 - effectNow)}, " +
                "новый инсулин x${"%.2f".format(newInsulinFactor)}, " +
                "расход ${"%.1f".format(glucoseUse)} mg/dL/5м",
            manualMode = manual.mode.label,
            effectFraction = manual.mode.effectFraction,
            currentPhase = currentPhase,
            newInsulinFactor = newInsulinFactor,
            startOffsetMinutes = startOffset,
            activeRemainingMinutes = activeRemaining,
            tailRemainingMinutes = tailRemaining,
            tailTotalMinutes = manual.tailMinutes,
            glucoseUseMgdlPer5m = glucoseUse
        )
    }

    private fun parseManualActivity(event: TE): ManualActivity? {
        val note = event.note
        if (note?.contains(NOTE_PREFIX) != true) return null
        val tokens = note.split(' ')
            .mapNotNull { part ->
                val splitAt = part.indexOf('=')
                if (splitAt <= 0 || splitAt >= part.lastIndex) null
                else part.substring(0, splitAt) to part.substring(splitAt + 1)
            }
            .toMap()
        val mode = when (tokens["mode"]?.uppercase()) {
            ManualMode.WALK.label  -> ManualMode.WALK
            ManualMode.SPORT.label -> ManualMode.SPORT
            else                   -> null
        } ?: return null
        val duration = (tokens["duration"]?.toIntOrNull() ?: (event.duration / 60_000L).toInt()).coerceAtLeast(5)
        val tail = (tokens["tail"]?.toIntOrNull() ?: tailMinutes(mode, duration)).coerceAtLeast(0)
        return ManualActivity(
            mode = mode,
            startMs = event.timestamp,
            durationMinutes = duration,
            tailMinutes = tail,
            note = note
        )
    }

    private fun mergeContexts(automatic: ActivityContext, manual: ActivityContext?): ActivityContext {
        if (manual == null) return automatic
        val manualRelevant = manual.manualMode != null &&
            (manual.startOffsetMinutes <= 6 * 60) &&
            (manual.startOffsetMinutes > 0 || manual.currentPhase > 0.0 || manual.tailRemainingMinutes > 0)
        if (!manualRelevant) return automatic

        // Manual WALK/SPORT is the only dosing input for Activity v2 for now.
        // Watch-derived steps/HR remain visible in logs, but must not silently
        // strengthen sensitivity, basal reduction, or protection decisions.
        val description = "${manual.description}; часы: ${automatic.description} (только наблюдение)"
        return manual.copy(
            state = manual.state,
            intensityScore = manual.intensityScore,
            isRecovery = manual.isRecovery,
            isfMultiplier = manual.isfMultiplier,
            protectionMode = manual.protectionMode,
            description = description
        )
    }

    private fun activityGlucoseUseMgdlPer5m(mode: ManualMode, profileBasalUph: Double, profileIsfMgdl: Double): Double {
        val insulinEquivalent = if (profileBasalUph > 0.0 && profileIsfMgdl > 0.0) {
            profileBasalUph * profileIsfMgdl * mode.effectFraction / 12.0
        } else {
            0.0
        }
        val movementUse = when (mode) {
            ManualMode.WALK  -> 0.8
            ManualMode.SPORT -> 1.2
        }
        val cap = when (mode) {
            ManualMode.WALK  -> 3.5
            ManualMode.SPORT -> 5.0
        }
        return (insulinEquivalent + movementUse).coerceIn(0.5, cap)
    }

    private fun minutesToMs(minutes: Int): Long = minutes * 60_000L

    private fun tailMinutes(mode: ManualMode, durationMinutes: Int): Int =
        when (mode) {
            ManualMode.WALK  -> when {
                durationMinutes >= 90 -> 30
                else                  -> 0
            }

            ManualMode.SPORT -> when {
                durationMinutes >= 90 -> 180
                durationMinutes >= 50 -> 120
                else                  -> 60
            }
        }
}
