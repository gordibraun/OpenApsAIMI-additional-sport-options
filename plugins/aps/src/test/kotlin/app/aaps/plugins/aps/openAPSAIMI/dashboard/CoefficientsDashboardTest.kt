package app.aaps.plugins.aps.openAPSAIMI.dashboard

import app.aaps.plugins.aps.openAPSAIMI.dashboard.CoefficientsDashboard.StatusKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The dashboard must read at a glance. These tests are the checks a reader would make: every
 * number has its unit, every card says in one sentence what its number means, nothing technical
 * reaches the screen, the states are three plain words, and the proof of use is a check mark.
 */
class CoefficientsDashboardTest {

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val time: (Long) -> String = { ms -> val m = (ms / 60_000) % 1440; "%02d:%02d".format(m / 60, m % 60) }
    private val dayAndTime: (Long) -> String = { ms -> "28.10 " + time(ms) }

    private fun facts(
        foodIsf: Double? = 62.3, decisionIsf: Double? = 58.5, icApplied: Double = 10.3, icProfile: Double = 10.3, enabled: Boolean = true,
        lessonNow: Double? = null, lessonMeals: Int = 3, mealsWithResult: Int = 3,
        wizardIc: Double? = 10.3, loopIc: Double? = 10.3, watchFoodIsf: Double? = 62.3, samples: Int = 6
    ) = CoefficientsFacts(
        nowEpochMs = now, foodIsf = foodIsf, decisionIsf = decisionIsf, profileIsf = 108.0,
        isfSamples = List(samples) { CoefficientsFacts.IsfPoint(now - it * hour, 56.0 + it * 2, 150.0) },
        icApplied = icApplied, icProfile = icProfile, learnerEnabled = enabled,
        lessons = listOf(
            CoefficientsFacts.BucketLesson("Утро 06–11", 4.1, 10, false, 8.8, false),
            CoefficientsFacts.BucketLesson("День 11–16", lessonNow, lessonMeals, false, lessonNow?.let { 8.8 }, true),
            CoefficientsFacts.BucketLesson("Вечер 16–21", null, 0, false, null, false),
            CoefficientsFacts.BucketLesson("Поздно 21–06", 5.4, 19, false, 8.8, false)
        ),
        mealsEvaluated = 12, mealsWithResult = mealsWithResult,
        meals = listOf(CoefficientsFacts.MealRow(now - 3 * hour, 21.0, 2.7, 4.0, 5.2, 133.0, 171.0), CoefficientsFacts.MealRow(now - 30 * hour, 10.0, 5.9, 6.4, 1.6, 165.0, 123.0)),
        wizard = wizardIc?.let { CoefficientsFacts.Consumer(now - 6 * hour, 61.3, it) },
        loop = loopIc?.let { CoefficientsFacts.Consumer(now - 5 * 60_000, 58.5, it) },
        watch = loopIc?.let { CoefficientsFacts.WatchConsumer(now - 5 * 60_000, 58.5, watchFoodIsf, it) }
    )

    private fun build(f: CoefficientsFacts = facts()) = CoefficientsDashboard.build(f, time, dayAndTime)

    @Test fun `each card has one big number with its unit and one short sentence that says what it means`() {
        val v = build()
        for (card in listOf(v.isf, v.ic)) {
            assertTrue(card.title.split(" ").size <= 3, card.title)
            assertTrue(card.value.isNotBlank() && card.unit.isNotBlank())
            assertTrue(card.meaning.endsWith("."), card.meaning)
            assertTrue(card.meaning.length <= 70, card.meaning)
            assertTrue(card.details.size in 1..2)
        }
        assertEquals("62", v.isf.value)
        assertEquals("мг/дл на 1 ЕД", v.isf.unit)
        assertEquals("1 ЕД снижает сахар примерно на 62.", v.isf.meaning)
        assertEquals("10,3", v.ic.value.replace('.', ','))
        assertEquals("г на 1 ЕД", v.ic.unit)
    }

    @Test fun `nothing technical reaches the screen`() {
        val forbidden = listOf("_", "null", "NaN", "carb", "sens", "snapshot", "json", "oaps", "variable", "getIc", "true", "false")
        for (text in build().allTexts()) {
            for (word in forbidden) assertFalse(text.contains(word, ignoreCase = true), "'$word' in '$text'")
            // Latin letters only as the two abbreviations everybody uses.
            assertTrue(Regex("[A-Za-z]+").findAll(text).all { it.value in setOf("ISF", "IC") }, text)
        }
    }

    @Test fun `the carb ratio's state is one of three plain words`() {
        assertEquals("учится", build(facts()).ic.status!!.text)
        assertEquals(StatusKind.LEARNED, build(facts(lessonNow = 5.2, lessonMeals = 12)).ic.status!!.kind)
        assertEquals("урок есть", build(facts(lessonNow = 5.2, lessonMeals = 12)).ic.status!!.text)
        assertEquals("выключен", build(facts(enabled = false)).ic.status!!.text)
    }

    @Test fun `the carb ratio card says where the learner is taking it, or how many meals it still needs`() {
        assertTrue(build(facts()).ic.details.any { it == "Учитель собирает обеды: 3 с результатом, нужно 8." })
        val learned = build(facts(lessonNow = 5.2, lessonMeals = 12))
        assertTrue(learned.ic.details.any { it.replace("5.2", "5,2") == "Учитель ведёт к 5,2 (12 обедов)." }, learned.ic.details.toString())
        assertTrue(build(facts(enabled = false)).ic.details.any { it.startsWith("Учитель выключен") })
    }

    @Test fun `each place a coefficient is used says when, with its numbers, and a check mark when they are the same`() {
        val v = build()
        assertEquals(listOf("Мастер болюса", "Цикл", "Часы"), v.usedIn.map { it.who })
        assertEquals("расчёт ${time(now - 6 * hour)}", v.usedIn[0].whenText)
        assertTrue(v.usedIn.all { it.matches == true }, v.usedIn.map { it.matches }.toString())
        assertTrue(v.usedIn[2].values.contains("на еду 62"))
        val other = build(facts(wizardIc = 8.0, watchFoodIsf = null))
        assertEquals(false, other.usedIn[0].matches)
        assertEquals(false, other.usedIn[2].matches)
        val none = build(facts(wizardIc = null, loopIc = null))
        assertNull(none.usedIn[0].matches)
        assertEquals("прогона ещё не было", none.usedIn[1].whenText)
    }

    @Test fun `the lessons table has four periods, marks the current one and spells the counts out`() {
        val rows = build().lessons
        assertEquals(4, rows.size)
        assertEquals(1, rows.count { it.current })
        assertEquals("10 обедов", rows[0].meals)
        assertEquals("3 обеда", rows[1].meals)
        assertEquals("нет обедов", rows[2].meals)
        assertEquals("—", rows[2].lesson)
        assertEquals("профиль", rows[2].applied)
    }

    @Test fun `the trend is a line of hourly means with a one-line summary, and absent without samples`() {
        val t = build().trend!!
        assertEquals(6, t.points.size)
        assertEquals(6, t.labels.size)
        assertEquals("от 56 до 66", t.summary)
        assertNull(build(facts(samples = 0)).trend)
    }

    @Test fun `meals read as one line each, newest first, and say whether more insulin than the profile thinks was needed`() {
        val meals = build().meals
        assertEquals(2, meals.size)
        assertTrue(meals[0].text.startsWith("28.10 "))
        assertTrue(meals[0].text.contains("21 г") && meals[0].text.contains("IC 5"))
        assertEquals(true, meals[0].belowProfile)
        assertNotNull(meals[0].icEff)
    }

    @Test fun `without a food sensitivity the card says plainly that there is no data yet`() {
        val v = build(facts(foodIsf = null, decisionIsf = null))
        assertEquals("—", v.isf.value)
        assertEquals("Пока нет данных: цикл ещё не считал.", v.isf.meaning)
    }
}
