package app.aaps.plugins.aps.decisiontrace

import app.aaps.core.interfaces.aps.DecisionStage
import app.aaps.core.interfaces.aps.DecisionTraceStep

internal data class BranchOption(val id: String, val label: String)
internal data class BranchNode(val id: String, val title: String, val stage: DecisionStage, val options: List<BranchOption>)
internal data class BranchVisit(val node: BranchNode, val step: DecisionTraceStep?) {
    val selected get() = node.options.firstOrNull { it.id == step?.branch?.outcome }
}

internal object DecisionBranchMap {
    private fun node(id: String, title: String, stage: DecisionStage, vararg choices: Pair<String, String>) =
        BranchNode(id, title, stage, choices.map { BranchOption(it.first, it.second) })
    private fun guard(id: String, title: String) = node(id, title, DecisionStage.SAFETY,
        "pass" to "Продолжить", "block" to "SMB запрещён")
    private fun cap(id: String, title: String) = node(id, title, DecisionStage.SAFETY,
        "pass" to "Без ограничения", "cap" to "Предел SMB", "block" to "SMB запрещён")
    private fun basalRule(id: String, title: String) = node(id, title, DecisionStage.BASAL,
        "selected" to "Выбран базал", "unchanged" to "Условие не подошло", "skipped" to "Пропущено: базал уже выбран")

    val nodes = listOf(
        node("meal_mode", "Специальный режим еды", DecisionStage.CARBS, "normal" to "Обычный расчёт", "active" to "Режим еды"),
        node("meal_prebolus", "Предболюс режима еды", DecisionStage.SMB, "pass" to "Продолжить", "prebolus" to "Предболюс; ранний итог"),
        node("input_quality", "Проверка данных сенсора", DecisionStage.INPUT, "pass" to "Без замечаний", "warning" to "Есть замечания"),
        node("isf", "Источник ISF решения", DecisionStage.SENSITIVITY,
            "dynamic" to "Динамическая оценка / профиль", "fused" to "Объединённая оценка", "minimum" to "Меньшая из двух оценок"),
        node("rescue", "Рост после низкой глюкозы", DecisionStage.FORECAST,
            "normal" to "Обычная модель", "protect" to "Короткий отскок"),
        node("food", "Есть остаток введённой еды?", DecisionStage.CARBS, "empty" to "Нет остатка", "declared" to "Кривая введённой еды"),
        cap("early", "Ранний перелив"),
        node("hypo_proposal", "Прогноз допускает первичный запрос?", DecisionStage.SAFETY,
            "pass" to "Обычный запрос", "block" to "Запрос обнулён", "fallback" to "Резервная оценка с уменьшением"),
        cap("calculation_limit", "Накопленный инсулин без еды"),
        node("proposal", "Первичный запрос микродозы", DecisionStage.SMB, "zero" to "Нет запроса", "positive" to "Предложена доза"),
        node("smb_adjustment", "Пересчёт запроса микродозы", DecisionStage.SMB,
            "same" to "Без изменения", "up" to "Увеличен", "down" to "Уменьшен"),
        guard("critical.hypo", "Защита от гипо"),
        guard("critical.remission", "Ремиссия и активный инсулин"),
        guard("critical.remission_fall", "Ремиссия и снижение"),
        guard("critical.negative_delta", "Снижение без режима еды"),
        guard("critical.iob_low", "Инсулин при невысокой глюкозе"),
        guard("critical.fasting", "Режим голодания"),
        guard("critical.minimum", "Глюкоза ниже 60"),
        guard("critical.calibration", "Новая калибровка"),
        guard("critical.below_falling", "Ниже цели и падает"),
        guard("critical.below_empty", "Ниже цели, еды нет"),
        guard("critical.fast_fall", "Быстрое снижение"),
        guard("critical.high_fall", "Снижение с высокого уровня"),
        guard("critical.very_fast_fall", "Очень быстрое снижение"),
        guard("critical.prediction_fall", "Снижаются сахар и прогноз"),
        guard("critical.below90", "Глюкоза ниже 90"),
        guard("critical.accelerating_fall", "Ускоряющееся снижение"),
        cap("sport", "Спортивная защита"),
        cap("rescue_cap", "Предел после низкого сахара"),
        cap("cumulative", "Накопленные микродозы"),
        cap("night", "Ночь без подтверждённой еды"),
        cap("manual", "Недавний обычный болюс"),
        cap("dose_limits", "Пределы SMB и активного инсулина"),
        node("early_return", "Как применён ранний предел?", DecisionStage.SAFETY,
            "continue" to "Обычный расчёт", "hold" to "SMB = 0; защитный базал"),
        node("smb_permission", "Микродозы разрешены?", DecisionStage.CONSTRAINTS,
            "pass" to "Разрешены", "block" to "Запрещены настройками"),
        node("iob_limit", "Достигнут предел инсулина?", DecisionStage.SAFETY,
            "pass" to "Продолжить", "block" to "Только базал", "meal" to "Послабление режима еды"),
        node("smb_interval", "Прошёл интервал после болюса?", DecisionStage.SMB,
            "pass" to "Доза в предложение", "wait" to "Ждать интервал", "none" to "Дозы нет"),
        node("basal_route", "Путь расчёта базала", DecisionStage.BASAL,
            "stop" to "Остановка по безопасности", "meal" to "Начало режима еды", "iob" to "Избыток активного инсулина", "engine" to "Планировщик базала"),
        node("basal_planner", "Приоритетный выбор базала", DecisionStage.BASAL,
            "hard_low" to "Глюкоза не выше 60: остановка", "soft_fall" to "Низкая и падает: остановка",
            "soft_rise" to "Низкая, не падает: часть базала", "resume" to "Возобновление после нулевого базала",
            "plateau" to "Повышенное плато", "stall" to "Повышенная и не снижается", "next" to "Перейти к адаптивному расчёту"),
        node("basal_adaptive", "Адаптивный базал", DecisionStage.BASAL,
            "candidate" to "Получен кандидат", "none" to "Кандидата нет"),
        node("basal_rules", "Дальнейшие правила базала", DecisionStage.BASAL,
            "selected" to "Правило выбрало базал", "unchanged" to "Без нового выбора", "skipped" to "Не вычислялось: базал уже выбран", "profile" to "Базал профиля"),
        basalRule("basal.low_prediction", "Низкий прогноз и избыток инсулина"),
        basalRule("basal.low_bg", "Базал при низкой глюкозе"),
        basalRule("basal.rising", "Базал при растущей глюкозе"),
        basalRule("basal.time", "Время суток и активность"),
        basalRule("basal.strong_rise", "Базал при сильном росте"),
        basalRule("basal.meal_window", "Базал в окне режима еды"),
        basalRule("basal.plateau", "Базал при повышенном плато"),
        basalRule("basal.hyper", "Базал при высокой глюкозе или прогнозе"),
        basalRule("basal.remission", "Базал в режиме ремиссии"),
        basalRule("basal.pregnancy", "Базал в режиме беременности"),
        node("final_forecast", "Безопасен прогноз с новой дозой?", DecisionStage.FORECAST,
            "pass" to "Сохранить решение", "reduce" to "Уменьшить по прогнозу"),
        node("final", "Итог алгоритма", DecisionStage.FINAL,
            "insulin" to "Микродоза и базал", "basal" to "Без SMB; решение по базалу", "error" to "Ошибка расчёта"),
        node("loop_gate", "Цикл может отправить решение?", DecisionStage.CONSTRAINTS,
            "pass" to "Продолжить", "block" to "Подача недоступна"),
        node("loop_limits", "Внешние ограничения AAPS", DecisionStage.CONSTRAINTS,
            "pass" to "Без изменения", "reduce" to "Предложение ограничено"),
        node("delivery_smb", "Отправка микродозы", DecisionStage.DELIVERY,
            "requested" to "Запрос в очередь", "none" to "Не запрошена", "wait" to "Ждать интервал", "blocked" to "Не отправлена"),
        node("delivery_result", "Ответ помпы на SMB", DecisionStage.DELIVERY,
            "success" to "Введение подтверждено", "accepted" to "Успешный ответ без подтверждения введения", "failed" to "Не подтверждено"),
        node("basal_result", "Ответ на временный базал", DecisionStage.DELIVERY,
            "success" to "Подтверждено", "failed" to "Не подтверждено")
    )

    fun visits(steps: List<DecisionTraceStep>, throughSequence: Int = Int.MAX_VALUE): List<BranchVisit> {
        val recorded = steps.filter { it.sequence <= throughSequence && it.branch != null }.groupBy { it.branch!!.id }
        // Repeated evaluations remain separate visits; unseen nodes are never inferred from log text.
        val executed = recorded.flatMap { (id, events) ->
            events.map { event ->
                val choice = event.branch!!
                val node = nodes.firstOrNull { it.id == id }
                    ?: node(id, event.title, event.stage, choice.outcome to "Записанный исход: ${choice.outcome}")
                BranchVisit(node, event)
            }
        }.sortedBy { it.step!!.sequence }
        return executed + nodes.filter { it.id !in recorded }.map { BranchVisit(it, null) }
    }
}
