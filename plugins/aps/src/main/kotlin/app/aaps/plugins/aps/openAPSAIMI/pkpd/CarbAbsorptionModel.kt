package app.aaps.plugins.aps.openAPSAIMI.pkpd

import kotlin.math.exp
import kotlin.math.pow

object CarbAbsorptionModel {

    enum class FoodType(
        val displayName: String,
        val description: String,
        val peakMinutes: Double,
        val absorptionMinutes: Double
    ) {
        FAST(
            displayName = "Быстрые углеводы",
            description = "Очень ранний пик и короткий хвост. Подходит для сахара, сока и rescue-углеводов после низкой глюкозы.",
            peakMinutes = 15.0,
            absorptionMinutes = 45.0
        ),
        BALANCED(
            displayName = "Обычная еда",
            description = "Средний профиль всасывания для большинства обычных приёмов пищи.",
            peakMinutes = 50.0,
            absorptionMinutes = 165.0
        ),
        SLOW(
            displayName = "Медленная еда",
            description = "Поздний пик и длинный хвост. Подходит для жирной, белковой и растянутой по усвоению еды.",
            peakMinutes = 80.0,
            absorptionMinutes = 240.0
        )
    }

    data class Parameters(
        val peakMinutes: Double,
        val absorptionMinutes: Double
    )

    fun normalizeFoodType(selectedFoodType: String?): FoodType =
        when (selectedFoodType?.lowercase()) {
            "fast" -> FoodType.FAST
            "slow" -> FoodType.SLOW
            else -> FoodType.BALANCED
        }

    fun resolveParameters(cobG: Double, delta: Double, selectedFoodType: String? = null): Parameters {
        if (selectedFoodType == null) {
            return Parameters(
                peakMinutes = when {
                    cobG <= 0 -> 45.0
                    delta > 5.0 -> 25.0
                    delta > 2.0 -> 35.0
                    cobG >= 30.0 -> 45.0
                    cobG >= 15.0 -> 55.0
                    else -> 65.0
                },
                absorptionMinutes = when {
                    cobG <= 0 -> 180.0
                    delta > 5.0 -> 110.0
                    delta > 2.0 -> 130.0
                    cobG >= 30.0 -> 150.0
                    else -> 165.0
                }
            )
        }

        val foodType = normalizeFoodType(selectedFoodType)
        val peakShift = when {
            delta > 5.0 -> -10.0
            delta > 2.0 -> -5.0
            delta < -2.0 -> 10.0
            else -> 0.0
        }
        val absorptionShift = when {
            delta > 5.0 -> -20.0
            delta > 2.0 -> -10.0
            delta < -2.0 -> 20.0
            else -> 0.0
        }
        return Parameters(
            peakMinutes = (foodType.peakMinutes + peakShift).coerceAtLeast(20.0),
            absorptionMinutes = (foodType.absorptionMinutes + absorptionShift).coerceAtLeast(
                if (foodType == FoodType.FAST) 35.0 else 90.0
            )
        )
    }

    fun buildWeights(
        steps: Int,
        peakMinutes: Double,
        absorptionMinutes: Double
    ): DoubleArray {
        if (steps <= 0) return doubleArrayOf()
        val peak = peakMinutes.coerceIn(15.0, absorptionMinutes.coerceAtLeast(30.0))
        val sigma = (absorptionMinutes / 3.2).coerceAtLeast(20.0)
        val weights = DoubleArray(steps)
        var sum = 0.0
        for (index in 0 until steps) {
            val minutes = (index + 1) * 5.0
            if (minutes > absorptionMinutes) {
                weights[index] = 0.0
                continue
            }
            val value = exp(-0.5 * ((minutes - peak) / sigma).pow(2.0))
            weights[index] = value
            sum += value
        }
        if (sum <= 0.0) return DoubleArray(steps) { 1.0 / steps }
        return DoubleArray(steps) { idx -> weights[idx] / sum }
    }

    fun buildWeights(steps: Int, foodType: FoodType): DoubleArray =
        buildWeights(steps, foodType.peakMinutes, foodType.absorptionMinutes)
}
