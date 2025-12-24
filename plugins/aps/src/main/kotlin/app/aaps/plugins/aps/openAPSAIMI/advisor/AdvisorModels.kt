package app.aaps.plugins.aps.openAPSAIMI.advisor

/**
 * =============================================================================
 * AIMI ADVISOR DATA MODELS
 * =============================================================================
 *
 * Simple, non-nullable data classes for the AIMI Profile Advisor feature.
 * Zero external dependencies - guaranteed crash-free.
 * =============================================================================
 */

/**
 * Metrics collected for analysis.
 */
data class AdvisorMetrics(
    val periodLabel: String,
    val tir70_180: Double,          // Fraction (0-1)
    val tir70_140: Double,
    val timeBelow70: Double,
    val timeBelow54: Double,
    val timeAbove180: Double,
    val timeAbove250: Double,
    val meanBg: Double,             // mg/dL
    val gmi: Double,                // % (derived)
    val tdd: Double,                // U/day
    val basalPercent: Double,       // Basal as fraction of TDD
    val hypoEvents: Int,
    val severeHypoEvents: Int,
    val hyperEvents: Int
)

/**
 * Domain area for a recommendation.
 */
enum class RecommendationDomain {
    SAFETY,
    BASAL,
    ISF,
    TARGET,
    SMB,
    MODES,
    PROFILE_QUALITY
}

/**
 * Priority level for recommendations.
 */
enum class RecommendationPriority {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW
}

/**
 * Severity classification for the overall score.
 */
enum class AdvisorSeverity {
    GOOD,
    WARNING,
    CRITICAL
}

/**
 * A single recommendation from the advisor.
 */
data class AimiRecommendation(
    val domain: RecommendationDomain,
    val priority: RecommendationPriority,
    val titleResId: Int,
    val descriptionResId: Int,
    val actionsResIds: List<Int> = emptyList(),
    val advisorActions: List<AdvisorAction> = emptyList()
)

/**
 * Full advisor report.
 */
data class AdvisorReport(
    val generatedAt: Long,
    val metrics: AdvisorMetrics,
    val overallScore: Double,
    val overallSeverity: AdvisorSeverity,
    val overallAssessment: String,
    val recommendations: List<AimiRecommendation>,
    val summary: String
)

/**
 * =============================================================================
 * RULES ENGINE CONTEXT
 * =============================================================================
 */
data class AdvisorContext(
    val metrics: AdvisorMetrics,
    val profile: AimiProfileSnapshot,
    val prefs: AimiPrefsSnapshot,

    val basalProfile24h: List<BasalBlock>,
    val cgm24h: Cgm24hSnapshot,
    val meals24h: List<MealSnapshot>,
    val apsSettings: AimiFullSettingsSnapshot,

    // NEW — for deep analysis / LLM
    val insulin24h: Insulin24hSnapshot? = null,
    val steps24h: Steps24hSnapshot? = null
)

/**
 * Profile snapshot.
 */
data class AimiProfileSnapshot(
    val nightBasal: Double,      // U/h
    val icRatio: Double,         // g/U
    val isf: Double,             // mg/dL/U
    val targetBg: Double         // mg/dL
)

/**
 * AIMI preferences snapshot.
 */
data class AimiPrefsSnapshot(
    val maxSmb: Double,                     // U
    val lunchFactor: Double,                // multiplier
    val unifiedReactivityFactor: Double,    // multiplier
    val autodriveMaxBasal: Double           // U/h
)

data class AdvisorFlag(
    val code: String,
    val severity: AdvisorSeverity
)

data class AdvisorAction(
    val actionCode: AdvisorActionCode,
    val params: Map<String, Any> = emptyMap()
)

enum class AdvisorActionCode {
    INCREASE_NIGHT_BASAL,
    REDUCE_MAX_SMB,
    INCREASE_LUNCH_FACTOR
}

/**
 * =============================================================================
 * EXTRA SNAPSHOTS FOR LLM / DEEP AUDIT
 * =============================================================================
 */

data class BasalBlock(
    val from: String,     // "HH:MM"
    val to: String,       // "HH:MM"
    val uPerHour: Double
)

data class Cgm24hSnapshot(
    val intervalMin: Int,
    val valuesMgDl: List<Int>
)

data class MealSnapshot(
    val timestamp: Long,
    val carbsG: Double,
    val bolusU: Double? = null,
    val note: String? = null
)

data class AimiFullSettingsSnapshot(
    val json: String
)

/**
 * Insulin activity snapshot (24h).
 */
data class Insulin24hSnapshot(
    val totalU: Double,
    val basalU: Double,
    val bolusU: Double,
    val smbU: Double,
    val tempBasals: List<TempBasalEvent>,
    val smbs: List<SmbEvent>,
    val boluses: List<BolusEvent>,
    val iobNow: Double,
    val cobNow: Double
)

data class TempBasalEvent(
    val ts: Long,
    val durationMin: Int,
    val rateUph: Double? = null,
    val percent: Int? = null
)

data class SmbEvent(val ts: Long, val units: Double)

data class BolusEvent(
    val ts: Long,
    val units: Double,
    val kind: String // meal / correction / other
)

/**
 * Steps / activity snapshot.
 */
data class Steps24hSnapshot(
    val intervalMin: Int,
    val values: List<Int>
)

/**
 * Lightweight report representation for LLM.
 */
data class AdvisorReportForLLM(
    val overallScore: Double,
    val overallSeverity: String,
    val overallAssessment: String,
    val summary: String,
    val recommendations: List<RecForLLM>
)

data class RecForLLM(
    val domain: String,
    val priority: String,
    val title: String,
    val description: String,
    val advisorActions: List<AdvisorAction>
)