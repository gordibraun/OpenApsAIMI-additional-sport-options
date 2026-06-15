package app.aaps.plugins.aps.openAPSAIMI.meal

import app.aaps.core.interfaces.aps.AimiMealAssist
import app.aaps.core.interfaces.aps.AimiMealDecision
import app.aaps.core.interfaces.aps.AimiMealEpisode
import app.aaps.core.interfaces.aps.AimiMealInput
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.pkpd.CarbAbsorptionModel
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.roundToInt

@Singleton
class AimiMealAssistImpl @Inject constructor(
    private val logger: AAPSLogger,
    private val preferences: Preferences
) : AimiMealAssist {

    private data class FoodTypeModifier(
        val carbFactor: Double,
        val prebolusFactor: Double,
        val label: String
    )

    private val activeEpisodesRef = AtomicReference<List<AimiMealEpisode>>(emptyList())
    private val lastAcceptedTreatmentAtRef = AtomicLong(0L)

    override fun evaluate(input: AimiMealInput): AimiMealDecision {
        val targetBg = (input.targetBgLow + input.targetBgHigh) / 2.0
        val mealMode = detectMealMode(input)
        val baseModeFactor = when (mealMode) {
            "highcarb"  -> preferences.get(DoubleKey.OApsAIMIHCFactor) / 100.0
            "meal"      -> preferences.get(DoubleKey.OApsAIMIMealFactor) / 100.0
            "breakfast" -> preferences.get(DoubleKey.OApsAIMIBFFactor) / 100.0
            "lunch"     -> preferences.get(DoubleKey.OApsAIMILunchFactor) / 100.0
            "dinner"    -> preferences.get(DoubleKey.OApsAIMIDinnerFactor) / 100.0
            "snack"     -> preferences.get(DoubleKey.OApsAIMISnackFactor) / 100.0
            else        -> 1.0
        }
        val basePrebolusBonus = when (mealMode) {
            "highcarb"  -> preferences.get(DoubleKey.OApsAIMIHighCarbPrebolus)
            "meal"      -> preferences.get(DoubleKey.OApsAIMIMealPrebolus)
            "breakfast" -> preferences.get(DoubleKey.OApsAIMIBFPrebolus)
            "lunch"     -> preferences.get(DoubleKey.OApsAIMILunchPrebolus)
            "dinner"    -> preferences.get(DoubleKey.OApsAIMIDinnerPrebolus)
            "snack"     -> preferences.get(DoubleKey.OApsAIMISnackPrebolus)
            else        -> 0.0
        }
        val foodTypeModifier = foodTypeModifier(input.selectedFoodType)
        val modeFactor = baseModeFactor * foodTypeModifier.carbFactor
        val prebolusBonus = basePrebolusBonus * foodTypeModifier.prebolusFactor
        val activityNewInsulinFactor = input.activityNewInsulinFactor.coerceIn(0.55, 1.0)

        val protectiveCarbs = input.requiredCarbs.coerceAtLeast(0)
        val netCarbs = (input.carbs - protectiveCarbs).coerceAtLeast(0)
        val carbCoverageRatio = when {
            input.carbs <= 0 -> 0.0
            else -> netCarbs.toDouble() / input.carbs.toDouble()
        }
        val manualCorrection = input.correction
        val carbComponent = input.wizardInsulinFromCarbs * carbCoverageRatio
        val baseWithoutCarbs = input.wizardCalculatedBolus - input.wizardInsulinFromCarbs
        val baseWithoutCarbsAndManualCorrection = baseWithoutCarbs - manualCorrection
        val effectiveBaseWithoutCarbs = when {
            protectiveCarbs > 0 -> max(0.0, baseWithoutCarbsAndManualCorrection)
            else -> baseWithoutCarbsAndManualCorrection
        }
        val adjustedCarbComponent = when {
            netCarbs <= 0 -> 0.0
            else             -> carbComponent * modeFactor
        }
        val prebolusApplied = if (protectiveCarbs == 0 && netCarbs > 0 && input.carbTimeMinutes >= 0) prebolusBonus else 0.0
        val baseRecommendationBeforeForecast = when {
            protectiveCarbs > 0 && input.carbs < protectiveCarbs -> 0.0
            else -> max(0.0, effectiveBaseWithoutCarbs + adjustedCarbComponent + prebolusApplied)
        }
        val recommendationBeforeActivity = baseRecommendationBeforeForecast
        val recommendationBeforeManualCorrection =
            if (activityNewInsulinFactor < 0.999) recommendationBeforeActivity * activityNewInsulinFactor
            else recommendationBeforeActivity
        val rawRecommendation = max(0.0, recommendationBeforeManualCorrection + manualCorrection)
        val expectedEventualBg = when {
            netCarbs > 0 && rawRecommendation > 0.0 -> targetBg
            input.carbs > 0                          -> max(targetBg, input.bg)
            else                                        -> input.bg
        }
        val roundedRecommendation = ((rawRecommendation * 20.0).roundToInt() / 20.0)

        return AimiMealDecision(
            createdAt = input.timestamp,
            recommendedBolus = roundedRecommendation,
            targetBg = targetBg,
            expectedEventualBg = expectedEventualBg,
            confidence = 0.35,
            mealMode = mealMode,
            modeFactor = modeFactor,
            prebolusBonus = prebolusApplied,
            source = "AIMI meal wizard",
            explanation = buildString {
                append("AIMI mode=$mealMode. ")
                append("Food type=${foodTypeModifier.label}. ")
                if (protectiveCarbs > 0) {
                    append("Protective carbs=${protectiveCarbs}g, net carbs=${netCarbs}g. ")
                }
                if (protectiveCarbs > 0 && input.carbs < protectiveCarbs) {
                    append("Entered carbs below required carbs, bolus forced to 0U. ")
                } else if (protectiveCarbs > 0 && netCarbs > 0) {
                    append("Protective carbs covered, dosing only excess carbs above requirement. ")
                }
                append("Base(without carbs)=${"%.2f".format(baseWithoutCarbsAndManualCorrection)}U, ")
                append("carbs ${"%.2f".format(carbComponent)}U x ${"%.2f".format(modeFactor)} = ${"%.2f".format(adjustedCarbComponent)}U")
                if (prebolusApplied > 0.0) {
                    append(", prebolus +${"%.2f".format(prebolusApplied)}U")
                }
                if (activityNewInsulinFactor < 0.999) {
                    append(", нагрузка новый инсулин x${"%.2f".format(activityNewInsulinFactor)}")
                    input.activityDescription?.let { append(" ($it)") }
                }
                if (manualCorrection != 0.0) {
                    append(", ручная коррекция ${"%.2f".format(manualCorrection)}U")
                }
                append(" -> bolus ${"%.2f".format(roundedRecommendation)}U")
            }
        )
    }

    override fun activate(input: AimiMealInput, decision: AimiMealDecision): AimiMealEpisode {
        val protectiveCarbs = input.requiredCarbs.coerceAtLeast(0).coerceAtMost(input.carbs)
        val cobHandledCarbs = when {
            input.carbs <= 0                    -> 0
            decision.recommendedBolus > 0.0     -> input.carbs
            else                                -> protectiveCarbs
        }
        val episode = AimiMealEpisode(
            startedAt = input.timestamp,
            profileName = input.profileName,
            selectedFoodType = input.selectedFoodType,
            carbs = input.carbs,
            cobHandledCarbs = cobHandledCarbs,
            deliveredBolus = decision.recommendedBolus,
            carbTimeMinutes = input.carbTimeMinutes,
            targetBg = decision.targetBg,
            expectedEventualBg = decision.expectedEventualBg,
            source = decision.source,
            notes = input.notes
        )
        val activeEpisodes = pruneActiveEpisodes(activeEpisodesRef.get(), input.timestamp)
        activeEpisodesRef.set((activeEpisodes + episode).takeLast(MAX_ACTIVE_EPISODES))
        logger.debug(
            LTag.APS,
            "AIMI meal episode activated: carbs=${input.carbs} cobHandled=$cobHandledCarbs bolus=${"%.2f".format(decision.recommendedBolus)} " +
                "target=${"%.0f".format(decision.targetBg)} expected=${"%.0f".format(decision.expectedEventualBg)}"
        )
        return episode
    }

    override fun activeEpisode(): AimiMealEpisode? {
        val now = System.currentTimeMillis()
        val activeEpisodes = pruneActiveEpisodes(activeEpisodesRef.get(), now)
        if (activeEpisodes.size != activeEpisodesRef.get().size) {
            activeEpisodesRef.set(activeEpisodes)
        }
        return effectiveForecastEpisode(activeEpisodes, now)
    }

    override fun markTreatmentAccepted(timestamp: Long) {
        lastAcceptedTreatmentAtRef.set(timestamp.coerceAtLeast(0L))
    }

    override fun clearPendingTreatment() {
        lastAcceptedTreatmentAtRef.set(0L)
    }

    override fun lastTreatmentAcceptedAt(): Long = lastAcceptedTreatmentAtRef.get()

    private fun detectMealMode(input: AimiMealInput): String {
        if (input.carbs <= 0) return "correction"
        if (input.carbs >= 40) return "highcarb"
        if (input.carbs <= 15) return "snack"

        val hour = Instant.ofEpochMilli(input.timestamp).atZone(ZoneId.systemDefault()).hour
        return when (hour) {
            in 4..10  -> "breakfast"
            in 11..15 -> "lunch"
            in 17..22 -> "dinner"
            else      -> "meal"
        }
    }

    private fun foodTypeModifier(selectedFoodType: String?): FoodTypeModifier =
        when (selectedFoodType?.lowercase()) {
            "fast" -> FoodTypeModifier(
                carbFactor = 0.80,
                prebolusFactor = 0.0,
                label = "быстрые углеводы: раннее всасывание, bolus осторожнее"
            )
            "slow" -> FoodTypeModifier(
                carbFactor = 0.92,
                prebolusFactor = 0.35,
                label = "медленная еда"
            )
            else -> FoodTypeModifier(
                carbFactor = 1.0,
                prebolusFactor = 1.0,
                label = "обычная еда"
            )
        }

    private fun effectiveForecastEpisode(episodes: List<AimiMealEpisode>, now: Long): AimiMealEpisode? {
        if (episodes.isEmpty()) return null

        val remainingByType = episodes
            .groupBy { normalizeFoodType(it.selectedFoodType) }
            .mapValues { (_, typedEpisodes) ->
                typedEpisodes.sumOf { episode -> episode.carbs * remainingFraction(episode, now) }
            }
            .filterValues { it > 0.5 }
        if (remainingByType.isEmpty()) {
            logger.debug(LTag.APS, "AIMI активные углеводы закончились: остаток меньше 0.5 г")
            return null
        }

        val totalRemaining = remainingByType.values.sum()
        val totalHandledRemaining = episodes
            .sumOf { episode -> episode.cobHandledCarbs * remainingFraction(episode, now) }
            .coerceIn(0.0, totalRemaining)
        val dominant = remainingByType.maxByOrNull { it.value }
        val dominantShare = dominant?.value?.div(totalRemaining) ?: 0.0
        val forecastFoodType = if (dominant != null && dominantShare >= DOMINANT_TYPE_SHARE) {
            dominant.key
        } else {
            "balanced"
        }
        if (forecastFoodType == "balanced" && remainingByType.size > 1) {
            logger.debug(
                LTag.APS,
                "AIMI mixed active carb types: " +
                    remainingByType.entries.joinToString { "${it.key}=${"%.1f".format(it.value)}g" } +
                    " -> balanced forecast"
            )
        }

        val latest = episodes.maxByOrNull { it.startedAt } ?: return null
        return latest.copy(
            startedAt = episodes.minOf { it.startedAt },
            selectedFoodType = forecastFoodType,
            carbs = totalRemaining.roundToInt().coerceAtLeast(0),
            cobHandledCarbs = totalHandledRemaining.roundToInt().coerceAtLeast(0),
            deliveredBolus = episodes.sumOf { it.deliveredBolus },
            carbTimeMinutes = 0,
            notes = "AIMI активные углеводы: " + remainingByType.entries.joinToString { "${it.key}=${"%.1f".format(it.value)}g" } +
                "; COB уже покрыто=${"%.1f".format(totalHandledRemaining)}г"
        )
    }

    private fun pruneActiveEpisodes(episodes: List<AimiMealEpisode>, now: Long): List<AimiMealEpisode> =
        episodes.filter { episode ->
            episode.carbs > 0 &&
                now - episode.startedAt <= activeWindowMinutes(normalizeFoodType(episode.selectedFoodType)) * 60_000L &&
                episode.carbs * remainingFraction(episode, now) > 0.5
        }

    private fun remainingFraction(episode: AimiMealEpisode, now: Long): Double {
        val carbStart = episode.startedAt + episode.carbTimeMinutes * 60_000L
        val minutesSinceCarbs = (now - carbStart) / 60_000.0
        return CarbAbsorptionModel.remainingFraction(
            elapsedMinutes = minutesSinceCarbs,
            selectedFoodType = normalizeFoodType(episode.selectedFoodType)
        )
    }

    private fun normalizeFoodType(selectedFoodType: String?): String =
        when (selectedFoodType?.lowercase()) {
            "fast" -> "fast"
            "slow" -> "slow"
            else -> "balanced"
        }

    private fun activeWindowMinutes(foodType: String): Long =
        when (foodType) {
            "fast" -> 75L
            "slow" -> 300L
            else -> 210L
        }

    companion object {
        private const val MAX_ACTIVE_EPISODES = 12
        private const val DOMINANT_TYPE_SHARE = 0.75
    }
}
