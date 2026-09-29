package app.aaps.core.objects.aps

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.TreatmentInputStamp
import app.aaps.core.interfaces.db.PersistenceLayer

/** The boluses actually used for IOB, not the newest record seen when sending a command. */
class BolusInputSnapshot private constructor(
    private val since: Long,
    private val calculatedAt: Long,
    private val inputs: List<TreatmentInputStamp>
) {
    val lastBolusTime: Long get() = inputs.filter { it.amount > 0.0 }.maxOfOrNull { it.timestamp } ?: 0L

    fun matches(current: List<BS>, now: Long): Boolean {
        if (now < calculatedAt) return false
        val actual = current.filter { it.isValid && it.timestamp >= since && it.timestamp < now }
            .map { TreatmentInputStamp(it.timestamp, it.amount, it.dateCreated) }
        // Compare multisets: duplicate doses count, record ordering and NS metadata do not.
        return inputs.groupingBy { it }.eachCount() == actual.groupingBy { it }.eachCount()
    }

    fun validationError(persistence: PersistenceLayer, now: Long): String? = try {
        if (matches(persistence.getBolusesFromTime(since, true).blockingGet(), now)) null
        else "История инсулина изменилась после расчета IOB. Старое повышение отменено; нужен новый расчет."
    } catch (_: Exception) {
        "Не удалось сверить историю инсулина. Автоматическое повышение не отправлено."
    }

    companion object {
        const val MISSING_INPUTS = "Нет подтвержденного списка доз расчета IOB. Нужен новый расчет перед автоматическим повышением."

        fun from(iob: IobTotal?): BolusInputSnapshot? {
            val since = iob?.bolusInputsSince ?: return null
            val inputs = iob.bolusInputs ?: return null
            if (since >= iob.time || iob.time <= 0L || inputs.any { !it.amount.isFinite() }) return null
            return BolusInputSnapshot(since, iob.time, inputs.toList())
        }
    }
}
