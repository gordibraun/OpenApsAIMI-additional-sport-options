package app.aaps.plugins.aps.openAPSAIMI.learning

import kotlin.math.abs

/** Excludes known confounders; absence of recorded food is not proof of fasting. */
internal object BasalLearningEligibility {
    fun skipReason(
        now: Long, sampleTime: Long, bg: Double, delta: Double, noise: Double, flat: Boolean,
        cob: Double, mealActive: Boolean, exercise: Boolean, lastCarbTime: Long,
        insulinHorizonHours: Double, bolusIob: Double, basalIob: Double, tempBasalActive: Boolean
    ): String? = when {
        sampleTime <= 0 || now - sampleTime !in 0..12 * 60_000L -> "нет свежего измерения сенсора"
        !listOf(bg, delta, noise, cob, insulinHorizonHours, bolusIob, basalIob).all { it.isFinite() } -> "неполные входные данные"
        bg <= 39.0 || noise >= 3.0 || flat -> "качество данных сенсора недостаточно для обучения"
        mealActive || cob != 0.0 -> "есть влияние еды"
        insulinHorizonHours <= 0.0 -> "неизвестна длительность действия инсулина"
        lastCarbTime > 0 && now - lastCarbTime < insulinHorizonHours * 3_600_000 -> "недавние углеводы, в том числе для лечения низкой глюкозы"
        exercise -> "есть влияние нагрузки"
        abs(bolusIob) > 1e-6 -> "еще действует болюсный инсулин"
        abs(basalIob) > 1e-6 || tempBasalActive -> "есть влияние временного базала"
        else -> null
    }
}
