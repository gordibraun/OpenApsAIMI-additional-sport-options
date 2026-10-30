package app.aaps.core.objects.activity

import kotlin.math.ceil
import kotlin.math.max

/** Shared activity preview; the phone remains the authority for both phone and watch entry. */
object ActivityPlanCalculator {
    fun endWithTail(startMs: Long, durationMs: Long, note: String?): Long? {
        if (note?.startsWith("AIMI_ACTIVITY_V2 ") != true) return null
        val tokens = note.split(' ').mapNotNull { part ->
            val pair = part.split('=', limit = 2)
            if (pair.size == 2) pair[0] to pair[1] else null
        }.toMap()
        val mode = tokens["mode"]?.takeIf { it in setOf("WALK", "SPORT") } ?: return null
        val tail = (tokens["tail"]?.toIntOrNull() ?: tailMinutes(mode, (durationMs / 60_000).toInt())).coerceIn(0, 360)
        return startMs + durationMs + tail * 60_000L
    }
    data class Plan(
        val mode: String,
        val effectPercent: Int,
        val startOffsetMinutes: Int,
        val durationMinutes: Int,
        val tailMinutes: Int,
        val activityWindowEndMinutes: Int,
        val requiredCarbs: Int,
        val baseRequiredCarbs: Int,
        val activityRequiredCarbs: Int,
        val carbsWithinMinutes: Int,
        val carbType: String?,
        val forecastMin: Double,
        val forecastMinMinute: Int,
        val lateForecastMin: Double?,
        val lateForecastMinMinute: Int?,
        val glucoseUseMgdlPer5m: Double,
        val carbSensitivityMgdlPerGram: Double,
        val activityEquivalentCarbs: Double,
        val activityCarbFloorMgdl: Double,
        val baseDeficitMgdl: Double,
        val activityDeficitMgdl: Double,
        val totalDeficitMgdl: Double,
        val firstRiskMinute: Int?,
        val firstActivityImpactMinute: Int?,
        val carbLeadMinutes: Int
    )

    fun effectPercent(mode: String): Int =
        if (mode == "SPORT") 30 else 20

    /**
     * The window and the tail only, with no carbohydrate advice: what is recorded when the forecast
     * an advice needs is not available. The session itself must never go unrecorded for that
     * (30.10.2026: the owner's entries used to be refused on a stale loop run).
     */
    fun recordOnly(mode: String, durationMinutes: Int, startOffsetMinutes: Int, carbType: String?): Plan {
        val tail = tailMinutes(mode, durationMinutes)
        return Plan(
            mode = mode, effectPercent = effectPercent(mode), startOffsetMinutes = startOffsetMinutes, durationMinutes = durationMinutes,
            tailMinutes = tail, activityWindowEndMinutes = startOffsetMinutes + durationMinutes + tail,
            requiredCarbs = 0, baseRequiredCarbs = 0, activityRequiredCarbs = 0, carbsWithinMinutes = 0, carbType = carbType,
            forecastMin = Double.NaN, forecastMinMinute = 0, lateForecastMin = null, lateForecastMinMinute = null,
            glucoseUseMgdlPer5m = 0.0, carbSensitivityMgdlPerGram = 0.0, activityEquivalentCarbs = 0.0, activityCarbFloorMgdl = 0.0,
            baseDeficitMgdl = 0.0, activityDeficitMgdl = 0.0, totalDeficitMgdl = 0.0, firstRiskMinute = null, firstActivityImpactMinute = null,
            carbLeadMinutes = 0
        )
    }

    /**
     * How long after a session insulin keeps working better - the tail in which the loop's ISF is
     * raised, basal and new insulin cut, micro-doses capped and the watch alone adds no insulin.
     * Lengthened 30.10.2026 at the owner's word: the night of 29/30.10 brought a two-hour low six
     * hours after an evening session whose tail had ended four hours earlier. Physiology puts the
     * effect at 8–24 h after hard exercise; this stays on the cautious side of that.
     */
    fun tailMinutes(mode: String, durationMinutes: Int): Int =
        when (mode) {
            "SPORT" -> if (durationMinutes >= 50) 480 else 360
            else    -> when {
                durationMinutes >= 90 -> 180
                durationMinutes >= 30 -> 120
                else                  -> 60
            }
        }

    /** The first part of the tail in which the effect holds in full before it fades. */
    fun tailHoldMinutes(mode: String): Int = if (mode == "SPORT") 60 else 30

    /** The insulin effect inside the tail: full for the hold, then fading in a straight line to nothing at the tail's end. */
    fun tailPhase(mode: String, tailMinutes: Int, minutesIntoTail: Double): Double {
        if (tailMinutes <= 0 || minutesIntoTail < 0.0 || minutesIntoTail >= tailMinutes) return 0.0
        val hold = tailHoldMinutes(mode).coerceAtMost(tailMinutes)
        if (minutesIntoTail <= hold) return 1.0
        return (1.0 - (minutesIntoTail - hold) / (tailMinutes - hold).toDouble()).coerceIn(0.0, 1.0)
    }

    /**
     * How long after the session the movement itself still takes glucose off the forecast: the
     * short tail of before. The long [tailMinutes] is about insulin working better, not about
     * glucose being burnt, so forecasts and carbohydrate advice keep this one.
     */
    fun useTailMinutes(mode: String, durationMinutes: Int): Int =
        when (mode) {
            "SPORT" -> when {
                durationMinutes >= 90 -> 180
                durationMinutes >= 50 -> 120
                else                  -> 60
            }

            else    -> if (durationMinutes >= 90) 30 else 0
        }

    fun note(
        mode: String,
        effectPercent: Int,
        startOffsetMinutes: Int,
        durationMinutes: Int,
        tailMinutes: Int,
        requiredCarbs: Int,
        carbType: String?
    ): String =
        "AIMI_ACTIVITY_V2 mode=$mode effect=$effectPercent startOffset=$startOffsetMinutes " +
            "duration=$durationMinutes tail=$tailMinutes requiredCarbs=$requiredCarbs " +
            "carbType=${carbType ?: "none"}"

    private fun activityGlucoseUseMgdlPer5m(mode: String, effectPercent: Int, basal: Double, isf: Double): Double {
        val insulinEquivalent = if (basal > 0.0 && isf > 0.0) basal * isf * (effectPercent / 100.0) / 12.0 else 0.0
        val movementUse = if (mode == "SPORT") 1.2 else 0.8
        val cap = if (mode == "SPORT") 5.0 else 3.5
        return (insulinEquivalent + movementUse).coerceIn(0.5, cap)
    }

    private fun activityPhaseAtMinute(minute: Int, startOffset: Int, duration: Int, tail: Int): Double =
        when {
            minute < startOffset -> 0.0
            minute <= startOffset + duration -> 1.0
            tail > 0 && minute <= startOffset + duration + tail ->
                (1.0 - (minute - startOffset - duration).toDouble() / tail.toDouble()).coerceIn(0.0, 1.0)
            else -> 0.0
        }

    private fun activityTotalUseMgdl(startOffset: Int, duration: Int, tail: Int, glucoseUseMgdlPer5m: Double): Double {
        val windowEnd = startOffset + duration + tail
        var total = 0.0
        var minute = 5
        while (minute <= windowEnd) {
            total += glucoseUseMgdlPer5m * activityPhaseAtMinute(minute, startOffset, duration, tail)
            minute += 5
        }
        return total
    }

    fun build(mode: String, duration: Int, startOffset: Int, carbType: String?,
              basal: Double, isf: Double, ic: Double, target: Double, lowTarget: Double,
              overviewLowMark: Double, currentBg: Double, forecast: List<Double>, carbIsf: Double = isf): Plan {
        require(mode in setOf("WALK", "SPORT"))
        require(duration in setOf(30, 50, 90) && startOffset in setOf(0, 20, 30, 50, 60))
        val effectPercent = effectPercent(mode)
        val tail = tailMinutes(mode, duration)
        // Forecast, deficits and carbohydrate advice follow the movement's glucose use, i.e. the short tail.
        val useTail = useTailMinutes(mode, duration)
        val windowEnd = startOffset + duration + useTail
        val glucoseUse = activityGlucoseUseMgdlPer5m(mode, effectPercent, basal, isf)
        val minSteps = (windowEnd / 5).coerceAtLeast(48)
        val base = forecast.ifEmpty { List(48) { currentBg } }
        val baseForecast = base + List((minSteps - base.size).coerceAtLeast(0)) { base.last() }
        val adjustedForecast = activityAdjustedForecast(baseForecast, startOffset, duration, useTail, glucoseUse)
        val forecastPoints = adjustedForecast.mapIndexed { index, value -> ((index + 1) * 5) to value }
        val minPoint = forecastPoints
            .filter { (minute, _) -> minute in max(5, startOffset)..windowEnd }
            .minByOrNull { (_, value) -> value }
        val lateMinPoint = forecastPoints
            .filter { (minute, _) -> minute > windowEnd }
            .minByOrNull { (_, value) -> value }
        val forecastMin = minPoint?.second ?: currentBg
        val forecastMinMinute = minPoint?.first ?: 0
        val profileLowTarget = lowTarget.takeIf { it.isFinite() && it > 0.0 } ?: overviewLowMark
        val activityCarbFloor = max(overviewLowMark + 10.0, profileLowTarget)
            .coerceAtMost(target.takeIf { it.isFinite() } ?: forecastMin)
        val csf = if (carbIsf > 0.0 && ic > 0.0) carbIsf / ic else 0.0
        val baseDeficit = activityBaseDeficitMgdl(
            baseForecast = baseForecast,
            windowEnd = windowEnd,
            target = activityCarbFloor
        )
        val activityDeficit = activityAddedDeficitMgdl(
            baseForecast = baseForecast,
            adjustedForecast = adjustedForecast,
            startOffset = startOffset,
            windowEnd = windowEnd,
            target = activityCarbFloor
        )
        val totalDeficit = (baseDeficit + activityDeficit).coerceAtLeast(
            activityTotalDeficitMgdl(
                adjustedForecast = adjustedForecast,
                windowEnd = windowEnd,
                target = activityCarbFloor
            )
        )
        val baseRequiredCarbs = carbsForDeficitMgdl(baseDeficit, csf)
        val requiredCarbs = carbsForDeficitMgdl(totalDeficit, csf)
        val activityRequiredCarbs = (requiredCarbs - baseRequiredCarbs).coerceAtLeast(0)
        val firstBaseRiskMinute = firstDeficitMinute(
            baseForecast = baseForecast,
            windowEnd = windowEnd,
            target = activityCarbFloor
        )
        val firstActivityImpactMinute = firstActivityImpactMinute(
            baseForecast = baseForecast,
            adjustedForecast = adjustedForecast,
            startOffset = startOffset,
            windowEnd = windowEnd,
            target = activityCarbFloor
        )
        val firstRiskMinute = listOfNotNull(
            firstBaseRiskMinute.takeIf { baseRequiredCarbs > 0 },
            firstActivityImpactMinute.takeIf { activityRequiredCarbs > 0 }
        ).minOrNull()
        val carbLead = when (carbType) {
            "fast" -> 10
            "balanced" -> 25
            else -> 15
        }
        val within = if (requiredCarbs > 0) ((firstRiskMinute ?: forecastMinMinute) - carbLead).coerceIn(0, windowEnd) else 0
        val activityEquivalentCarbs = if (csf > 0.0) activityTotalUseMgdl(startOffset, duration, useTail, glucoseUse) / csf else 0.0

        return Plan(
            mode = mode,
            effectPercent = effectPercent,
            startOffsetMinutes = startOffset,
            durationMinutes = duration,
            tailMinutes = tail,
            activityWindowEndMinutes = windowEnd,
            requiredCarbs = requiredCarbs,
            baseRequiredCarbs = baseRequiredCarbs,
            activityRequiredCarbs = activityRequiredCarbs,
            carbsWithinMinutes = within,
            carbType = carbType,
            forecastMin = forecastMin,
            forecastMinMinute = forecastMinMinute,
            lateForecastMin = lateMinPoint?.second,
            lateForecastMinMinute = lateMinPoint?.first,
            glucoseUseMgdlPer5m = glucoseUse,
            carbSensitivityMgdlPerGram = csf,
            activityEquivalentCarbs = activityEquivalentCarbs,
            activityCarbFloorMgdl = activityCarbFloor,
            baseDeficitMgdl = baseDeficit,
            activityDeficitMgdl = activityDeficit,
            totalDeficitMgdl = totalDeficit,
            firstRiskMinute = firstRiskMinute,
            firstActivityImpactMinute = firstActivityImpactMinute,
            carbLeadMinutes = carbLead
        )
    }

    private fun activityAdjustedForecast(base: List<Double>, startOffset: Int, duration: Int, tail: Int, glucoseUseMgdlPer5m: Double): List<Double> {
        var accumulatedUse = 0.0
        return base.mapIndexed { index, predicted ->
            val minute = (index + 1) * 5
            val phase = activityPhaseAtMinute(minute, startOffset, duration, tail)
            accumulatedUse += glucoseUseMgdlPer5m * phase
            predicted - accumulatedUse
        }
    }

    private fun activityBaseDeficitMgdl(
        baseForecast: List<Double>,
        windowEnd: Int,
        target: Double
    ): Double =
        baseForecast
            .mapIndexedNotNull { index, base ->
                val minute = (index + 1) * 5
                if (minute !in 5..windowEnd) null else (target - base).coerceAtLeast(0.0)
            }
            .maxOrNull()
            ?: 0.0

    private fun activityTotalDeficitMgdl(
        adjustedForecast: List<Double>,
        windowEnd: Int,
        target: Double
    ): Double =
        adjustedForecast
            .mapIndexedNotNull { index, adjusted ->
                val minute = (index + 1) * 5
                if (minute !in 5..windowEnd) null else (target - adjusted).coerceAtLeast(0.0)
            }
            .maxOrNull()
            ?: 0.0

    private fun activityAddedDeficitMgdl(
        baseForecast: List<Double>,
        adjustedForecast: List<Double>,
        startOffset: Int,
        windowEnd: Int,
        target: Double
    ): Double =
        baseForecast
            .zip(adjustedForecast)
            .mapIndexedNotNull { index, (base, adjusted) ->
                val minute = (index + 1) * 5
                if (minute !in max(5, startOffset)..windowEnd) {
                    null
                } else {
                    val baseDeficit = (target - base).coerceAtLeast(0.0)
                    val adjustedDeficit = (target - adjusted).coerceAtLeast(0.0)
                    (adjustedDeficit - baseDeficit).coerceAtLeast(0.0)
                }
            }
            .maxOrNull()
            ?: 0.0

    private fun carbsForDeficitMgdl(deficitMgdl: Double, csf: Double): Int =
        if (csf > 0.0 && deficitMgdl >= 3.0) ceil(deficitMgdl / csf).toInt().coerceAtMost(60) else 0

    private fun firstDeficitMinute(baseForecast: List<Double>, windowEnd: Int, target: Double): Int? =
        baseForecast
            .mapIndexedNotNull { index, base ->
                val minute = (index + 1) * 5
                if (minute in 5..windowEnd && target - base >= 3.0) minute else null
            }
            .minOrNull()

    private fun firstActivityImpactMinute(
        baseForecast: List<Double>,
        adjustedForecast: List<Double>,
        startOffset: Int,
        windowEnd: Int,
        target: Double
    ): Int? =
        baseForecast
            .zip(adjustedForecast)
            .mapIndexedNotNull { index, (base, adjusted) ->
                val minute = (index + 1) * 5
                val activeWindow = minute in max(5, startOffset)..windowEnd
                val addedDrop = base - adjusted
                val addedDeficit = (target - adjusted).coerceAtLeast(0.0) - (target - base).coerceAtLeast(0.0)
                if (activeWindow && (addedDeficit >= 3.0 || addedDrop >= 3.0)) minute else null
            }
            .minOrNull()

}
