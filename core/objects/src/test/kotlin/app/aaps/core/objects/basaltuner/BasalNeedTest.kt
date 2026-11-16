package app.aaps.core.objects.basaltuner

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.TimeZone
import kotlin.math.sin

/**
 * The estimate of the need for basal, on six made-up days. The same days are built by
 * `aaps-bridge/basal_tuner_fixture.py` and run through the reference estimator the method was worked out and
 * checked with on the owner's own records; the numbers expected here are that script's output.
 */
class BasalNeedTest {

    private val zone = TimeZone.getTimeZone("GMT+3")
    private val hourMs = 3_600_000L
    private val minuteMs = 60_000L
    private val dayMs = 86_400_000L

    /** 10.11.2026 10:00 in the owner's time zone. */
    private val end = 1_794_294_000_000L
    private val days = 6
    private val day0 = end - days * dayMs - 10 * hourMs
    private fun day(k: Int) = day0 + k * dayMs

    /** From here on temp basals are filed at their true length. */
    private val fixed = day(3) + 12 * hourMs

    private fun readings(): List<BasalNeed.Reading> {
        val out = ArrayList<BasalNeed.Reading>()
        var i = 0
        var at = day0 - 12 * hourMs
        while (at < end) {
            var value = 110.0 + 15.0 * sin(i / 40.0) + ((i * 37) % 11 - 5)
            val missing = at >= day(2) + 4 * hourMs && at < day(2) + 4 * hourMs + 40 * minuteMs
            if (!missing) {
                if (at == day(3) + 3 * hourMs + 10 * minuteMs) value = 62.0
                out += BasalNeed.Reading(at, value)
            }
            at += 5 * minuteMs
            i++
        }
        return out
    }

    private fun carbs() = (-1..5).map { BasalNeed.Carbs(day(it) + 19 * hourMs + 30 * minuteMs, 60.0) } +
        BasalNeed.Carbs(day(1) + 23 * hourMs + 30 * minuteMs, 10.0) + BasalNeed.Carbs(day(4) + hourMs, 25.0)

    private fun doses() = (-1..5).map { BasalNeed.Dose(day(it) + 19 * hourMs + 30 * minuteMs, 6.0, microbolus = false) } +
        (0..6).flatMap { listOf(BasalNeed.Dose(day(it) + 2 * hourMs + 15 * minuteMs, 0.3, true), BasalNeed.Dose(day(it) + 4 * hourMs + 40 * minuteMs, 0.3, true)) } +
        BasalNeed.Dose(day(5) + 3 * hourMs, 1.0, microbolus = false)

    private fun tempBasals() = (0..6).flatMap {
        listOf(BasalNeed.TempBasal(day(it) + hourMs, 30 * minuteMs, 150.0, absolute = false), BasalNeed.TempBasal(day(it) + 4 * hourMs, 60 * minuteMs, 0.4, absolute = true))
    } + listOf(
        // Filed as seconds before the fix: this one really ran until the next began, twenty minutes later...
        BasalNeed.TempBasal(day(1) + 5 * hourMs, 20_000L, 200.0, false), BasalNeed.TempBasal(day(1) + 5 * hourMs + 20 * minuteMs, 30 * minuteMs, 80.0, false),
        // ...this one for its planned half hour...
        BasalNeed.TempBasal(day(2) + 6 * hourMs, 25_000L, 300.0, false),
        // ...and this one, after the fix, for the seconds on record.
        BasalNeed.TempBasal(day(4) + 6 * hourMs, 25_000L, 300.0, false)
    )

    private val firstProfile = List(24) { if (it in 2..5) 1.2 else 1.0 }
    private fun profiles() = listOf(BasalNeed.ProfileBasal(day0 - 2 * dayMs, firstProfile), BasalNeed.ProfileBasal(fixed, firstProfile.mapIndexed { hour, rate -> if (hour == 3) 0.9 else rate }))

    private fun input() = BasalNeed.Input(
        readings = readings(), doses = doses(), carbs = carbs(), tempBasals = tempBasals(), profiles = profiles(),
        toEpochMs = end + 17 * minuteMs, days = days, zone = zone, tempBasalsFiledShortBeforeEpochMs = fixed
    )

    private class Expected(val hour: Int, val samples: Int, val nights: Int, val need: Double, val q1: Double, val q3: Double, val acted: Double, val slope: Double, val glucose: Double)

    @Test
    fun `the six made-up days give the reference's numbers, hour by hour`() {
        val result = BasalNeed.estimate(input())
        assertThat(result.toEpochMs).isEqualTo(end)
        assertThat(result.fromEpochMs).isEqualTo(end - days * dayMs)
        assertThat(result.totalDailyU).isWithin(1e-6).of(30.807368421)
        assertThat(result.sensitivityMgdlPerU).isWithin(1e-6).of(58.427580551)
        assertThat(result.cleanHours).isEqualTo(90)
        assertThat(result.refused).containsExactly("bolus", 6, "carbs", 44, "low", 3, "readings", 1)
        assertThat(result.recentLowClockHours).containsExactly(3)
        val expected = listOf(
            Expected(0, 8, 4, 1.115945350, 1.065450165, 1.161626323, 1.125758382, -0.732557453, 113.250838709),
            Expected(1, 7, 5, 1.229395230, 1.166159515, 1.291545767, 1.195273387, 1.993656729, 104.082993989),
            Expected(2, 5, 3, 1.240200144, 1.193282343, 1.349642030, 1.251979272, 3.348681032, 100.205366429),
            Expected(3, 5, 3, 1.081748465, 1.011153724, 1.173522584, 1.051156275, 2.992119127, 103.925294520),
            Expected(4, 7, 4, 1.081748465, 1.005014293, 1.199163447, 1.045599865, 1.220227056, 112.647641522),
            Expected(5, 9, 5, 1.038508932, 0.996125811, 1.224804310, 1.045599865, 0.538535710, 116.776454903),
            Expected(6, 10, 5, 1.045973606, 0.969417522, 1.130190946, 1.034123093, 0.022153422, 117.103883424),
            Expected(7, 11, 6, 1.040295861, 0.958778816, 1.063077967, 1.016258365, -0.494228865, 112.179160177),
            Expected(8, 12, 7, 1.007911375, 0.946220874, 1.040902329, 1.006987230, -1.154513842, 110.175998490),
            Expected(9, 12, 6, 0.989712656, 0.942012601, 1.019554989, 1.000660559, -1.168920649, 112.287916317),
            Expected(10, 12, 7, 0.986987396, 0.932979384, 1.031687160, 1.000000000, -0.760294972, 108.343229483),
            Expected(11, 12, 6, 0.987258986, 0.942500424, 1.020793142, 1.000000000, -0.744426614, 107.073948492),
            Expected(12, 12, 6, 0.987116800, 0.944948713, 1.021233829, 1.000000000, -0.752734206, 107.073948492),
            Expected(13, 12, 6, 0.977786890, 0.934863589, 1.045077167, 1.000000000, -1.297858292, 107.092981824),
            Expected(14, 12, 6, 0.977786890, 0.943145756, 1.061476891, 1.000000000, -1.297858292, 107.118271721),
            Expected(15, 12, 6, 0.989549576, 0.943642693, 1.053908526, 1.000000000, -0.610592974, 107.066759421),
            Expected(16, 12, 6, 1.009940432, 0.932886161, 1.052511976, 1.000000000, 0.580795374, 107.041801763),
            Expected(17, 6, 6, 1.009940432, 0.946480613, 1.050572209, 1.000000000, 0.580795374, 107.272463686),
            Expected(23, 4, 4, 1.043495994, 0.994657115, 1.091381302, 1.056243376, -0.744798667, 113.250838709)
        )
        assertThat(result.hours.map { it.hour }).isEqualTo((0..23).toList())
        assertThat(result.hours.filter { it.samples == 0 }.map { it.hour }).containsExactly(18, 19, 20, 21, 22)
        expected.forEach { want ->
            val got = result.hours[want.hour]
            assertThat(got.samples).isEqualTo(want.samples)
            assertThat(got.nights).isEqualTo(want.nights)
            assertThat(got.needUph).isWithin(1e-6).of(want.need)
            assertThat(got.q1Uph).isWithin(1e-6).of(want.q1)
            assertThat(got.q3Uph).isWithin(1e-6).of(want.q3)
            assertThat(got.actedUph).isWithin(1e-6).of(want.acted)
            assertThat(got.slopeMgdlPerHour).isWithin(1e-6).of(want.slope)
            assertThat(got.glucoseMgdl).isWithin(1e-6).of(want.glucose)
        }
    }

    // ---- the idea itself, on days simple enough to do in the head ---------------------------------------------------

    private fun flatDays(basalGivenUph: Double, glucosePerHour: Double, extra: (Long) -> Double = { 0.0 }): BasalNeed.Input {
        val readings = ArrayList<BasalNeed.Reading>()
        var at = end - 5 * dayMs
        while (at < end) {
            readings += BasalNeed.Reading(at, 120.0 + glucosePerHour * ((at - (end - 5 * dayMs)) % dayMs) / hourMs + extra(at))
            at += 5 * minuteMs
        }
        return BasalNeed.Input(
            readings = readings, doses = emptyList(), carbs = emptyList(), tempBasals = emptyList(),
            profiles = listOf(BasalNeed.ProfileBasal(0L, List(24) { basalGivenUph })), toEpochMs = end, days = 3, zone = zone
        )
    }

    @Test
    fun `basal that holds glucose level is the need`() {
        val result = BasalNeed.estimate(flatDays(basalGivenUph = 0.8, glucosePerHour = 0.0))
        assertThat(result.totalDailyU).isWithin(1e-6).of(19.2)
        result.hours.forEach {
            assertThat(it.samples).isEqualTo(6)
            assertThat(it.needUph).isWithin(1e-6).of(0.8)
            assertThat(it.q1Uph).isWithin(1e-6).of(0.8)
            assertThat(it.slopeMgdlPerHour).isWithin(1e-9).of(0.0)
        }
    }

    @Test
    fun `glucose that climbs under the same basal says more is needed, by the climb over the sensitivity`() {
        // 0.8 U/h given, glucose up 9.375 mg/dl an hour; sensitivity 1800 / 19.2 = 93.75 mg/dl per unit: 0.1 U/h is missing.
        // (The day's sawtooth drops at midnight, so the hours around it are looked at apart.)
        val result = BasalNeed.estimate(flatDays(basalGivenUph = 0.8, glucosePerHour = 9.375))
        assertThat(result.sensitivityMgdlPerU).isWithin(1e-6).of(93.75)
        (3..20).forEach { assertThat(result.hours[it].needUph).isWithin(1e-6).of(0.9) }
    }

    @Test
    fun `insulin counts when it acts, not when it is given`() {
        // A microbolus at 03:00 every night: nothing of it has acted by 03:00, most of it by 08:00.
        val base = flatDays(basalGivenUph = 1.0, glucosePerHour = 0.0)
        val doses = (0..5).map { BasalNeed.Dose(end - it * dayMs - 10 * hourMs + 3 * hourMs, 1.0, microbolus = true) }
        val result = BasalNeed.estimate(BasalNeed.Input(base.readings, doses, base.carbs, base.tempBasals, base.profiles, end, 3, zone = zone))
        // The profile's hour 00 is judged by the clock hours 01 and 02: before the dose.
        assertThat(result.hours[0].actedUph).isWithin(1e-6).of(1.0)
        // Hour 02 is judged by 03 and 04, where the dose acts: the first hour's share and the second's.
        val firstHour = 1.0 - BasalNeed.stillToAct(60.0, 300, 55)
        val secondHour = BasalNeed.stillToAct(60.0, 300, 55) - BasalNeed.stillToAct(120.0, 300, 55)
        // About a third of a dose acts in its first hour and about a third in its second.
        assertThat(firstHour).isWithin(0.05).of(0.33)
        assertThat(secondHour).isWithin(0.05).of(0.33)
        assertThat(result.hours[2].actedUph).isWithin(1e-6).of(1.0 + (firstHour + secondHour) / 2)
        // Five hours on nothing of it is left.
        assertThat(result.hours[8].actedUph).isWithin(1e-6).of(1.0)
    }

    @Test
    fun `an hour is not clean after food, after a dose by hand, after a low, or without readings`() {
        val base = flatDays(basalGivenUph = 1.0, glucosePerHour = 0.0)
        val night = end - 10 * hourMs - dayMs // midnight before the last full day
        fun cleanClockHours(carbs: List<BasalNeed.Carbs> = emptyList(), doses: List<BasalNeed.Dose> = emptyList(), readings: List<BasalNeed.Reading> = base.readings): List<Int> {
            // One day only, so that every clock hour is either in or out.
            val result = BasalNeed.estimate(BasalNeed.Input(readings, doses, carbs, emptyList(), base.profiles, night + dayMs, 1, zone = zone))
            return (0..23).filter { clock -> isClean(result, clock) }
        }
        assertThat(cleanClockHours()).isEqualTo((0..23).toList())
        // A meal at 12:10 spoils its own hour and the five after it; a snack, three.
        assertThat(cleanClockHours(carbs = listOf(BasalNeed.Carbs(night + 12 * hourMs + 10 * minuteMs, 40.0)))).isEqualTo((0..11) + (18..23))
        assertThat(cleanClockHours(carbs = listOf(BasalNeed.Carbs(night + 12 * hourMs + 10 * minuteMs, 15.0)))).isEqualTo((0..11) + (16..23))
        // A dose by hand: five hours, food entered or not. The loop's own microbolus: none.
        assertThat(cleanClockHours(doses = listOf(BasalNeed.Dose(night + 12 * hourMs + 10 * minuteMs, 2.0, microbolus = false)))).isEqualTo((0..11) + (18..23))
        assertThat(cleanClockHours(doses = listOf(BasalNeed.Dose(night + 12 * hourMs + 10 * minuteMs, 0.4, microbolus = true)))).isEqualTo((0..23).toList())
        // A low at 04:20: its hour and the two after it.
        val withLow = base.readings.map { if (it.atEpochMs == night + 4 * hourMs + 20 * minuteMs) BasalNeed.Reading(it.atEpochMs, 64.0) else it }
        assertThat(cleanClockHours(readings = withLow)).isEqualTo((0..3) + (7..23))
        // Half an hour without readings: that hour is out (and its neighbours still have enough around them for a slope).
        val withGap = base.readings.filterNot { it.atEpochMs >= night + 9 * hourMs && it.atEpochMs < night + 9 * hourMs + 30 * minuteMs }
        assertThat(cleanClockHours(readings = withGap)).isEqualTo((0..8) + (10..23))
    }

    /**
     * With one day of data, whether the clock hour gave a sample. The result holds sums, not hours:
     * hours[H].samples = clean(H+1) + clean(H+2), each term 0 or 1 - so the hours are solved for from the 24 sums.
     */
    private fun isClean(result: BasalNeed.Result, clock: Int): Boolean {
        val sums = result.hours.map { it.samples }
        // Try both possibilities for clock hour 1, propagate, keep the one that closes the ring.
        for (first in 0..1) {
            val clean = IntArray(24)
            clean[1] = first
            var ok = true
            for (h in 0..22) {
                val next = sums[h] - clean[(h + 1) % 24]
                if (next !in 0..1) { ok = false; break }
                clean[(h + 2) % 24] = next
            }
            if (ok && sums[23] == clean[0] + clean[1]) return clean[clock] == 1
        }
        error("the sums do not come from clean hours: $sums")
    }

    @Test
    fun `temp basals filed as seconds before the fix are counted as they ran`() {
        val fix = 1_000_000_000L
        val ran = BasalNeed.asTheyRan(
            listOf(
                BasalNeed.TempBasal(fix - 50 * minuteMs, 26_000L, 340.0, false),               // the morning of 16.11: fifteen minutes, filed as 26 s
                BasalNeed.TempBasal(fix - 35 * minuteMs, 30 * minuteMs, 100.0, false),
                BasalNeed.TempBasal(fix - 200 * minuteMs, 20_000L, 0.0, false),                // nothing follows within its plan: the planned half hour
                BasalNeed.TempBasal(fix + 10 * minuteMs, 20_000L, 0.0, false)                  // after the fix a short record is a short basal
            ), fix
        )
        assertThat(ran.map { it.durationMs }).containsExactly(30 * minuteMs, 15 * minuteMs, 30 * minuteMs, 20_000L).inOrder()
    }

    @Test
    fun `the slope of glucose is not thrown by one wild reading`() {
        val steady = (0..24).map { BasalNeed.Reading(it * 5 * minuteMs, 100.0 + it * 1.0) } // 12 mg/dl an hour
        assertThat(BasalNeed.slopePerHour(steady)!!).isWithin(1e-9).of(12.0)
        val withSpike = steady.mapIndexed { index, reading -> if (index == 12) BasalNeed.Reading(reading.atEpochMs, 60.0) else reading }
        assertThat(BasalNeed.slopePerHour(withSpike)!!).isWithin(0.5).of(12.0)
        assertThat(BasalNeed.slopePerHour(steady.take(3))).isNull()
    }

    @Test
    fun `the action curve starts whole, ends empty, and peaks where it is said to`() {
        assertThat(BasalNeed.stillToAct(0.0, 300, 55)).isEqualTo(1.0)
        assertThat(BasalNeed.stillToAct(300.0, 300, 55)).isEqualTo(0.0)
        val perMinute = (0 until 300).map { BasalNeed.stillToAct(it.toDouble(), 300, 55) - BasalNeed.stillToAct(it + 1.0, 300, 55) }
        assertThat(perMinute.sum()).isWithin(1e-9).of(1.0)
        assertThat(perMinute.indices.maxByOrNull { perMinute[it] }!!).isIn(53..56)
    }
}
