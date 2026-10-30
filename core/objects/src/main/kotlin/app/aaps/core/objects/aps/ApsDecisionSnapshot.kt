package app.aaps.core.objects.aps

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AimiMealAssist
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.aps.PendingWizardTreatment
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Display-only APS demand. Neither insulinReq nor a forecast peak authorizes a manual bolus. */
data class ApsDecisionSnapshot(
    val calculatedAt: Long,
    val state: State,
    val insulin: Double?,
    val carbs: Int?,
    val pendingTreatment: PendingWizardTreatment? = null,
    val unverifiedCarbWarning: Boolean = false
) {
    enum class State { CURRENT, WAITING_FOR_TREATMENT, REVIEW_DELIVERY, STALE, UNAVAILABLE }

    val statusLabel: String
        get() = when (state) {
            State.CURRENT -> "APS"
            State.WAITING_FOR_TREATMENT -> "Обновление"
            State.REVIEW_DELIVERY -> "Сверка"
            State.STALE -> "Расчет устарел"
            State.UNAVAILABLE -> "Нет расчета"
        }

    val requirementLabel: String
        get() = when {
            unverifiedCarbWarning -> "Углеводы"
            state == State.REVIEW_DELIVERY -> "Доза: н/д"
            else -> statusLabel
        }

    val requirementExplanation: String
        get() = if (unverifiedCarbWarning)
            "В свежем расчёте есть запрос углеводов. Количество не подтверждено: предыдущий ввод ещё не сверён или не включён в расчёт.\n\n$explanation"
        else explanation

    val explanation: String
        get() = when (state) {
            State.CURRENT -> "Это результат автоматического расчета APS, не дополнительный ручной болюс."
            State.WAITING_FOR_TREATMENT -> buildString {
                append("Жду расчёт, который учтёт предыдущий ввод инсулина или еды.")
                pendingTreatment?.takeIf { it.acceptedAt > 0L }?.let { pending ->
                    val time = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(pending.acceptedAt))
                    append(" Ввод от $time: ${pending.insulin} Е, ${pending.carbs} г.")
                }
                append(" Что подано на самом деле, видно в истории помпы и в журнале лечения.")
            }
            // No longer produced (the manual review was removed 30.10.2026); kept so old callers compile.
            State.REVIEW_DELIVERY -> "Предыдущий ввод ещё не учтён в расчёте. Что подано на самом деле, видно в истории помпы и в журнале лечения."
            State.STALE, State.UNAVAILABLE -> "Актуальное решение APS пока недоступно."
        }

    companion object {
        fun fromLoop(loop: Loop, mealAssist: AimiMealAssist, persistence: PersistenceLayer, now: Long): ApsDecisionSnapshot = try {
            val run = loop.lastRun
            val pending = mealAssist.pendingTreatment()
            val latestChange = maxOf(
                mealAssist.lastTreatmentAcceptedAt(),
                persistence.getNewestBolus()?.let { treatmentChangedAt(it.timestamp, it.dateCreated, now) } ?: 0L,
                persistence.getNewestCarbs()?.let { treatmentChangedAt(it.timestamp, it.dateCreated, now) } ?: 0L,
                latestActivityChange(persistence, now)
            )
            // Completion time can be newer than a treatment that was absent from the input snapshot.
            val inputTime = run?.constraintsProcessed?.iobData?.firstOrNull()?.time
                ?: run?.constraintsProcessed?.date ?: 0L
            // The manual review («сверка») was removed 30.10.2026: a pending dose is a short status, not a
            // gate; it ages out in the meal assist, and the wizard is held back only by a bolus in flight.
            from(run?.constraintsProcessed, now,
                pending != null || latestChange > inputTime,
                reviewDelivery = false,
                carbsInputsCurrent = latestChange <= inputTime &&
                    (pending == null || pending.carbsIncludedIn(run?.constraintsProcessed?.mealData)))
                .copy(pendingTreatment = pending)
        } catch (_: Exception) {
            from(null, now, pending = false)
        }

        // Future meal time is not the time at which the database changed.
        fun treatmentChangedAt(timestamp: Long, createdAt: Long, now: Long): Long =
            if (createdAt > 0L) createdAt else timestamp.takeIf { it <= now } ?: 0L

        private fun latestActivityChange(persistence: PersistenceLayer, now: Long): Long =
            persistence.getTherapyEventDataFromToTime(now - 12 * 60 * 60_000L, now + 6 * 60 * 60_000L).blockingGet()
                .filter { it.isValid && it.type == TE.Type.EXERCISE && it.note?.contains("AIMI_ACTIVITY_V2") == true }
                .maxOfOrNull { event ->
                    if (event.dateCreated > 0L) event.dateCreated
                    else {
                        val offset = event.note.orEmpty().split(' ').firstOrNull { it.startsWith("startOffset=") }
                            ?.substringAfter('=')?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                        treatmentChangedAt(event.timestamp - offset * 60_000L, 0L, now)
                    }
                } ?: 0L

        fun from(result: APSResult?, now: Long, pending: Boolean, reviewDelivery: Boolean = false, carbsInputsCurrent: Boolean = false): ApsDecisionSnapshot {
            val date = result?.date ?: 0L
            val fresh = result != null && date > 0L && now >= date && now - date <= 15 * 60_000L
            val state = when {
                reviewDelivery -> State.REVIEW_DELIVERY
                pending -> State.WAITING_FOR_TREATMENT
                result == null -> State.UNAVAILABLE
                !fresh -> State.STALE
                else -> State.CURRENT
            }
            val insulin = result?.json()?.optDouble("insulinReq", Double.NaN)?.takeIf { it.isFinite() && it >= 0.0 }
            // An unresolved insulin receipt blocks another bolus, not a new carbohydrate warning.
            val calculatedCarbs = result?.carbsReq?.coerceAtLeast(0)
            val verifiedCarbs = calculatedCarbs?.takeIf { fresh && (state == State.CURRENT || carbsInputsCurrent) }
            return ApsDecisionSnapshot(date, state, insulin.takeIf { state == State.CURRENT }, verifiedCarbs,
                unverifiedCarbWarning = fresh && (calculatedCarbs ?: 0) > 0 && verifiedCarbs == null)
        }
    }
}
