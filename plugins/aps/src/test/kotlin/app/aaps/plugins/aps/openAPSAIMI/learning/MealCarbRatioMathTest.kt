package app.aaps.plugins.aps.openAPSAIMI.learning

import app.aaps.plugins.aps.openAPSAIMI.learning.MealCarbRatioMath.Bucket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The carb ratio learned from meals: clustering, the accounting of one meal, the lessons and how they are applied. */
class MealCarbRatioMathTest {

    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private val t = 1_800_000_000_000L

    @Test fun `carbohydrates entered within an hour are one meal, later ones another`() {
        val meals = MealCarbRatioMath.meals(
            listOf(
                MealCarbRatioMath.CarbEntry(t, 10.0), MealCarbRatioMath.CarbEntry(t + 20 * minute, 15.0),
                MealCarbRatioMath.CarbEntry(t + 70 * minute, 5.0), MealCarbRatioMath.CarbEntry(t + 300 * minute, 40.0)
            )
        )
        assertEquals(2, meals.size)
        assertEquals(30.0, meals[0].grams, 1e-9)
        assertEquals(3, meals[0].entries)
        assertEquals(t + 300 * minute, meals[1].startEpochMs)
        assertEquals(t + 300 * minute + MealCarbRatioMath.WINDOW_MS, meals[1].endEpochMs)
    }

    @Test fun `the lunch of 28 October needed about four units for 21 g`() {
        // 1.5 U before, 0.4 + SMBs 0.8 after, IOB 2.0 at the start and 1.2 four hours on, basal cut a little, glucose 133 -> 171 at ISF 59.
        val meal = MealCarbRatioMath.Meal(t, 21.0, 1)
        val outcome = MealCarbRatioMath.outcome(
            MealCarbRatioMath.Window(meal, bgStartMgdl = 133.0, bgEndMgdl = 171.0, iobStartU = 2.0, iobEndU = 1.2, bolusesU = 1.2, tempBasalNetU = -0.3, sensitivityMgdlPerU = 59.0, profileIcGPerU = 10.3)
        )
        assertEquals(2.0 + 1.2 - 0.3 - 1.2 + 38.0 / 59.0, outcome.neededU, 1e-9)
        assertEquals(21.0 / outcome.neededU, outcome.effectiveIcGPerU!!, 1e-9)
        assertTrue(outcome.effectiveIcGPerU!! in 8.0..9.5)
    }

    @Test fun `a meal that needed no insulin, or an absurd ratio, teaches nothing`() {
        val meal = MealCarbRatioMath.Meal(t, 20.0, 1)
        val none = MealCarbRatioMath.outcome(MealCarbRatioMath.Window(meal, 100.0, 90.0, 1.0, 1.0, 0.0, 0.0, 50.0, 10.0))
        assertNull(none.effectiveIcGPerU)
        // 15 g nobody bolused for, "needing" half a unit: the 28 g/U of 10 October that pulled the median up.
        val unbolused = MealCarbRatioMath.outcome(MealCarbRatioMath.Window(MealCarbRatioMath.Meal(t, 15.0, 1), 84.0, 93.0, 0.9, 0.5, 0.0, 0.0, 52.0, 10.3))
        assertNull(unbolused.effectiveIcGPerU)
        assertNull(MealCarbRatioMath.effectiveIc(20.0, 0.4, 2.0))
        assertNull(MealCarbRatioMath.effectiveIc(20.0, 2.0, 0.9))
        assertEquals(10.0, MealCarbRatioMath.effectiveIc(20.0, 2.0, 2.0)!!, 1e-9)
        // 20 g over 90 U would be a fifth of a gram per unit: no ratio a body has.
        val absurd = MealCarbRatioMath.outcome(MealCarbRatioMath.Window(meal, 100.0, 1000.0, 0.0, 0.0, 0.1, 0.0, 10.0, 10.0))
        assertNull(absurd.effectiveIcGPerU)
    }

    @Test fun `buckets follow the hour`() {
        assertEquals(Bucket.MORNING, MealCarbRatioMath.bucket(6))
        assertEquals(Bucket.MORNING, MealCarbRatioMath.bucket(10))
        assertEquals(Bucket.DAY, MealCarbRatioMath.bucket(11))
        assertEquals(Bucket.EVENING, MealCarbRatioMath.bucket(16))
        assertEquals(Bucket.LATE, MealCarbRatioMath.bucket(21))
        assertEquals(Bucket.LATE, MealCarbRatioMath.bucket(3))
    }

    @Test fun `a bucket with enough meals learns its own median, a thin one borrows the day's, too few in all learn nothing`() {
        val outcomes = List(6) { Bucket.DAY to 5.0 + it } + List(3) { Bucket.MORNING to 4.0 }
        val lessons = MealCarbRatioMath.learn(outcomes)
        assertEquals(7.5, lessons.getValue(Bucket.DAY).icGPerU, 1e-9)
        assertEquals(6, lessons.getValue(Bucket.DAY).meals)
        assertTrue(lessons.getValue(Bucket.MORNING).fromAllDay)
        assertEquals(MealCarbRatioMath.median(outcomes.map { it.second }), lessons.getValue(Bucket.MORNING).icGPerU, 1e-9)
        assertTrue(lessons.containsKey(Bucket.LATE))
        assertTrue(MealCarbRatioMath.learn(List(7) { Bucket.DAY to 5.0 }.take(4)).isEmpty())
    }

    @Test fun `the applied ratio stays within half to one and a half of the profile and moves at most fifteen percent a day`() {
        // Lesson 4.9 against a profile of 10.3: the floor is 5.15, and the first day's step from 10.3 is 15 %.
        val first = MealCarbRatioMath.applied(4.9, 10.3, previousIcGPerU = null, previousAtEpochMs = null, nowEpochMs = t)
        assertEquals(10.3 * 0.85, first, 1e-9)
        // An hour later only an hour's share of the day's step.
        val hourLater = MealCarbRatioMath.applied(4.9, 10.3, first, t, t + 60 * minute)
        assertEquals(first * (1 - 0.15 / 24), hourLater, 1e-6)
        // Days later it settles on the floor, not the lesson.
        var ic = first
        var at = t
        repeat(10) { ic = MealCarbRatioMath.applied(4.9, 10.3, ic, at, at + day); at += day }
        assertEquals(10.3 * 0.5, ic, 1e-9)
        // A lesson above the profile is capped the same way.
        assertEquals(10.0 * 1.5, MealCarbRatioMath.applied(40.0, 10.0, 15.0, t, t + day), 1e-9)
    }
}
