package app.aaps.plugins.aps.openAPSAIMI.meal

import app.aaps.core.interfaces.aps.AimiMealAssist
import app.aaps.core.interfaces.aps.AimiMealDecision
import app.aaps.core.interfaces.aps.AimiMealEpisode
import app.aaps.core.interfaces.aps.AimiMealInput
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.PendingWizardTreatment
import app.aaps.core.interfaces.sharedPreferences.SP
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.pkpd.CarbAbsorptionModel
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

@Singleton
class AimiMealAssistImpl @Inject constructor(
    private val logger: AAPSLogger,
    private val sp: SP
) : AimiMealAssist {

    private data class FoodTypeModifier(
        val carbFactor: Double,
        val label: String
    )

    private val activeEpisodesRef = AtomicReference<List<AimiMealEpisode>>(emptyList())
    private val lastAcceptedTreatmentAtRef = AtomicLong(0L)
    private val pendingKey = "aimi_pending_wizard_treatment_v1"

    override fun evaluate(input: AimiMealInput): AimiMealDecision {
        val targetBg = (input.targetBgLow + input.targetBgHigh) / 2.0
        // Manual-mode SMB factors and fixed preboluses belong to automation, not the wizard.
        val mealMode = if (input.carbs > 0) "meal" else "correction"
        val foodTypeModifier = foodTypeModifier(input.selectedFoodType)
        val foodFactor = foodTypeModifier.carbFactor
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
        val recentMealTopUpCredit = recentMealTopUpBolusCredit(input)
        val topUpAdjustedBaseWithoutCarbs = baseWithoutCarbsAndManualCorrection + recentMealTopUpCredit
        val effectiveBaseWithoutCarbs = when {
            protectiveCarbs > 0 -> max(0.0, topUpAdjustedBaseWithoutCarbs)
            else -> topUpAdjustedBaseWithoutCarbs
        }
        val adjustedCarbComponent = when {
            netCarbs <= 0 -> 0.0
            else             -> carbComponent * foodFactor
        }
        val baseRecommendationBeforeForecast = when {
            protectiveCarbs > 0 && input.carbs <= protectiveCarbs -> 0.0
            else -> max(0.0, effectiveBaseWithoutCarbs + adjustedCarbComponent)
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
            modeFactor = foodFactor,
            prebolusBonus = 0.0,
            source = "AIMI meal wizard",
            explanation = buildString {
                append("Ручной расчёт; коэффициенты и предболюсы режимов не применяются. ")
                append("Food type=${foodTypeModifier.label}. ")
                if (protectiveCarbs > 0) {
                    append("Protective carbs=${protectiveCarbs}g, net carbs=${netCarbs}g. ")
                }
                if (protectiveCarbs > 0 && input.carbs <= protectiveCarbs) {
                    append("Введено не больше требуемых углеводов: автоматическая прибавка инсулина 0 Е. ")
                } else if (protectiveCarbs > 0 && netCarbs > 0) {
                    append("Protective carbs covered, dosing only excess carbs above requirement. ")
                }
                append("Base(without carbs)=${"%.2f".format(baseWithoutCarbsAndManualCorrection)}U, ")
                append("carbs ${"%.2f".format(carbComponent)}U x ${"%.2f".format(foodFactor)} = ${"%.2f".format(adjustedCarbComponent)}U")
                if (activityNewInsulinFactor < 0.999) {
                    append(", нагрузка новый инсулин x${"%.2f".format(activityNewInsulinFactor)}")
                    input.activityDescription?.let { append(" ($it)") }
                }
                if (recentMealTopUpCredit > 0.0) {
                    append(", добавка к свежей еде: предыдущий болюс не гасит новые угли +${"%.2f".format(recentMealTopUpCredit)}U")
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

    @Synchronized
    override fun pendingTreatment(): PendingWizardTreatment? {
        val saved = sp.getString(pendingKey, "")
        if (saved.isEmpty()) return null
        return try {
            Json.decodeFromString<PendingWizardTreatment>(saved)
        } catch (e: Exception) {
            logger.error("Cannot read pending wizard delivery", e)
            // Corrupt or unknown persisted delivery must not silently authorize another dose.
            PendingWizardTreatment(0L, 0L, 0.0, 0L, 0.0)
        }
    }

    @Synchronized
    override fun beginTreatment(treatment: PendingWizardTreatment): Boolean {
        if (pendingTreatment() != null) return false
        sp.edit(commit = true) { putString(pendingKey, Json.encodeToString(treatment)) }
        markTreatmentAccepted(treatment.acceptedAt)
        return true
    }

    @Synchronized
    override fun completeTreatment(acceptedAt: Long, bolusTimestamp: Long, deliveredInsulin: Double, success: Boolean) {
        val pending = pendingTreatment()?.takeIf { it.acceptedAt == acceptedAt } ?: return
        // A positive partial delivery still has to reach the IOB input, even when the command failed.
        if (!deliveredInsulin.isFinite() || deliveredInsulin < 0.0 || (!success && deliveredInsulin == 0.0)) return
        val confirmed = pending.copy(
            bolusTimestamp = bolusTimestamp, insulin = deliveredInsulin, deliveryConfirmed = true
        )
        sp.edit(commit = true) { putString(pendingKey, Json.encodeToString(confirmed)) }
    }

    @Synchronized
    override fun rejectTreatment(acceptedAt: Long) {
        // Only used when the command queue reports that the request was not queued at all.
        if (pendingTreatment()?.acceptedAt == acceptedAt) sp.edit(commit = true) { remove(pendingKey) }
    }

    @Synchronized
    override fun acknowledgeTreatment(iob: IobTotal?, meal: MealData?) {
        if (pendingTreatment()?.includedIn(iob, meal) == true) sp.edit(commit = true) { remove(pendingKey) }
    }

    @Synchronized
    override fun confirmManualTreatmentReview(acceptedAt: Long, reviewedAt: Long): Boolean {
        val pending = pendingTreatment()?.takeIf { it.acceptedAt == acceptedAt } ?: return false
        if (reviewedAt <= acceptedAt || reviewedAt - acceptedAt < 5 * 60_000L) return false
        val reviewed = pending.copy(manuallyReviewedAt = reviewedAt)
        sp.edit(commit = true) {
            putString(pendingKey, Json.encodeToString(reviewed))
            putString("aimi_last_manual_delivery_review_v1", Json.encodeToString(reviewed))
        }
        markTreatmentAccepted(reviewedAt)
        logger.info(LTag.APS, "Wizard treatment explicitly reconciled by user: accepted=$acceptedAt reviewed=$reviewedAt; waiting for fresh calculation")
        return true
    }

    private fun recentMealTopUpBolusCredit(input: AimiMealInput): Double {
        if (input.carbs <= 0 || input.bolusIob <= 0.0) return 0.0
        if (input.bg < input.targetBgLow || input.delta < -1.0) return 0.0
        val now = input.timestamp
        val activeEpisodes = pruneActiveEpisodes(activeEpisodesRef.get(), now)
            .filter { episode ->
                episode.deliveredBolus > 0.0 &&
                    now >= episode.startedAt &&
                    now - episode.startedAt <= TOP_UP_MEAL_WINDOW_MINUTES * 60_000L
            }
        if (activeEpisodes.isEmpty()) return 0.0

        val remainingDeliveredBolus = activeEpisodes.sumOf { episode ->
            episode.deliveredBolus * remainingFraction(episode, now)
        }
        return remainingDeliveredBolus.coerceIn(0.0, input.bolusIob)
    }

    private fun foodTypeModifier(selectedFoodType: String?): FoodTypeModifier =
        when (selectedFoodType?.lowercase()) {
            "fast" -> FoodTypeModifier(
                carbFactor = 0.80,
                label = "быстрые углеводы: раннее всасывание, bolus осторожнее"
            )
            "slow" -> FoodTypeModifier(
                carbFactor = 0.92,
                label = "медленная еда"
            )
            else -> FoodTypeModifier(
                carbFactor = 1.0,
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
        private const val TOP_UP_MEAL_WINDOW_MINUTES = 20L
    }
}
