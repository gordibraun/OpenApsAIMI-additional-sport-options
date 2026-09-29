package app.aaps.plugins.aps.decisiontrace

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.DecisionStage
import app.aaps.core.interfaces.aps.DecisionTraceStep
import app.aaps.core.interfaces.aps.DecisionValue
import app.aaps.core.interfaces.aps.RT
import kotlin.math.abs

/** Display-only translation; recorded text and numeric decisions remain untouched. */
internal fun readableDecisionReason(text: String): String = text
    .removePrefix("Микродозу фактически ограничивает: ")
    .replace("Safety condition ✔", "Сработало условие защиты")
    .replace("Safety condition", "Условие защиты")
    .replace("No conditions met", "Условия защиты не выполнены")
    .replace("BG<90 → SMB=0", "Глюкоза ниже 90 мг/дл: микродоза отменена")
    .replace("Adjustments", "Поправки")
    .replace("Final SMB", "Итоговая микродоза")
    .replace("\uD83D\uDED1", "").replace("\u2705", "").replace("\uD83C\uDF9B\uFE0F", "")
    .replace(Regex("\\bSMB\\b"), "микродоза")
    .replace(Regex("\\bIOB\\b"), "активный инсулин")
    .replace(Regex("\\bCOB\\b"), "остаток еды")
    .replace(Regex("\\bBG\\b"), "глюкоза")
    .replace(Regex("\\bU\\b"), "Е")
    .lineSequence().joinToString("\n") { it.trim() }.trim()

/** An immutable display snapshot of one APS result, never an input to dosing. */
internal data class DecisionContext(
    val bg: Double? = null,
    val delta: Double? = null,
    val target: Double? = null,
    val iob: Double? = null,
    val cob: Double? = null,
    val isf: Double? = null,
    val forecast: List<Int> = emptyList(),
    val forecastSmb: Double? = null,
    val forecastBasal: Double? = null
) {
    companion object {
        fun from(result: APSResult): DecisionContext? {
            val raw = result.rawData() as? RT ?: return null
            return DecisionContext(
                raw.bg ?: result.glucoseStatus?.glucose, result.glucoseStatus?.delta,
                raw.targetBG, raw.IOB ?: result.iobData?.firstOrNull()?.iob,
                raw.COB ?: result.mealData?.mealCOB, raw.variable_sens,
                raw.predBGs?.AIMI_FINAL.orEmpty().toList(), raw.units, raw.rate
            )
        }
    }
}

internal data class ExplainedChange(
    val event: DecisionTraceStep,
    val before: Double?,
    val after: Double,
    val basal: Boolean,
    val gapBefore: Boolean = false,
    val reason: String = event.detail
) {
    val reduced get() = before != null && after < before - 0.0001
    val stopped get() = reduced && after == 0.0
}

internal data class DecisionExplanation(
    val title: String,
    val context: DecisionContext,
    val changes: List<ExplainedChange>,
    val basalChanges: List<ExplainedChange>,
    val smb: Double?,
    val basal: Double?,
    val carbs: Double?,
    val blockers: List<DecisionTraceStep>,
    val delivery: String,
    val basalDelivery: String,
    val forecastDiffersFromRequest: Boolean
) {
    companion object {
        private val smbNames = setOf("SMB", "Микродоза", "Запрос микродозы", "SMB до ограничений")
        private val basalNames = setOf("Базал", "Временный базал")
        private fun String.number() = toDoubleOrNull()?.takeIf { it.isFinite() }
        private fun close(a: Double?, b: Double?) = a != null && b != null && abs(a - b) < 0.0001
        private fun amount(step: DecisionTraceStep, basal: Boolean): DecisionValue? = step.values.firstOrNull {
            it.name in (if (basal) basalNames else smbNames) && it.unit == if (basal) "Е/ч" else "Е"
        }

        fun from(run: TraceRun): DecisionExplanation {
            val steps = run.steps
            fun input(name: String) = steps.flatMap { it.values }.firstOrNull { it.name == name }?.after?.number()
            val final = steps.lastOrNull { it.branch?.id == "final" || it.stage == DecisionStage.FINAL && amount(it, false) != null }
            val failed = run.state == TraceRunState.FAILED || final?.branch?.outcome == "error"
            val complete = final != null && !failed
            val context = (run.context ?: DecisionContext()).let {
                it.copy(bg = it.bg ?: input("Глюкоза"), delta = it.delta ?: input("Изменение за 5 минут"),
                    target = it.target ?: input("Цель"), iob = it.iob ?: input("Активный инсулин"),
                    cob = it.cob ?: input("Остаток"), isf = it.isf ?: input("ISF решения"),
                    forecast = if (complete) it.forecast else emptyList())
            }
            fun changes(basal: Boolean): List<ExplainedChange> {
                val found = mutableListOf<ExplainedChange>()
                for (step in steps) {
                    // Baseline adaptation and optimization candidates are not pump requests.
                    if (step.stage in setOf(DecisionStage.INPUT, DecisionStage.INSULIN, DecisionStage.SENSITIVITY, DecisionStage.CARBS, DecisionStage.DELIVERY)) continue
                    val value = amount(step, basal) ?: continue
                    if (step.branch?.outcome in setOf("skipped", "unchanged")) continue
                    val after = value.after.number()?.takeIf { it >= 0 } ?: continue
                    val before = value.before?.number()
                    if (value.before != null && before == null) continue
                    val last = found.lastOrNull()
                    if (close(before, after)) continue
                    if (before == null && last != null && close(last.after, after)) continue
                    // The journal records some changes both as a value and as a branch.
                    if (last != null && close(last.before, before) && close(last.after, after)) {
                        if (step.detail.isNotBlank()) found[found.lastIndex] = last.copy(event = step,
                            reason = step.detail)
                        continue
                    }
                    val forecastCheck = steps.lastOrNull { it.sequence < step.sequence && it.branch?.id == "final_forecast" }
                    val reason = if (step.title == "Выбран вариант по прогнозу" && forecastCheck != null)
                        forecastCheck.detail else step.detail
                    found += ExplainedChange(step, before, after, basal,
                        gapBefore = last != null && !close(last.after, before), reason = reason)
                }
                return found
            }
            val smbChanges = changes(false)
            val basalChanges = changes(true)
            // A result includes external limits only when these belong to the same recorded run.
            val resultSteps = if (final == null) emptyList() else steps.filter { it.sequence >= final.sequence }
            fun resultAmount(basal: Boolean) = resultSteps.asReversed().firstNotNullOfOrNull { amount(it, basal)?.after?.number() }
            val wait = resultSteps.lastOrNull { it.branch?.id == "delivery_smb" }
            val finalSmb = if (!complete) null else if (wait?.branch?.outcome in setOf("wait", "blocked", "none")) 0.0 else resultAmount(false)
            val finalBasal = if (complete) resultAmount(true) else null
            val blockers = steps.filter { it.branch?.outcome in setOf("block", "hold", "blocked", "wait") }
            val receipt = steps.lastOrNull { it.branch?.id == "delivery_result" }
            val delivery = when (receipt?.branch?.outcome) {
                "success" -> "Помпа подтвердила введение микродозы."
                "accepted" -> "Успешный ответ, но факт введения не подтверждён."
                "failed" -> "Помпа не подтвердила введение микродозы."
                else -> when (wait?.branch?.outcome) {
                    "requested" -> "Запрос отправлен. Подтверждения введения пока нет."
                    "none" -> "Команда микродозы не отправлялась."
                    "wait" -> "Отправка отложена. ${wait.detail}"
                    "blocked" -> "Микродоза не отправлена. ${wait.detail}"
                    else -> "Подтверждения микродозы в этой записи нет."
                }
            }
            val basalReceipt = steps.lastOrNull { it.branch?.id == "basal_result" }
            val basalDelivery = when (basalReceipt?.branch?.outcome) {
                "success" -> "Базал: успешный ответ помпы; подробности в журнале."
                "failed" -> "Базал: запрос не подтверждён."
                else -> "Подтверждения базала в этой записи нет."
            }
            return DecisionExplanation(
                when {
                    failed -> "Ошибка расчёта"
                    !complete -> if (run.state == TraceRunState.CALCULATING) "Расчёт выполняется" else "Итог не записан"
                    finalSmb == 0.0 && smbChanges.any { it.stopped } -> "Почему микродоза стала нулевой"
                    finalSmb == 0.0 -> "Почему микродоза не запрошена"
                    else -> "Как выбрана микродоза"
                }, context, smbChanges, basalChanges, finalSmb, finalBasal,
                if (complete) steps.asReversed().firstNotNullOfOrNull { it.values.firstOrNull { v -> v.name == "Углеводы" && v.unit == "г" }?.after?.number() } else null,
                blockers, delivery, basalDelivery,
                complete && (context.forecastSmb != null && !close(context.forecastSmb, finalSmb) ||
                    context.forecastBasal != null && !close(context.forecastBasal, finalBasal))
            )
        }
    }
}
