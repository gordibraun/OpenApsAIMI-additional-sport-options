package app.aaps.core.interfaces.aps

data class AimiMealInput(
    val timestamp: Long,
    val profileName: String,
    val selectedFoodType: String,
    val bg: Double,
    val delta: Double,
    val carbs: Int,
    val requiredCarbs: Int,
    val cob: Double,
    val carbTimeMinutes: Int,
    val targetBgLow: Double,
    val targetBgHigh: Double,
    val ic: Double,
    val isf: Double,
    val bolusIob: Double,
    val basalIob: Double,
    val wizardRecommendedBolus: Double,
    val wizardCalculatedBolus: Double,
    val wizardInsulinFromCarbs: Double,
    val wizardInsulinFromBg: Double,
    val wizardInsulinFromTrend: Double,
    val wizardInsulinFromCob: Double,
    val wizardInsulinFromBolusIob: Double,
    val wizardInsulinFromBasalIob: Double,
    val wizardInsulinFromSuperBolus: Double,
    val correction: Double,
    val trendInsulin: Double,
    val notes: String
)

data class AimiMealDecision(
    val createdAt: Long,
    val recommendedBolus: Double,
    val targetBg: Double,
    val expectedEventualBg: Double,
    val confidence: Double,
    val mealMode: String,
    val modeFactor: Double,
    val prebolusBonus: Double,
    val source: String,
    val explanation: String
)

data class AimiMealEpisode(
    val startedAt: Long,
    val profileName: String,
    val selectedFoodType: String,
    val carbs: Int,
    val deliveredBolus: Double,
    val carbTimeMinutes: Int,
    val targetBg: Double,
    val expectedEventualBg: Double,
    val source: String,
    val notes: String
)

interface AimiMealAssist {
    fun evaluate(input: AimiMealInput): AimiMealDecision
    fun activate(input: AimiMealInput, decision: AimiMealDecision): AimiMealEpisode
    fun activeEpisode(): AimiMealEpisode?
    fun markTreatmentAccepted(timestamp: Long) {}
    fun clearPendingTreatment() {}
    fun lastTreatmentAcceptedAt(): Long = 0L
}
