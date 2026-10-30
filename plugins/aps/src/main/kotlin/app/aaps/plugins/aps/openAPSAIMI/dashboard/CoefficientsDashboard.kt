package app.aaps.plugins.aps.openAPSAIMI.dashboard

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the owner sees about the two coefficients that set a meal's dose, built so that it reads
 * at a glance: one big number with its unit and one plain sentence per card, where each number is
 * used with a check mark when it is the same number, and the rest in short lines. The words are
 * everyday Russian; the tests in CoefficientsDashboardTest hold this to the letter.
 */
object CoefficientsDashboard {

    enum class Accent { FOOD_ISF, IC }
    enum class StatusKind { LEARNING, LEARNED, OFF }

    class Status(val text: String, val kind: StatusKind)
    class BigCard(val title: String, val value: String, val unit: String, val meaning: String, val details: List<String>, val status: Status?, val accent: Accent)

    /** Where a coefficient is used: who, when, with which numbers, and whether they are the ones shown above. */
    class UsageRow(val who: String, val whenText: String, val values: String, val matches: Boolean?)
    class LessonRow(val period: String, val lesson: String, val meals: String, val applied: String, val current: Boolean)
    class Trend(val title: String, val points: List<Double>, val labels: List<String>, val summary: String)
    class MealLine(val text: String, val icEff: Double?, val belowProfile: Boolean?)
    class ActivityLine(val text: String, val weak: Boolean)

    class View(
        val title: String,
        val updated: String,
        val isf: BigCard,
        val ic: BigCard,
        val usedIn: List<UsageRow>,
        val lessons: List<LessonRow>,
        val trend: Trend?,
        val meals: List<MealLine>,
        val legend: List<String>,
        val activities: List<ActivityLine> = emptyList(),
        val activityAdvice: List<String> = emptyList()
    ) {
        /** Every string a person reads, for the tests. */
        fun allTexts(): List<String> = listOf(title, updated, isf.title, isf.value, isf.unit, isf.meaning, ic.title, ic.value, ic.unit, ic.meaning) +
            isf.details + ic.details + listOfNotNull(isf.status?.text, ic.status?.text) +
            usedIn.flatMap { listOf(it.who, it.whenText, it.values) } +
            lessons.flatMap { listOf(it.period, it.lesson, it.meals, it.applied) } +
            listOfNotNull(trend?.title, trend?.summary) + (trend?.labels ?: emptyList()) +
            meals.map { it.text } + legend + activities.map { it.text } + activityAdvice
    }

    fun build(facts: CoefficientsFacts, time: (Long) -> String, dayAndTime: (Long) -> String): View {
        val isfCard = BigCard(
            title = "ISF на еду",
            value = facts.foodIsf?.let { whole(it) } ?: "—",
            unit = "мг/дл на 1 ЕД",
            meaning = facts.foodIsf?.let { "1 ЕД снижает сахар примерно на ${whole(it)}." } ?: "Пока нет данных: цикл ещё не считал.",
            details = listOfNotNull(
                "Профиль: ${whole(facts.profileIsf)}",
                facts.decisionIsf?.let { "Цикл сейчас: ${whole(it)}" }
            ),
            status = null,
            accent = Accent.FOOD_ISF
        )
        val lessonNow = facts.lessons.firstOrNull { it.current }
        // The state of the period we are in now: a lesson elsewhere in the day does not make this one taught.
        val icStatus = when {
            !facts.learnerEnabled        -> Status("выключен", StatusKind.OFF)
            lessonNow?.lessonIc != null  -> Status("урок есть", StatusKind.LEARNED)
            else                         -> Status("учится", StatusKind.LEARNING)
        }
        val icLessonLine = when {
            !facts.learnerEnabled -> "Учитель выключен: берётся профиль."
            lessonNow?.lessonIc != null -> "Учитель ведёт к ${one(lessonNow.lessonIc)} (${if (lessonNow.fromAllDay) "по всему дню, " else ""}${plural(lessonNow.meals, "обед", "обеда", "обедов")})."
            else -> "Учитель собирает обеды: ${facts.mealsWithResult} с результатом, нужно 8."
        }
        val icCard = BigCard(
            title = "Углеводный коэффициент",
            value = one(facts.icApplied),
            unit = "г на 1 ЕД",
            meaning = "На каждые ${one(facts.icApplied)} г еды нужна 1 ЕД.",
            details = listOf("Профиль: ${one(facts.icProfile)}", icLessonLine),
            status = icStatus,
            accent = Accent.IC
        )
        // When the numbers a consumer last took differ from the ones above, say which it would take now.
        fun nowHint(ic: Double) = if (sameIc(ic, facts.icApplied)) "" else " → сейчас IC ${one(facts.icApplied)}"
        val usedIn = listOf(
            facts.wizard?.let {
                UsageRow("Мастер болюса", "расчёт ${time(it.atEpochMs)}", "ISF ${whole(it.isf)} · IC ${one(it.ic)}" + nowHint(it.ic), sameIc(it.ic, facts.icApplied))
            } ?: UsageRow("Мастер болюса", "расчётов за сутки нет", "возьмёт ISF ${isfCard.value} · IC ${one(facts.icApplied)}", null),
            facts.loop?.let {
                UsageRow("Цикл", "прогон ${time(it.atEpochMs)}", "ISF ${whole(it.isf)} · IC ${one(it.ic)}" + nowHint(it.ic), sameIc(it.ic, facts.icApplied))
            } ?: UsageRow("Цикл", "прогона ещё не было", "—", null),
            facts.watch?.let {
                UsageRow(
                    "Часы", "снимок ${time(it.atEpochMs)}",
                    "ISF ${whole(it.isf)}" + (it.foodIsf?.let { f -> ", на еду ${whole(f)}" } ?: ", на еду —") + " · IC ${one(it.ic)}" + nowHint(it.ic),
                    sameIc(it.ic, facts.icApplied) && it.foodIsf != null
                )
            } ?: UsageRow("Часы", "снимка ещё не было", "—", null)
        )
        val lessons = facts.lessons.map { b ->
            LessonRow(
                period = b.label,
                lesson = b.lessonIc?.let { one(it) } ?: "—",
                meals = if (b.ownMeals > 0) plural(b.ownMeals, "обед", "обеда", "обедов") else "нет обедов",
                applied = b.appliedIc?.let { one(it) } ?: "профиль",
                current = b.current
            )
        }
        val trend = facts.isfSamples.takeIf { it.isNotEmpty() }?.let { samples ->
            val byHour = samples.groupBy { it.atEpochMs / 3_600_000L }.toSortedMap()
            val points = byHour.values.map { list -> list.map { it.isf }.average() }
            val labels = byHour.keys.map { time(it * 3_600_000L).take(2) }
            Trend("ISF цикла за сутки", points, labels, "от ${whole(points.min())} до ${whole(points.max())}")
        }
        val meals = facts.meals.sortedByDescending { it.atEpochMs }.take(8).map { m ->
            MealLine(
                "${dayAndTime(m.atEpochMs)} · ${m.grams.roundToInt()} г · подано ${one(m.givenU)} ЕД, нужно было ${one(m.neededU)}" +
                    (m.icEff?.let { " → IC ${one(it)}" } ?: " → без вывода") + " · сахар ${m.bgStart.roundToInt()}→${m.bgEnd.roundToInt()}",
                m.icEff, m.icEff?.let { it < facts.icProfile }
            )
        }
        return View(
            title = "Коэффициенты еды",
            updated = "обновлено ${time(facts.nowEpochMs)}",
            isf = isfCard, ic = icCard, usedIn = usedIn, lessons = lessons, trend = trend, meals = meals,
            legend = listOf(
                "ISF: на сколько мг/дл одна единица снижает сахар.",
                "IC: сколько граммов еды покрывает одна единица.",
                "Учитель: по закрытым обедам ведёт IC к реальному, не быстрее 15 % в день."
            ),
            activities = facts.activities.map { ActivityLine(it.text, it.weak) },
            activityAdvice = facts.activityAdvice
        )
    }

    private fun sameIc(a: Double, b: Double): Boolean = abs(a - b) < 0.05
    private fun whole(v: Double): String = v.roundToInt().toString()
    private fun one(v: Double): String = String.format(Locale.getDefault(), "%.1f", v)

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val mod10 = n % 10
        val mod100 = n % 100
        val word = when {
            mod100 in 11..14 -> many
            mod10 == 1       -> one
            mod10 in 2..4    -> few
            else             -> many
        }
        return "$n $word"
    }
}
