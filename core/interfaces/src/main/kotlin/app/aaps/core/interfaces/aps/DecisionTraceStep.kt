package app.aaps.core.interfaces.aps

import kotlinx.serialization.Serializable

@Serializable
enum class DecisionStage { INPUT, INSULIN, SENSITIVITY, CARBS, FORECAST, SMB, SAFETY, BASAL, FINAL, CONSTRAINTS, DELIVERY }

@Serializable
enum class DecisionStepKind { DETAIL, CHECKPOINT, CHANGE }

@Serializable
data class DecisionBranch(val id: String, val outcome: String)

@Serializable
data class DecisionValue(
    val name: String,
    val after: String,
    val before: String? = null,
    val unit: String = "",
    val stage: DecisionStage? = null
)

@Serializable
data class DecisionTraceStep(
    val sequence: Int,
    val stage: DecisionStage,
    val title: String,
    val detail: String,
    val kind: DecisionStepKind = DecisionStepKind.DETAIL,
    val values: List<DecisionValue> = emptyList(),
    val elapsedMs: Long? = null,
    val branch: DecisionBranch? = null
)

fun RT.recordDecisionStep(stage: DecisionStage, title: String, detail: String, values: List<DecisionValue> = emptyList(), branch: DecisionBranch? = null) {
    synchronized(this) {
        decisionTrace = decisionTrace + DecisionTraceStep(decisionTrace.size + 1, stage, title, detail,
            DecisionStepKind.CHECKPOINT, values, branch = branch)
    }
}
