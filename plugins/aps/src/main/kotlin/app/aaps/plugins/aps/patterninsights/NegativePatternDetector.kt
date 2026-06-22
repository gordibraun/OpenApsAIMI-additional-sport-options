package app.aaps.plugins.aps.patterninsights

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.CA
import app.aaps.core.data.model.GV
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.APSResult
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

object NegativePatternDetector {

    data class PatternReport(
        val generatedAt: Long,
        val summary: String,
        val patterns: List<NegativePattern>,
        val findings: List<String>,
        val days: List<DailyPattern>,
        val suggestions: List<String>
    )

    data class NegativePattern(
        val title: String,
        val occurrences: Int,
        val priority: Int,
        val detail: String,
        val suggestion: String
    )

    data class DailyPattern(
        val label: String,
        val start: Long,
        val end: Long,
        val bgMin: Int?,
        val bgMax: Int?,
        val smbUnits: Double,
        val normalBolusUnits: Double,
        val rescueCarbs: Double,
        val maxSmb60m: Double,
        val hadHighRise: Boolean,
        val hadDanger: Boolean,
        val likelyUnannouncedCarbs: Boolean,
        val note: String
    )

    fun analyze(
        now: Long,
        glucose: List<GV>,
        boluses: List<BS>,
        carbs: List<CA>,
        apsResults: List<APSResult>,
        daysBack: Int = 7
    ): PatternReport {
        val windows = eveningWindows(now, daysBack)
        val days = windows.map { window ->
            analyzeWindow(window, glucose, boluses, carbs, apsResults)
        }
        val riskyDays = days.filter { it.hadHighRise && it.smbUnits >= 3.0 && it.hadDanger }
        val heavySmbDays = days.filter { it.smbUnits >= 6.0 || it.maxSmb60m >= 3.0 }
        val rescueDays = days.filter { it.rescueCarbs >= 10.0 }
        val patterns = buildPatterns(days, riskyDays, heavySmbDays, rescueDays)

        val findings = buildList {
            if (riskyDays.size >= 2) {
                add("За ${riskyDays.size} из ${days.size} последних вечеров повторился сценарий: глюкоза сначала поднималась, затем система набирала SMB, а потом прогноз или факт уходил в опасную зону.")
            } else if (riskyDays.size == 1) {
                add("Нашёл один вечер с подозрительным сценарием: подъём глюкозы, заметный SMB и затем опасный прогноз или низкая глюкоза.")
            }
            if (heavySmbDays.isNotEmpty()) {
                val maxBurst = heavySmbDays.maxOf { it.maxSmb60m }
                add("В ${heavySmbDays.size} вечерних окнах был плотный набор SMB; максимальный часовой набор около ${fmtU(maxBurst)}.")
            }
            if (rescueDays.isNotEmpty()) {
                add("В ${rescueDays.size} окнах углеводы больше похожи на спасательные: они появились поздно ночью или после опасного прогноза.")
            }
            val unannounced = days.count { it.likelyUnannouncedCarbs }
            if (unannounced > 0) {
                add("В $unannounced окнах подъём глюкозы был без заметных углеводов до подъёма; это может быть неуказанная еда, UAM или завышенная вечерняя агрессия.")
            }
            if (isEmpty()) add("Повторяющегося вечерне-ночного паттерна перелива по последним данным не видно.")
        }

        val suggestions = buildList {
            if (riskyDays.isNotEmpty()) {
                add("Сначала проверять точность: ISF/динамическую чувствительность вечером, длительность действия инсулина и то, не завышает ли модель потребность при UAM или неточно указанной еде.")
                add("Отдельно смотреть окна, где прогноз уже опасный, но SMB продолжал накапливаться: это признак, что итоговый прогноз и ограничение SMB должны быть согласованы в одном контуре.")
            }
            if (heavySmbDays.isNotEmpty()) {
                add("Для защиты нужен не только лимит одного SMB, но и анализ накопления за 30-60 минут: если набрано много инсулина, следующий SMB должен требовать более сильного подтверждения.")
            }
            if (rescueDays.isNotEmpty()) {
                add("Ночные углеводы после падения лучше считать спасательными, а не доказательством обычной еды: они не должны задним числом оправдывать агрессивную вечернюю дозу.")
            }
            if (isEmpty()) add("Пока достаточно наблюдать следующие дни и сравнивать новые эпизоды с этой вкладкой.")
        }

        val summary = when {
            riskyDays.size >= 2 -> "Есть повторяющийся плохой паттерн: вечерний подъём, затем SMB-набор, затем риск падения. Это стоит разбирать как системную ошибку точности, а защиту использовать вторым слоем."
            riskyDays.size == 1 -> "Есть один опасный вечерний эпизод; пока это сигнал к проверке точности, но ещё не устойчивый паттерн."
            else -> "По последним данным устойчивого сценария перелива не найдено."
        }

        return PatternReport(
            generatedAt = now,
            summary = summary,
            patterns = patterns,
            findings = findings,
            days = days,
            suggestions = suggestions
        )
    }

    private fun buildPatterns(
        days: List<DailyPattern>,
        riskyDays: List<DailyPattern>,
        heavySmbDays: List<DailyPattern>,
        rescueDays: List<DailyPattern>
    ): List<NegativePattern> {
        val unannouncedDays = days.filter { it.likelyUnannouncedCarbs }
        val bolusPlusSmbDays = days.filter { it.normalBolusUnits >= 2.0 && it.smbUnits >= 3.0 }
        val nightRescueAfterInsulinDays = days.filter { it.rescueCarbs >= 10.0 && (it.smbUnits + it.normalBolusUnits) >= 4.0 }

        val mainDays = (if (riskyDays.isNotEmpty()) riskyDays else heavySmbDays).distinctBy { it.start }
        if (mainDays.isNotEmpty()) {
            val detail = buildList {
                if (riskyDays.isNotEmpty()) {
                    add("• За ${riskyDays.size} из ${days.size} последних вечеров повторился сценарий: глюкоза сначала поднималась, затем система набирала SMB, а потом прогноз или факт уходил в опасную зону.")
                }
                if (heavySmbDays.isNotEmpty()) {
                    add("• В ${heavySmbDays.size} вечерних окнах был плотный набор SMB; максимальный часовой набор около ${fmtU(heavySmbDays.maxOf { it.maxSmb60m })}.")
                }
                if (nightRescueAfterInsulinDays.isNotEmpty()) {
                    add("• В ${nightRescueAfterInsulinDays.size} окнах углеводы больше похожи на спасательные: они появились после риска и на фоне заметного инсулина.")
                } else if (rescueDays.isNotEmpty()) {
                    add("• В ${rescueDays.size} окнах углеводы появились поздно или рядом с опасным прогнозом.")
                }
                if (unannouncedDays.isNotEmpty()) {
                    add("• В ${unannouncedDays.size} окнах подъём глюкозы был без заметных углеводов до подъёма; это может быть неуказанная еда, UAM или завышенная вечерняя агрессия.")
                }
                if (bolusPlusSmbDays.isNotEmpty()) {
                    add("• В ${bolusPlusSmbDays.size} окнах обычный болюс и SMB были рядом; это фактор того же сценария, а не отдельный пересекающийся паттерн.")
                }
                add("• Эпизоды: ${evidence(mainDays)}.")
            }.joinToString("\n")
            val suggestion = buildList {
                add("• Сначала проверять точность: ISF/динамическую чувствительность вечером, длительность действия инсулина и то, не завышает ли модель потребность при UAM или неточно указанной еде.")
                add("• Отдельно смотреть окна, где прогноз уже опасный, но SMB продолжал накапливаться: итоговый прогноз и ограничение SMB должны быть согласованы в одном контуре.")
                add("• Ночные углеводы после падения считать спасательными, а не доказательством обычной еды: они объясняют восстановление глюкозы, но не должны задним числом оправдывать агрессивную вечернюю дозу.")
            }.joinToString("\n")
            return listOf(
                NegativePattern(
                    title = if (riskyDays.isNotEmpty()) "Вечерний подъем -> SMB -> риск падения" else "Плотное вечернее накопление SMB",
                    occurrences = mainDays.size,
                    priority = 100,
                    detail = detail,
                    suggestion = suggestion
                )
            )
        }

        return when {
            unannouncedDays.isNotEmpty() -> listOf(
                NegativePattern(
                    title = "Подъем без указанной еды",
                    occurrences = unannouncedDays.size,
                    priority = 75,
                    detail = "• Перед подъемом почти не было указанных углеводов, но глюкоза уходила высоко. Это может быть неуказанная еда, UAM или ошибочная вечерняя модель.\n• Эпизоды: ${evidence(unannouncedDays)}.",
                    suggestion = "• В такие окна UAM должен помогать объяснять подъем, но не давать системе бесконтрольно набирать SMB, если итоговый прогноз уже становится опасным."
                )
            )

            rescueDays.isNotEmpty() -> listOf(
                NegativePattern(
                    title = "Похожие на спасательные углеводы",
                    occurrences = rescueDays.size,
                    priority = 70,
                    detail = "• Углеводы появились поздно или рядом с опасным прогнозом.\n• Эпизоды: ${evidence(rescueDays)}.",
                    suggestion = "• Разделять обычную еду и спасательные углеводы, иначе модель будет путать причину подъема и лечение падения."
                )
            )

            else -> emptyList()
        }
    }

    private fun analyzeWindow(
        window: Window,
        glucose: List<GV>,
        boluses: List<BS>,
        carbs: List<CA>,
        apsResults: List<APSResult>
    ): DailyPattern {
        val g = glucose.filter { it.timestamp in window.start..window.end }.sortedBy { it.timestamp }
        val b = boluses.filter { it.isValid && it.timestamp in window.start..window.end }
        val c = carbs.filter { it.isValid && it.timestamp in window.start..window.end }
        val aps = apsResults.filter { it.date in window.start..window.end }.sortedBy { it.date }
        val highAt = g.firstOrNull { it.value >= 170.0 }?.timestamp
        val dangerAt = firstDangerTime(highAt ?: window.start, g, aps)
        val smb = b.filter { it.type == BS.Type.SMB }.sumOf { it.amount }
        val normal = b.filter { it.type == BS.Type.NORMAL }.sumOf { it.amount }
        val maxSmb60 = maxWindowedSmb(b.filter { it.type == BS.Type.SMB })
        val rescueCarbs = if (dangerAt != null) {
            c.filter { it.timestamp >= dangerAt - T.mins(20).msecs() }.sumOf { it.amount }
        } else {
            c.filter { hourOfDay(it.timestamp) <= 3 || hourOfDay(it.timestamp) >= 23 }.sumOf { it.amount }
        }
        val carbsBeforeHigh = highAt?.let { high ->
            c.filter { it.timestamp in (high - T.hours(4).msecs())..high }.sumOf { it.amount }
        } ?: 0.0
        val likelyUnannouncedCarbs = highAt != null && carbsBeforeHigh < 8.0 && g.any { it.timestamp >= highAt && it.value >= 190.0 }
        val hadDanger = dangerAt != null
        val note = buildNote(highAt != null, hadDanger, smb, maxSmb60, rescueCarbs, likelyUnannouncedCarbs)

        return DailyPattern(
            label = window.label,
            start = window.start,
            end = window.end,
            bgMin = g.minOfOrNull { it.value }?.roundToInt(),
            bgMax = g.maxOfOrNull { it.value }?.roundToInt(),
            smbUnits = smb,
            normalBolusUnits = normal,
            rescueCarbs = rescueCarbs,
            maxSmb60m = maxSmb60,
            hadHighRise = highAt != null,
            hadDanger = hadDanger,
            likelyUnannouncedCarbs = likelyUnannouncedCarbs,
            note = note
        )
    }

    private fun firstDangerTime(after: Long, glucose: List<GV>, apsResults: List<APSResult>): Long? {
        val glucoseDanger = glucose.firstOrNull { it.timestamp >= after && it.value <= 75.0 }?.timestamp
        val apsDanger = apsResults.firstOrNull { result ->
            result.date >= after && (result.carbsReq > 0 || result.predictionsAsGv.minOfOrNull { it.value }?.let { it <= 75.0 } == true)
        }?.date
        return listOfNotNull(glucoseDanger, apsDanger).minOrNull()
    }

    private fun maxWindowedSmb(smbs: List<BS>): Double {
        val sorted = smbs.sortedBy { it.timestamp }
        var maxUnits = 0.0
        for (start in sorted) {
            val end = start.timestamp + T.hours(1).msecs()
            val units = sorted.filter { it.timestamp in start.timestamp..end }.sumOf { it.amount }
            maxUnits = max(maxUnits, units)
        }
        return maxUnits
    }

    private fun buildNote(
        hadHigh: Boolean,
        hadDanger: Boolean,
        smb: Double,
        maxSmb60: Double,
        rescueCarbs: Double,
        likelyUnannouncedCarbs: Boolean
    ): String {
        val parts = mutableListOf<String>()
        if (hadHigh) parts += "был вечерний подъём"
        if (smb >= 3.0) parts += "SMB ${fmtU(smb)}"
        if (maxSmb60 >= 2.5) parts += "за час ${fmtU(maxSmb60)}"
        if (hadDanger) parts += "был опасный прогноз или низкая глюкоза"
        if (rescueCarbs >= 10.0) parts += "углеводы похожи на спасательные ${fmtG(rescueCarbs)}"
        if (likelyUnannouncedCarbs) parts += "подъём без заметной указанной еды"
        return if (parts.isEmpty()) "без явного негативного паттерна" else parts.joinToString("; ")
    }

    private fun evidence(days: List<DailyPattern>): String =
        days.joinToString("; ") { day ->
            "${day.label}: ГК ${day.bgMin ?: "?"}-${day.bgMax ?: "?"}, SMB ${fmtU(day.smbUnits)}, болюс ${fmtU(day.normalBolusUnits)}, углеводы ${fmtG(day.rescueCarbs)}, пик SMB/ч ${fmtU(day.maxSmb60m)}"
        }

    private fun eveningWindows(now: Long, daysBack: Int): List<Window> {
        val labelFormat = SimpleDateFormat("dd.MM", Locale.getDefault())
        val midnight = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return (0 until daysBack).mapNotNull { offset ->
            val day = midnight.clone() as Calendar
            day.add(Calendar.DAY_OF_YEAR, -offset)
            day.set(Calendar.HOUR_OF_DAY, 18)
            val start = day.timeInMillis
            val endCal = day.clone() as Calendar
            endCal.add(Calendar.DAY_OF_YEAR, 1)
            endCal.set(Calendar.HOUR_OF_DAY, 3)
            val end = minOf(endCal.timeInMillis, now)
            if (start >= now || end <= start) null else Window(labelFormat.format(day.time), start, end)
        }
    }

    private fun hourOfDay(timestamp: Long): Int =
        Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.HOUR_OF_DAY)

    private fun fmtU(value: Double): String = String.format(Locale.US, "%.1fU", value)
    private fun fmtG(value: Double): String = String.format(Locale.US, "%.0fg", value)

    private data class Window(val label: String, val start: Long, val end: Long)
}
