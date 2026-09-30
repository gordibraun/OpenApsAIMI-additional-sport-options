package app.aaps.implementation.iob

import app.aaps.core.interfaces.aps.AutosensData
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.aps.MealAbsorptionSchedule
import java.util.Locale
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

class AutosensDataObject @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val dateUtil: DateUtil
) : AutosensData {

    override var time = 0L
    override var bg = 0.0 // mgdl
    override var sens = 0.0
    override var pastSensitivity = ""
    override var deviation = 0.0
    override var validDeviation = false
    override var activeCarbsList: MutableList<AutosensData.CarbsInPast> = ArrayList()
    override var this5MinAbsorption = 0.0
    override var carbsFromBolus = 0.0
    override var cob = 0.0
    override var bgi = 0.0
    override var delta = 0.0
    override var avgDelta = 0.0
    override var avgDeviation = 0.0
    override var autosensResult = AutosensResult()
    override var slopeFromMaxDeviation = 0.0
    override var slopeFromMinDeviation = 999.0
    override var usedMinCarbsImpact = 0.0
    override var failOverToMinAbsorptionRate = false

    // Oref1
    override var absorbing = false
    override var mealCarbs = 0.0
    override var mealStartCounter = 999
    override var type = ""
    override var uam = false
    override var extraDeviation: MutableList<Double> = ArrayList()
    private fun fromCarbsInPast(other: AutosensData.CarbsInPast): AutosensData.CarbsInPast =
        AutosensData.CarbsInPast(
            time = other.time,
            carbs = other.carbs,
            min5minCarbImpact = other.min5minCarbImpact,
            remaining = other.remaining,
            foodType = other.foodType
        )

    override fun toString(): String {
        return String.format(
            Locale.ENGLISH,
            "AutosensData: %s pastSensitivity=%s  delta=%.02f  avgDelta=%.02f bgi=%.02f deviation=%.02f avgDeviation=%.02f absorbed=%.02f carbsFromBolus=%.02f cob=%.02f autosensRatio=%.02f slopeFromMaxDeviation=%.02f slopeFromMinDeviation=%.02f activeCarbsList=%s",
            dateUtil.dateAndTimeString(time),
            pastSensitivity,
            delta,
            avgDelta,
            bgi,
            deviation,
            avgDeviation,
            this5MinAbsorption,
            carbsFromBolus,
            cob,
            autosensResult.ratio,
            slopeFromMaxDeviation,
            slopeFromMinDeviation,
            activeCarbsList.toString()
        )
    }

    override fun cloneCarbsList(): MutableList<AutosensData.CarbsInPast> {
        val newActiveCarbsList: MutableList<AutosensData.CarbsInPast> = ArrayList()
        for (c in activeCarbsList) {
            newActiveCarbsList.add(fromCarbsInPast(c))
        }
        return newActiveCarbsList
    }

    // remove carbs older than timeframe
    override fun removeOldCarbs(toTime: Long, isAAPSOrWeighted: Boolean) {
        val maxAbsorptionHours: Double =
            if (isAAPSOrWeighted) preferences.get(DoubleKey.AbsorptionMaxTime)
            else preferences.get(DoubleKey.AbsorptionMaxTime)
        val maxAbsorptionMs = (maxAbsorptionHours * 60 * 60 * 1000L).toLong()
        var i = 0
        while (i < activeCarbsList.size) {
            val c = activeCarbsList[i]
            val expirationMs = carbExpirationMs(c.foodType, maxAbsorptionMs)
            if (c.time + expirationMs < toTime) {
                activeCarbsList.removeAt(i--)
                if (c.remaining > 0) cob -= c.remaining
                aapsLogger.debug(
                    LTag.AUTOSENS,
                    "Removing carbs at " + dateUtil.dateAndTimeString(toTime) +
                        " after " + expirationMs / 60_000L + "m > " + c.toString()
                )
            }
            i++
        }
    }

    private fun carbExpirationMs(foodType: String?, maxAbsorptionMs: Long): Long {
        val typedAbsorptionMinutes = when (foodType?.lowercase()) {
            "fast" -> 45.0
            "balanced" -> 165.0
            "slow" -> 240.0
            else -> null
        }
        return typedAbsorptionMinutes
            ?.let { min(maxAbsorptionMs.toDouble(), it * 60_000.0).toLong() }
            ?: maxAbsorptionMs
    }

    override fun deductAbsorbedCarbs(beforeTime: Long) {
        var absorptionToAllocate = this5MinAbsorption.coerceAtLeast(0.0)
        if (absorptionToAllocate <= 0.0) return

        val activeCarbs = activeCarbsList
            .filter { it.remaining > 0.0 && it.time <= beforeTime }
            .toMutableList()
        if (activeCarbs.isEmpty()) return

        while (absorptionToAllocate > 0.0 && activeCarbs.isNotEmpty()) {
            val weightedCarbs = activeCarbs.map { it to carbAbsorptionWeight(it) }
            val totalWeight = weightedCarbs.sumOf { it.second }
            if (totalWeight <= 0.0) {
                deductAbsorbedCarbsProportionally(activeCarbs, absorptionToAllocate)
                return
            }

            var allocatedThisPass = 0.0
            for ((carb, weight) in weightedCarbs) {
                if (absorptionToAllocate <= 0.0) break
                if (carb.remaining <= 0.0 || weight <= 0.0) continue
                val requested = absorptionToAllocate * (weight / totalWeight)
                if (requested <= 0.0) continue
                val absorbed = min(requested, carb.remaining)
                carb.remaining -= absorbed
                allocatedThisPass += absorbed
            }
            activeCarbs.removeAll { it.remaining <= 0.0001 }

            if (allocatedThisPass <= 0.0001) {
                deductAbsorbedCarbsProportionally(activeCarbs, absorptionToAllocate)
                return
            }
            absorptionToAllocate -= allocatedThisPass
        }
    }

    private fun deductAbsorbedCarbsProportionally(activeCarbs: MutableList<AutosensData.CarbsInPast>, absorption: Double) {
        val totalRemaining = activeCarbs.sumOf { it.remaining }.coerceAtLeast(0.0)
        if (totalRemaining <= 0.0) return
        activeCarbs.forEach { carb ->
            val absorbed = min(absorption * (carb.remaining / totalRemaining), carb.remaining)
            carb.remaining -= absorbed
        }
    }

    private fun carbAbsorptionWeight(carbsInPast: AutosensData.CarbsInPast): Double {
        val elapsedMinutes = (time - carbsInPast.time) / 60_000.0
        if (elapsedMinutes <= 0.0) return 0.0
        val previousElapsedMinutes = max(0.0, elapsedMinutes - 5.0)
        val absorbedDelta = absorbedFraction(elapsedMinutes, carbsInPast.foodType) -
            absorbedFraction(previousElapsedMinutes, carbsInPast.foodType)
        return (carbsInPast.carbs * absorbedDelta).coerceAtLeast(0.0)
    }

    private fun absorbedFraction(elapsedMinutes: Double, foodType: String?): Double {
        if (elapsedMinutes <= 0.0) return 0.0
        return 1.0 - MealAbsorptionSchedule.remainingFraction(elapsedMinutes, foodType)
    }

}
