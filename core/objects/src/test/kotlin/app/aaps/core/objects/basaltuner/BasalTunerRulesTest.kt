package app.aaps.core.objects.basaltuner

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.jupiter.api.Test
import java.util.TimeZone

/**
 * What the tuner makes of a measured need. The numbers of the first tests are the owner's own, 16.11.2026: his
 * profile as it stood that morning, and the need measured over the 28 days before.
 */
class BasalTunerRulesTest {

    private val zone = TimeZone.getTimeZone("GMT+3")
    private val now = 1_794_834_000_000L // 16.11.2026 16:00 (UTC+3)
    private val hourMs = 3_600_000L
    private val dayMs = 86_400_000L

    private val morningProfile = listOf(0.93, 1.23, 1.26, 1.28, 1.29, 1.23, 1.37, 1.2, 1.08, 0.94, 1.02, 0.95, 1.0, 1.16, 1.2, 1.1, 0.94, 0.81, 1.05, 1.05, 1.07, 1.1, 1.04, 1.166)
    private val eveningProfile = morningProfile.mapIndexed { hour, rate -> mapOf(1 to 1.05, 2 to 1.05, 3 to 1.05, 4 to 1.0, 5 to 0.95, 6 to 1.05, 7 to 1.0, 8 to 0.95)[hour] ?: rate }

    private fun estimate(hour: Int, samples: Int, nights: Int, need: Double, q1: Double, q3: Double, slope: Double = -5.0) =
        BasalNeed.HourEstimate(hour, samples, nights, need, q1, q3, need + 0.1, slope, 105.0)

    /** As measured on 16.11.2026 at 16:00 over 28 days. */
    private val measured = listOf(
        estimate(0, 8, 6, 1.00, 0.89, 1.57), estimate(1, 13, 9, 0.99, 0.89, 1.54), estimate(2, 15, 10, 0.99, 0.82, 1.31), estimate(3, 16, 10, 0.93, 0.80, 1.12),
        estimate(4, 17, 11, 0.99, 0.84, 1.15), estimate(5, 19, 12, 1.09, 0.75, 1.28), estimate(6, 15, 11, 1.06, 0.75, 1.10), estimate(7, 8, 6, 0.94, 0.79, 1.10),
        estimate(8, 8, 6, 1.07, 0.92, 1.15), estimate(9, 6, 5, 1.00, 0.87, 1.13), estimate(10, 2, 2, 0.76, 0.72, 0.80)
    ) + (11..23).map { BasalNeed.HourEstimate(it, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) }

    private fun judge(
        profile: List<Double>, estimates: List<BasalNeed.HourEstimate> = measured, reference: List<Double> = profile,
        restUntil: Map<Int, Long> = emptyMap(), declined: Set<Int> = emptySet(), lows: Set<Int> = emptySet()
    ) = BasalTunerRules.judge(estimates, profile, reference, restUntil, declined, lows, now)

    @Test
    fun `against the morning's profile it would have lowered the small hours, a tenth at a time`() {
        val judged = judge(morningProfile)
        assertThat(judged.mapNotNull { it.change }.map { Triple(it.hour, it.fromUph, it.toUph) })
            .containsExactly(Triple(3, 1.28, 1.15), Triple(4, 1.29, 1.15), Triple(6, 1.37, 1.25))
        assertThat(judged.filter { it.change != null }.map { it.verdict }.toSet()).containsExactly(BasalTunerRules.Verdict.LOWER)
        // 01, 02 and 05 lie inside the middle half of what was measured; 00 and 07-10 have too few clean hours behind them.
        assertThat(judged[1].verdict).isEqualTo(BasalTunerRules.Verdict.WITHIN)
        assertThat(judged[5].verdict).isEqualTo(BasalTunerRules.Verdict.WITHIN)
        assertThat(judged[0].verdict).isEqualTo(BasalTunerRules.Verdict.FEW)
        assertThat(judged[9].verdict).isEqualTo(BasalTunerRules.Verdict.FEW)
        assertThat(judged[14].verdict).isEqualTo(BasalTunerRules.Verdict.NO_DATA)
    }

    @Test
    fun `against the profile as changed that afternoon it has nothing to propose`() {
        val judged = judge(eveningProfile)
        assertThat(judged.mapNotNull { it.change }).isEmpty()
        (1..6).forEach { assertThat(judged[it].verdict).isEqualTo(BasalTunerRules.Verdict.WITHIN) }
    }

    @Test
    fun `the step is half the difference - at most a tenth down, a twentieth up - on steps of five hundredths`() {
        fun one(profile: Double, need: Double, q1: Double, q3: Double) =
            judge(List(24) { profile }, listOf(estimate(3, 16, 10, need, q1, q3))).single().change?.toUph
        assertThat(one(1.00, 0.88, 0.80, 0.95)).isEqualTo(0.95)   // half of 0.12 is 0.06: 0.94, on the steps 0.95
        assertThat(one(1.30, 0.80, 0.70, 0.90)).isEqualTo(1.15)   // half of 0.50 would be 0.25; a tenth is 0.13: 1.17 -> 1.15
        assertThat(one(1.00, 1.30, 1.20, 1.40)).isEqualTo(1.05)   // up: a twentieth at most
        assertThat(one(0.80, 1.00, 0.95, 1.10)).isEqualTo(0.85)
        assertThat(one(0.40, 0.30, 0.25, 0.35)).isEqualTo(0.36)   // below half a unit the steps are hundredths
    }

    @Test
    fun `no proposal when the difference is small, or when it disappears on the pump's steps`() {
        fun verdict(profile: Double, need: Double, q1: Double, q3: Double) = judge(List(24) { profile }, listOf(estimate(3, 16, 10, need, q1, q3))).single().verdict
        assertThat(verdict(1.00, 0.95, 0.90, 0.97)).isEqualTo(BasalTunerRules.Verdict.SMALL)
        // The owner's 10:00 stands at 1.02, which the pump holds as 1.00: half of 8 % down from 1.02 is 0.98 - on the steps, 1.00 again.
        assertThat(verdict(1.02, 0.935, 0.90, 0.97)).isEqualTo(BasalTunerRules.Verdict.GRID)
    }

    @Test
    fun `too few clean hours, or too few nights behind them, and nothing is said`() {
        val profile = List(24) { 1.3 }
        assertThat(judge(profile, listOf(estimate(3, 9, 9, 0.9, 0.8, 1.0))).single().verdict).isEqualTo(BasalTunerRules.Verdict.FEW)
        assertThat(judge(profile, listOf(estimate(3, 14, 5, 0.9, 0.8, 1.0))).single().verdict).isEqualTo(BasalTunerRules.Verdict.FEW)
        assertThat(judge(profile, listOf(estimate(3, 10, 6, 0.9, 0.8, 1.0))).single().change).isNotNull()
    }

    @Test
    fun `an hour changed lately, or one whose proposal was declined, is left alone until its time is up`() {
        val profile = List(24) { 1.3 }
        val estimates = listOf(estimate(3, 16, 10, 0.9, 0.8, 1.0), estimate(4, 16, 10, 0.9, 0.8, 1.0), estimate(5, 16, 10, 0.9, 0.8, 1.0))
        val judged = judge(profile, estimates, restUntil = mapOf(3 to now + dayMs, 4 to now + 5 * dayMs, 5 to now - hourMs), declined = setOf(4))
        assertThat(judged.map { it.verdict }).containsExactly(BasalTunerRules.Verdict.SETTLING, BasalTunerRules.Verdict.DECLINED, BasalTunerRules.Verdict.LOWER).inOrder()
    }

    @Test
    fun `basal is not raised where a low followed in the last week`() {
        val profile = List(24) { 0.8 }
        val estimates = listOf(estimate(3, 16, 10, 1.0, 0.95, 1.1, slope = 8.0))
        assertThat(judge(profile, estimates).single().verdict).isEqualTo(BasalTunerRules.Verdict.RAISE)
        // Insulin of 03:00 acts from 04:00 to 08:00: a low at 06:00 forbids, one at 02:00 or at 09:00 does not.
        assertThat(judge(profile, estimates, lows = setOf(6)).single().verdict).isEqualTo(BasalTunerRules.Verdict.LOWS)
        assertThat(judge(profile, estimates, lows = setOf(7)).single().verdict).isEqualTo(BasalTunerRules.Verdict.LOWS)
        assertThat(judge(profile, estimates, lows = setOf(2, 9)).single().verdict).isEqualTo(BasalTunerRules.Verdict.RAISE)
        // Lowering is never held back by a low.
        assertThat(judge(List(24) { 1.3 }, listOf(estimate(3, 16, 10, 0.9, 0.8, 1.0)), lows = setOf(4, 5, 6)).single().verdict).isEqualTo(BasalTunerRules.Verdict.LOWER)
    }

    @Test
    fun `the walk stays within a quarter of the profile the tuner started from`() {
        val reference = List(24) { 1.2 }
        // Already at the floor (1.2 less a quarter): no further down, whatever was measured.
        assertThat(judge(List(24) { 0.9 }, listOf(estimate(3, 16, 10, 0.6, 0.5, 0.7)), reference = reference).single().verdict).isEqualTo(BasalTunerRules.Verdict.BOUND)
        // One step above the floor: the step is cut to the floor.
        assertThat(judge(List(24) { 0.95 }, listOf(estimate(3, 16, 10, 0.6, 0.5, 0.7)), reference = reference).single().change?.toUph).isEqualTo(0.9)
        assertThat(judge(List(24) { 1.5 }, listOf(estimate(3, 16, 10, 1.9, 1.8, 2.0)), reference = reference).single().verdict).isEqualTo(BasalTunerRules.Verdict.BOUND)
    }

    @Test
    fun `the daily calculation runs once, at the first chance after nine, and never in the evening or at night`() {
        fun at(day: Int, hour: Int, minute: Int = 0) = 1_794_776_400_000L + day * dayMs + hour * hourMs + minute * 60_000L // day 0 = 16.11.2026 00:00 (UTC+3)
        assertThat(BasalTunerRules.dueForDailyRun(at(0, 8, 59), 0L, zone)).isFalse()
        assertThat(BasalTunerRules.dueForDailyRun(at(0, 9, 0), 0L, zone)).isTrue()
        assertThat(BasalTunerRules.dueForDailyRun(at(0, 15), at(0, 9, 5), zone)).isFalse()
        assertThat(BasalTunerRules.dueForDailyRun(at(1, 8, 30), at(0, 9, 5), zone)).isFalse()
        assertThat(BasalTunerRules.dueForDailyRun(at(1, 9, 1), at(0, 9, 5), zone)).isTrue()
        // The owner pressed «Пересчитать» at 07:00: the day's own run still comes after nine.
        assertThat(BasalTunerRules.dueForDailyRun(at(1, 9, 1), at(1, 7), zone)).isTrue()
        // The phone was off all day: no calculation, and no notice, late in the evening.
        assertThat(BasalTunerRules.dueForDailyRun(at(1, 21, 30), at(0, 9, 5), zone)).isFalse()
        assertThat(BasalTunerRules.dueForDailyRun(at(1, 20, 30), at(0, 9, 5), zone)).isTrue()
    }

    @Test
    fun `a notice comes by day only, and not within six hours of the one before`() {
        fun at(hour: Int) = 1_794_776_400_000L + hour * hourMs
        assertThat(BasalTunerRules.mayNotify(at(3), 0L, zone)).isFalse()
        assertThat(BasalTunerRules.mayNotify(at(8), 0L, zone)).isFalse()
        assertThat(BasalTunerRules.mayNotify(at(9), 0L, zone)).isTrue()
        assertThat(BasalTunerRules.mayNotify(at(20), 0L, zone)).isTrue()
        assertThat(BasalTunerRules.mayNotify(at(21), 0L, zone)).isFalse()
        assertThat(BasalTunerRules.mayNotify(at(14), at(9), zone)).isFalse()
        assertThat(BasalTunerRules.mayNotify(at(15), at(9), zone)).isTrue()
    }

    @Test
    fun `the reasons are written out for the owner`() {
        val change = BasalTunerRules.Change(3, 1.28, 1.15)
        val text = BasalTunerRules.reason(change, estimate(3, 16, 10, 0.93, 0.80, 1.12, slope = -13.2), 28)
        assertThat(text).isEqualTo(
            "03:00: 1,28 → 1,15 ЕД/ч. Инсулин этого часа действует в 04:00–06:00. За 28 дней там набралось 16 часов без еды из 10 ночей: " +
                "действовало 1,03 ЕД/ч, сахар при этом шёл вниз на 13 мг/дл в час — потребность 0,93 ЕД/ч (в половине случаев от 0,80 до 1,12). " +
                "Профиль выше; шаг — половина разницы, не больше 10 % за раз."
        )
        assertThat(BasalTunerRules.headline(listOf(BasalTunerRules.Change(6, 1.37, 1.25), change))).isEqualTo("2 часа: 03:00 1,28 → 1,15; 06:00 1,37 → 1,25 ЕД/ч")
        assertThat(BasalTunerRules.headline(listOf(change))).startsWith("1 час: ")
        val judged = judge(morningProfile)
        assertThat(BasalTunerRules.tableLine(measured[3], 1.28, judged[3])).isEqualTo("03:00  профиль 1,28 · потребность 0,93 (0,80–1,12) · 16 ч из 10 ночей · профиль выше потребности → 1,15")
        assertThat(BasalTunerRules.tableLine(measured[14], 1.2, judged[14])).isEqualTo("14:00  профиль 1,20 · нет часов без еды")
        assertThat(BasalTunerRules.plural(21, "ночи", "ночей", "ночей")).isEqualTo("ночи")
        assertThat(BasalTunerRules.plural(12, "час", "часа", "часов")).isEqualTo("часов")
    }

    @Test
    fun `what is remembered reads back as it was written`() {
        val judged = judge(morningProfile)
        val rows = measured.zip(judged).map { (e, j) -> BasalTunerState.Row(e.hour, morningProfile[e.hour], e.samples, e.nights, e.needUph, e.q1Uph, e.q3Uph, e.actedUph, e.slopeMgdlPerHour, e.glucoseMgdl, j.verdict, j.change?.toUph) }
        val run = BasalTunerState.Run(now, now - 28 * dayMs, now, 28, 45.8, 39.3, 104, mapOf("carbs" to 480, "bolus" to 60), rows, null)
        val read = BasalTunerState.Run.fromJson(JSONObject(run.toJson().toString()))
        assertThat(read.rows.map { it.toJson().toString() }).isEqualTo(rows.map { it.toJson().toString() })
        assertThat(read.refused).containsExactly("carbs", 480, "bolus", 60)
        assertThat(read.note).isNull()
        assertThat(read.rows[3].judged().change?.toUph).isEqualTo(1.15)
        assertThat(read.rows[3].estimate().needUph).isEqualTo(0.93)

        val changes = judged.mapNotNull { it.change }
        val proposal = BasalTunerState.Proposal(now, changes, BasalTunerRules.headline(changes), listOf("почему"), 26.47, 26.07)
        val back = BasalTunerState.Proposal.fromJson(JSONObject(proposal.toJson().toString()))
        assertThat(back.status).isEqualTo(BasalTunerState.Status.PENDING)
        assertThat(back.sameChangesAs(changes)).isTrue()
        assertThat(back.sameChangesAs(changes.drop(1))).isFalse()
        val declined = BasalTunerState.Proposal.fromJson(JSONObject(back.decided(BasalTunerState.Status.DECLINED, now + 5, "нет").toJson().toString()))
        assertThat(declined.status).isEqualTo(BasalTunerState.Status.DECLINED)
        assertThat(declined.decidedAtEpochMs).isEqualTo(now + 5)
        assertThat(declined.outcome).isEqualTo("нет")
    }

    @Test
    fun `hours left alone come back when their time is up, and a later change clears a decline`() {
        var rest = BasalTunerState.Rest.NONE.after(listOf(3, 4), now + 7 * dayMs, declined = true)
        rest = rest.after(listOf(6), now + 3 * dayMs, declined = false)
        rest = BasalTunerState.Rest.fromJson(JSONObject(rest.toJson().toString()))
        assertThat(rest.until.keys).containsExactly(3, 4, 6)
        assertThat(rest.declined).containsExactly(3, 4)
        assertThat(rest.at(now + 4 * dayMs).until.keys).containsExactly(3, 4)
        assertThat(rest.at(now + 8 * dayMs).until).isEmpty()
        assertThat(rest.at(now + 8 * dayMs).declined).isEmpty()
        // Hour 3 is changed after all (by a later proposal the owner took): it settles, it is no longer "declined".
        val later = rest.after(listOf(3), now + 9 * dayMs, declined = false)
        assertThat(later.declined).containsExactly(4)
    }
}
