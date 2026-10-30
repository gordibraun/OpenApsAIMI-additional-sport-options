package app.aaps.plugins.aps.openAPSAIMI.activity

import java.util.Locale
import kotlin.math.roundToInt

/**
 * What a walk or a sport session did to glucose through its tail, and whether the tail was fair:
 * too weak when the sugar still went low or rescue carbohydrates were needed, too strong when it
 * stayed high with nothing eaten. Pure arithmetic over the records; the reporter feeds it.
 * Asked for by the owner 30.10.2026 to tune the sport mechanics from facts.
 */
object ActivityOutcomeMath {

    /** The session as the phone has it on record. */
    data class Session(val startEpochMs: Long, val durationMinutes: Int, val mode: String, val tailMinutes: Int, val hr: HrProfile?) {

        val activeEndEpochMs: Long get() = startEpochMs + durationMinutes * 60_000L
        val tailEndEpochMs: Long get() = activeEndEpochMs + tailMinutes * 60_000L
    }

    /** The heart rate the watch saw during the session. */
    data class HrProfile(val avgBpm: Int, val maxBpm: Int, val effortMinutes: Int, val hardMinutes: Int, val restingBpm: Int) {

        fun toTokens(): String = "hr_avg=$avgBpm hr_max=$maxBpm hr_effort=$effortMinutes hr_hard=$hardMinutes hr_rest=$restingBpm"

        companion object {

            fun fromNote(note: String?): HrProfile? {
                val tokens = note.orEmpty().split(' ').mapNotNull { part ->
                    val at = part.indexOf('=')
                    if (at <= 0 || at >= part.lastIndex) null else part.substring(0, at) to part.substring(at + 1)
                }.toMap()
                val avg = tokens["hr_avg"]?.toIntOrNull() ?: return null
                return HrProfile(
                    avg, tokens["hr_max"]?.toIntOrNull() ?: avg, tokens["hr_effort"]?.toIntOrNull() ?: 0,
                    tokens["hr_hard"]?.toIntOrNull() ?: 0, tokens["hr_rest"]?.toIntOrNull() ?: 0
                )
            }
        }
    }

    /** The records of the window from the session's start to the end of its tail. */
    data class Inputs(
        val glucoseMgdl: List<Pair<Long, Double>>,
        val carbsG: List<Pair<Long, Double>>,
        /** (time, units, micro-dose) */
        val boluses: List<Triple<Long, Double, Boolean>>,
        val basalDeliveredU: Double,
        val basalProfileU: Double
    )

    enum class Verdict { TOO_WEAK, FAIR, TOO_STRONG, UNKNOWN }

    data class Report(
        val startEpochMs: Long,
        val mode: String,
        val durationMinutes: Int,
        val tailMinutes: Int,
        val hr: HrProfile?,
        val minGlucose: Int?,
        val meanGlucose: Int?,
        val minutesBelow70: Int,
        val minutesBelow55: Int,
        val minutesAbove180: Int,
        val carbsG: Int,
        val bolusU: Double,
        val smbU: Double,
        val smbCount: Int,
        val basalPercentOfProfile: Int?,
        val verdict: Verdict,
        val why: String
    )

    fun evaluate(session: Session, inputs: Inputs): Report {
        val readings = inputs.glucoseMgdl.filter { it.first in session.startEpochMs..session.tailEndEpochMs }.sortedBy { it.first }
        val windowMinutes = ((session.tailEndEpochMs - session.startEpochMs) / 60_000L).toInt().coerceAtLeast(1)
        val coverage = readings.size * 5.0 / windowMinutes
        val values = readings.map { it.second }
        val minG = values.minOrNull()?.roundToInt()
        val meanG = values.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        // Each reading stands for the five minutes up to the next one.
        fun minutesWhere(test: (Double) -> Boolean): Int = readings.count { test(it.second) } * 5
        val below70 = minutesWhere { it < 70.0 }
        val below55 = minutesWhere { it < 55.0 }
        val above180 = minutesWhere { it > 180.0 }
        val carbs = inputs.carbsG.filter { it.first in session.startEpochMs..session.tailEndEpochMs }.sumOf { it.second }
        val bolusList = inputs.boluses.filter { it.first in session.startEpochMs..session.tailEndEpochMs }
        val bolusU = bolusList.filter { !it.third }.sumOf { it.second }
        val smbList = bolusList.filter { it.third }
        val basalPercent = if (inputs.basalProfileU > 0.05) (inputs.basalDeliveredU / inputs.basalProfileU * 100).roundToInt() else null
        val rescueCarbs = carbs >= 15.0 && (minG ?: 999) < 80
        val (verdict, why) = when {
            // A session on record that the pulse never saw (entered for the wrong hour, as on 29.10.2026) says nothing about the tail.
            session.hr != null && session.hr.effortMinutes < 5 -> Verdict.UNKNOWN to "по пульсу нагрузки не было (усилие ${session.hr.effortMinutes} мин из ${session.durationMinutes})"
            coverage < 0.5                        -> Verdict.UNKNOWN to "мало показаний сахара за окно (${(coverage * 100).roundToInt()} %)"
            below55 > 0                           -> Verdict.TOO_WEAK to "сахар падал ниже 55 ($below55 мин)"
            below70 >= 15                         -> Verdict.TOO_WEAK to "сахар был ниже 70 целых $below70 мин"
            rescueCarbs                           -> Verdict.TOO_WEAK to "понадобилось ${carbs.roundToInt()} г углеводов при минимуме ${minG}"
            (minG ?: 0) >= 100 && (meanG ?: 0) >= 170 && carbs < 5.0 -> Verdict.TOO_STRONG to "сахар держался высоко (средний $meanG, минимум $minG) без еды"
            else                                  -> Verdict.FAIR to "сахар держался в коридоре: минимум $minG, средний $meanG"
        }
        return Report(
            session.startEpochMs, session.mode, session.durationMinutes, session.tailMinutes, session.hr,
            minG, meanG, below70, below55, above180, carbs.roundToInt(), bolusU, smbList.sumOf { it.second }, smbList.size, basalPercent, verdict, why
        )
    }

    /** One line a person reads. */
    fun line(report: Report, dayAndTime: (Long) -> String): String {
        val kind = if (report.mode == "SPORT") "спорт" else "прогулка"
        val hr = report.hr?.let { " · пульс ${it.avgBpm}/${it.maxBpm}" } ?: ""
        val tail = "хвост ${report.tailMinutes / 60} ч"
        val verdict = when (report.verdict) {
            Verdict.TOO_WEAK   -> "хвост слабоват"
            Verdict.TOO_STRONG -> "хвост, похоже, избыточен"
            Verdict.FAIR       -> "хвост в самый раз"
            Verdict.UNKNOWN    -> "вывода нет"
        }
        return "${dayAndTime(report.startEpochMs)} · $kind ${report.durationMinutes} мин$hr · $tail · ${report.why}" +
            (if (report.carbsG > 0) " · углеводов ${report.carbsG} г" else "") +
            (report.basalPercentOfProfile?.let { " · базал $it % от профиля" } ?: "") +
            (if (report.smbCount > 0) " · микродоз ${report.smbCount} на ${String.format(Locale.getDefault(), "%.1f", report.smbU)} ЕД" else "") +
            " → $verdict"
    }

    /** After a few sessions of one kind: which way the tail should move, if any. */
    data class Suggestion(val mode: String, val sessions: Int, val weak: Int, val strong: Int, val text: String)

    fun suggestion(mode: String, reports: List<Report>): Suggestion? {
        val known = reports.filter { it.mode == mode && it.verdict != Verdict.UNKNOWN }
        if (known.size < 3) return null
        val weak = known.count { it.verdict == Verdict.TOO_WEAK }
        val strong = known.count { it.verdict == Verdict.TOO_STRONG }
        val kind = if (mode == "SPORT") "спорта" else "прогулки"
        val text = when {
            weak * 100 / known.size >= 40   -> "После $kind сахар слишком часто уходил вниз ($weak из ${known.size}): хвост стоит усилить — эффект на 5 % больше или на 2 часа длиннее."
            strong * 100 / known.size >= 60 -> "После $kind сахар слишком часто оставался высоким ($strong из ${known.size}): хвост стоит ослабить."
            else                            -> "Хвост $kind по ${known.size} занятиям выглядит справедливым: низких ${weak}, высоких $strong."
        }
        return Suggestion(mode, known.size, weak, strong, text)
    }
}
