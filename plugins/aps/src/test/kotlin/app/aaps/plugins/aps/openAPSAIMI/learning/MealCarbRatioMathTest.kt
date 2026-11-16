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

    @Test fun `fast carbohydrates without a bolus inside a meal's window are the meal's rescue, not a new meal`() {
        // 01.11.2026 13:42: 12 g, 7 g seven minutes on, then 18 g fast at 14:27 with no bolus, after 3.0 U at 13:42.
        val bolus = listOf(t)
        val meals = MealCarbRatioMath.meals(
            listOf(
                MealCarbRatioMath.CarbEntry(t, 12.0), MealCarbRatioMath.CarbEntry(t + 7 * minute, 7.0),
                MealCarbRatioMath.CarbEntry(t + 45 * minute, 18.0, fast = true)
            ), bolus
        )
        assertEquals(1, meals.size)
        assertEquals(37.0, meals[0].grams, 1e-9)
        assertEquals(false, meals[0].rescueOnly)
        // 11:21: 25 g bolused for, 13 g fast at 12:13 (52 minutes after the last entry, inside the window): one meal of 38 g.
        val lunch = MealCarbRatioMath.meals(
            listOf(MealCarbRatioMath.CarbEntry(t, 15.0), MealCarbRatioMath.CarbEntry(t + 8 * minute, 10.0), MealCarbRatioMath.CarbEntry(t + 52 * minute, 13.0, fast = true)),
            listOf(t, t + 8 * minute)
        )
        assertEquals(1, lunch.size)
        assertEquals(38.0, lunch[0].grams, 1e-9)
        // Fast carbohydrates the owner bolused for (04:12: 10 g fast with 2 + 2 U) are food.
        val night = MealCarbRatioMath.meals(listOf(MealCarbRatioMath.CarbEntry(t, 10.0, fast = true), MealCarbRatioMath.CarbEntry(t + 16 * minute, 7.0, fast = true)), listOf(t, t + 4 * minute))
        assertEquals(1, night.size)
        assertEquals(false, night[0].rescueOnly)
        // A rescue with no meal around it is a cluster of its own that teaches nothing, and the real meal after it starts fresh.
        val alone = MealCarbRatioMath.meals(
            listOf(MealCarbRatioMath.CarbEntry(t, 9.0, fast = true), MealCarbRatioMath.CarbEntry(t + 30 * minute, 40.0)),
            listOf(t + 30 * minute)
        )
        assertEquals(2, alone.size)
        assertEquals(true, alone[0].rescueOnly)
        assertEquals(t + 30 * minute, alone[1].startEpochMs)
        assertEquals(false, alone[1].rescueOnly)
        // More than 25 g is a meal even when fast.
        assertEquals(false, MealCarbRatioMath.meals(listOf(MealCarbRatioMath.CarbEntry(t, 30.0, fast = true)))[0].rescueOnly)
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
        val outcomes = List(6) { MealCarbRatioMath.Learned(Bucket.DAY, 5.0 + it, t + it * day) } + List(3) { MealCarbRatioMath.Learned(Bucket.MORNING, 4.0, t + it * day) }
        val lessons = MealCarbRatioMath.learn(outcomes)
        assertEquals(7.5, lessons.getValue(Bucket.DAY).icGPerU, 1e-9)
        assertEquals(6, lessons.getValue(Bucket.DAY).meals)
        assertTrue(lessons.getValue(Bucket.MORNING).fromAllDay)
        assertEquals(MealCarbRatioMath.median(outcomes.map { it.icGPerU }), lessons.getValue(Bucket.MORNING).icGPerU, 1e-9)
        assertTrue(lessons.containsKey(Bucket.LATE))
        assertTrue(MealCarbRatioMath.learn(List(4) { MealCarbRatioMath.Learned(Bucket.DAY, 5.0, t + it * day) }).isEmpty())
    }

    @Test fun `only the newest meals teach, so a changed week is heard`() {
        // Twelve lunches: eight old ones at 5 g/U, then four at 10 g/U. The lesson rests on the newest eight.
        val old = List(8) { MealCarbRatioMath.Learned(Bucket.DAY, 5.0, t + it * day) }
        val recent = List(4) { MealCarbRatioMath.Learned(Bucket.DAY, 10.0, t + (8 + it) * day) }
        val lesson = MealCarbRatioMath.learn(old + recent).getValue(Bucket.DAY)
        assertEquals(8, lesson.meals)
        assertEquals(7.5, lesson.icGPerU, 1e-9)
        // The whole-day fallback takes the newest ten across buckets.
        val mixed = List(10) { MealCarbRatioMath.Learned(Bucket.EVENING, 4.0, t + it * day) } + List(10) { MealCarbRatioMath.Learned(Bucket.MORNING, 12.0, t + (10 + it) * day) }
        assertEquals(12.0, MealCarbRatioMath.learn(mixed).getValue(Bucket.LATE).icGPerU, 1e-9)
    }

    @Test fun `the applied ratio stays within 0,7 to 1,5 of the profile and moves at most fifteen percent a day`() {
        // Lesson 4.9 against a profile of 10.3: the floor is 7.21, and the first day's step from 10.3 is 15 %.
        val first = MealCarbRatioMath.applied(4.9, 10.3, previousIcGPerU = null, previousAtEpochMs = null, nowEpochMs = t)
        assertEquals(10.3 * 0.85, first, 1e-9)
        // An hour later only an hour's share of the day's step.
        val hourLater = MealCarbRatioMath.applied(4.9, 10.3, first, t, t + 60 * minute)
        assertEquals(first * (1 - 0.15 / 24), hourLater, 1e-6)
        // Days later it settles on the floor, not the lesson.
        var ic = first
        var at = t
        repeat(10) { ic = MealCarbRatioMath.applied(4.9, 10.3, ic, at, at + day); at += day }
        assertEquals(10.3 * 0.7, ic, 1e-9)
        // A lesson above the profile is capped the same way.
        assertEquals(10.0 * 1.5, MealCarbRatioMath.applied(40.0, 10.0, 15.0, t, t + day), 1e-9)
    }

    // ---- one rule for both sides of a window (16.11.2026) -------------------------------------------

    private val hour = 60 * minute
    private val curve = { minutes: Double -> app.aaps.core.objects.basaltuner.BasalNeed.stillToAct(minutes, 300, 55) }

    @Test fun `carbohydrates count where they are absorbed - a meal over three hours, fast ones over an hour and a half`() {
        val lunch = listOf(MealCarbRatioMath.CarbEntry(t, 60.0))
        assertEquals(60.0, MealCarbRatioMath.absorbedBetween(lunch, t, t + 4 * hour), 1e-9)
        assertEquals(30.0, MealCarbRatioMath.absorbedBetween(lunch, t, t + 90 * minute), 1e-9)
        assertEquals(0.0, MealCarbRatioMath.absorbedBetween(lunch, t - 2 * hour, t), 1e-9)
        // Eaten an hour before the window: a third is gone, the rest is absorbed in it.
        assertEquals(40.0, MealCarbRatioMath.absorbedBetween(lunch, t + hour, t + 5 * hour), 1e-9)
        // Fast carbohydrates half an hour before the window's end: a third of them belongs to it.
        val late = listOf(MealCarbRatioMath.CarbEntry(t + 3 * hour + 30 * minute, 12.0, fast = true))
        assertEquals(4.0, MealCarbRatioMath.absorbedBetween(late, t, t + 4 * hour), 1e-9)
        // And the two halves of any stretch add up to the whole.
        val all = lunch + late
        assertEquals(
            MealCarbRatioMath.absorbedBetween(all, t, t + 6 * hour),
            MealCarbRatioMath.absorbedBetween(all, t, t + 2 * hour) + MealCarbRatioMath.absorbedBetween(all, t + 2 * hour, t + 6 * hour), 1e-9
        )
    }

    @Test fun `a meal inside a window belongs to it, and the next window starts at the first meal after it`() {
        val meals = listOf(
            MealCarbRatioMath.Meal(t, 50.0, 1),                                  // lunch
            MealCarbRatioMath.Meal(t + 90 * minute, 20.0, 1),                    // dessert, inside lunch's four hours
            MealCarbRatioMath.Meal(t + 250 * minute, 5.0, 1),                    // a crumb starts nothing
            MealCarbRatioMath.Meal(t + 260 * minute, 12.0, 1, rescueOnly = true), // nor does a rescue
            MealCarbRatioMath.Meal(t + 300 * minute, 30.0, 1),                   // tea
            MealCarbRatioMath.Meal(t + 500 * minute, 60.0, 1),                   // dinner, inside tea's four hours
            MealCarbRatioMath.Meal(t + 560 * minute, 25.0, 1)                    // after tea's window: a window of its own
        )
        assertEquals(listOf(t, t + 300 * minute, t + 560 * minute), MealCarbRatioMath.windowStarts(meals).map { it.startEpochMs })
        // Picking up where an earlier look stopped: lunch's window is already taken.
        assertEquals(listOf(t + 300 * minute, t + 560 * minute), MealCarbRatioMath.windowStarts(meals.drop(1), coveredUntilEpochMs = t + 4 * hour).map { it.startEpochMs })
    }

    @Test fun `insulin that acted in a stretch is what was on board, plus what came, less what is left`() {
        val given = listOf(
            MealCarbRatioMath.Delivery(t - 30 * minute, 2.0), MealCarbRatioMath.Delivery(t + 10 * minute, 4.0),
            MealCarbRatioMath.Delivery(t + 20 * minute, -0.1), MealCarbRatioMath.Delivery(t + 200 * minute, 1.0)
        )
        assertEquals(2.0 * curve(30.0), MealCarbRatioMath.onBoard(given, t, curve), 1e-12)
        // A dose at the very moment is not yet on board there: it is counted as given from then on.
        assertEquals(0.0, MealCarbRatioMath.onBoard(listOf(MealCarbRatioMath.Delivery(t, 3.0)), t, curve), 1e-12)
        assertEquals(3.0, MealCarbRatioMath.deliveredBetween(listOf(MealCarbRatioMath.Delivery(t, 3.0)), t, t + hour), 1e-12)
        assertEquals(4.9, MealCarbRatioMath.deliveredBetween(given, t, t + 4 * hour), 1e-12)
        // Everything has acted five hours after the last dose: nothing is on board, and the books balance.
        val end = t + 9 * hour
        assertEquals(0.0, MealCarbRatioMath.onBoard(given, end, curve), 1e-12)
        val acted = MealCarbRatioMath.onBoard(given, t, curve) + MealCarbRatioMath.deliveredBetween(given, t, end) - MealCarbRatioMath.onBoard(given, end, curve)
        assertEquals(2.0 * curve(30.0) + 4.9, acted, 1e-12)
    }

    /** A window's outcome from what the learner would have found, glucose level throughout. */
    private fun outcomeOf(entries: List<MealCarbRatioMath.CarbEntry>, boluses: List<MealCarbRatioMath.Delivery>, from: Long): MealCarbRatioMath.Outcome {
        val to = from + MealCarbRatioMath.WINDOW_MS
        return MealCarbRatioMath.outcome(
            MealCarbRatioMath.Window(
                MealCarbRatioMath.Meal(from, MealCarbRatioMath.absorbedBetween(entries, from, to), 1), bgStartMgdl = 120.0, bgEndMgdl = 120.0,
                iobStartU = MealCarbRatioMath.onBoard(boluses, from, curve), iobEndU = MealCarbRatioMath.onBoard(boluses, to, curve),
                bolusesU = MealCarbRatioMath.deliveredBetween(boluses, from, to), tempBasalNetU = 0.0, sensitivityMgdlPerU = 50.0, profileIcGPerU = 10.3,
                givenU = MealCarbRatioMath.deliveredBetween(boluses, from - MealCarbRatioMath.BOLUS_BEFORE_MS, to)
            )
        )
    }

    @Test fun `a lunch and a dessert an hour and a half later teach the ratio they were dosed at, not half of it`() {
        // 50 g with 6.0 U, then 20 g with 2.4 U: 8.33 g/U both times, and glucose stayed level - so that IS the ratio.
        val entries = listOf(MealCarbRatioMath.CarbEntry(t, 50.0), MealCarbRatioMath.CarbEntry(t + 90 * minute, 20.0))
        val boluses = listOf(MealCarbRatioMath.Delivery(t + minute, 6.0), MealCarbRatioMath.Delivery(t + 91 * minute, 2.4))

        // The window from lunch holds the dessert as far as it got, and its insulin as far as it acted.
        val window = outcomeOf(entries, boluses, t)
        assertEquals(50.0 + 20.0 * 150.0 / 180.0, window.meal.grams, 1e-9)
        assertEquals(8.33, window.effectiveIcGPerU!!, 0.5)

        // The rule before 16.11.2026 evaluated the dessert alone - lunch's window had "another meal inside" and was
        // dropped - and charged it with lunch's insulin still on board: 20 g against five units.
        val dessertAt = t + 90 * minute
        val oldNeeded = MealCarbRatioMath.onBoard(boluses, dessertAt, curve) + 2.4 - MealCarbRatioMath.onBoard(boluses, dessertAt + MealCarbRatioMath.WINDOW_MS, curve)
        assertTrue(20.0 / oldNeeded < 4.5, "the old rule gave ${20.0 / oldNeeded} g/U")
    }

    @Test fun `a pre-bolus is counted once - on board at the start - and still marks the meal as dosed for`() {
        val entries = listOf(MealCarbRatioMath.CarbEntry(t, 40.0))
        val boluses = listOf(MealCarbRatioMath.Delivery(t - 15 * minute, 5.0))
        val outcome = outcomeOf(entries, boluses, t)
        // Nearly all of the five units acted in the window: 40 g over a little under 5 U.
        assertEquals(5.0 * (curve(15.0) - curve(255.0)), outcome.neededU, 1e-9)
        assertTrue(outcome.neededU in 4.5..5.0)
        assertEquals(40.0 / outcome.neededU, outcome.effectiveIcGPerU!!, 1e-9)
        assertEquals(5.0, outcome.bolusesU, 1e-9)
        // Summed from 45 minutes before the entry AND counted as on board, as before, it came out at half the ratio.
        assertTrue(40.0 / (outcome.neededU + 5.0) < 4.5)
    }

    @Test fun `insulin given late in the window counts as far as it acted`() {
        // 30 g with 3 U at the start, and a correction of 2 U three and a half hours on: half an hour of it belongs here.
        val entries = listOf(MealCarbRatioMath.CarbEntry(t, 30.0))
        val boluses = listOf(MealCarbRatioMath.Delivery(t + minute, 3.0), MealCarbRatioMath.Delivery(t + 210 * minute, 2.0))
        val outcome = outcomeOf(entries, boluses, t)
        val expected = 3.0 * (1 - curve(239.0)) + 2.0 * (1 - curve(30.0))
        assertEquals(expected, outcome.neededU, 1e-9)
        // The rule before left the late dose out of what was given and took what was left of it off all the same.
        assertTrue(expected > 3.0 - 3.0 * curve(239.0) - 2.0 * curve(30.0) + 1.5)
    }
}
