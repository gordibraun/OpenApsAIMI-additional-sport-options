package app.aaps.plugins.aps.openAPSAIMI.meal

import app.aaps.core.interfaces.aps.AimiMealAssist
import app.aaps.core.interfaces.aps.AimiMealDecision
import app.aaps.core.interfaces.aps.AimiMealEpisode
import app.aaps.core.interfaces.aps.AimiMealInput
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import java.util.concurrent.atomic.AtomicReference
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

    private val activeEpisodeRef = AtomicReference<AimiMealEpisode?>()

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

        val protectiveCarbs = input.requiredCarbs.coerceAtLeast(0)
        val netCarbs = (input.carbs - protectiveCarbs).coerceAtLeast(0)
        val carbCoverageRatio = when {
            input.carbs <= 0 -> 0.0
            else -> netCarbs.toDouble() / input.carbs.toDouble()
        }
        val carbComponent = input.wizardInsulinFromCarbs * carbCoverageRatio
        val baseWithoutCarbs = input.wizardCalculatedBolus - input.wizardInsulinFromCarbs
        val effectiveBaseWithoutCarbs = when {
            protectiveCarbs > 0 -> max(0.0, baseWithoutCarbs)
            else -> baseWithoutCarbs
        }
        val adjustedCarbComponent = when {
            netCarbs <= 0 -> 0.0
            else             -> carbComponent * modeFactor
        }
        val prebolusApplied = if (protectiveCarbs == 0 && netCarbs > 0 && input.carbTimeMinutes >= 0) prebolusBonus else 0.0
        val rawRecommendation = when {
            protectiveCarbs > 0 && input.carbs < protectiveCarbs -> 0.0
            else -> max(0.0, effectiveBaseWithoutCarbs + adjustedCarbComponent + prebolusApplied)
        }
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
                append("Base(without carbs)=${"%.2f".format(baseWithoutCarbs)}U, ")
                append("carbs ${"%.2f".format(carbComponent)}U x ${"%.2f".format(modeFactor)} = ${"%.2f".format(adjustedCarbComponent)}U")
                if (prebolusApplied > 0.0) {
                    append(", prebolus +${"%.2f".format(prebolusApplied)}U")
                }
                append(" -> bolus ${"%.2f".format(roundedRecommendation)}U")
            }
        )
    }

    override fun activate(input: AimiMealInput, decision: AimiMealDecision): AimiMealEpisode {
        val episode = AimiMealEpisode(
            startedAt = input.timestamp,
            profileName = input.profileName,
            selectedFoodType = input.selectedFoodType,
            carbs = input.carbs,
            deliveredBolus = decision.recommendedBolus,
            carbTimeMinutes = input.carbTimeMinutes,
            targetBg = decision.targetBg,
            expectedEventualBg = decision.expectedEventualBg,
            source = decision.source,
            notes = input.notes
        )
        activeEpisodeRef.set(episode)
        logger.debug(
            LTag.APS,
            "AIMI meal episode activated: carbs=${input.carbs} bolus=${"%.2f".format(decision.recommendedBolus)} target=${"%.0f".format(decision.targetBg)} expected=${"%.0f".format(decision.expectedEventualBg)}"
        )
        return episode
    }

    override fun activeEpisode(): AimiMealEpisode? = activeEpisodeRef.get()

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
                carbFactor = 1.08,
                prebolusFactor = 1.25,
                label = "быстрая еда"
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
}
