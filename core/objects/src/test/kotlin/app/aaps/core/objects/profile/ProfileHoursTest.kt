package app.aaps.core.objects.profile

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.junit.jupiter.api.Test

/** The owner's profile «post» as it stood on 16.11.2026, and the changes made to it that day. */
class ProfileHoursTest {

    private val stored = JSONArray(
        """[{"time":"00:00","timeAsSeconds":0,"value":0.93},{"time":"01:00","timeAsSeconds":3600,"value":1.23},{"time":"02:00","timeAsSeconds":7200,"value":1.26},
           {"time":"03:00","timeAsSeconds":10800,"value":1.28},{"time":"04:00","timeAsSeconds":14400,"value":1.29},{"time":"05:00","timeAsSeconds":18000,"value":1.23},
           {"time":"06:00","timeAsSeconds":21600,"value":1.37},{"time":"07:00","timeAsSeconds":25200,"value":1.2},{"time":"08:00","timeAsSeconds":28800,"value":1.08},
           {"time":"09:00","timeAsSeconds":32400,"value":0.94},{"time":"10:00","timeAsSeconds":36000,"value":1.02},{"time":"11:00","timeAsSeconds":39600,"value":0.95},
           {"time":"12:00","timeAsSeconds":43200,"value":1.0},{"time":"13:00","timeAsSeconds":46800,"value":1.16},{"time":"14:00","timeAsSeconds":50400,"value":1.2},
           {"time":"15:00","timeAsSeconds":54000,"value":1.1},{"time":"16:00","timeAsSeconds":57600,"value":0.94},{"time":"17:00","timeAsSeconds":61200,"value":0.81},
           {"time":"18:00","timeAsSeconds":64800,"value":1.05},{"time":"20:00","timeAsSeconds":72000,"value":1.07},{"time":"21:00","timeAsSeconds":75600,"value":1.1},
           {"time":"22:00","timeAsSeconds":79200,"value":1.04},{"time":"23:00","timeAsSeconds":82800,"value":1.17}]"""
    )
    private val changes = "1=1.05,2=1.05,3=1.05,4=1.00,5=0.95,6=1.05,7=1.00,8=0.95"
    private val comboGrid: (Double) -> Boolean = { rate ->
        val factor = Math.round(rate * 1000.0).toInt()
        val step = if (factor <= 1000) 10 else 50
        factor % step == 0
    }

    @Test
    fun `blocks are spread to 24 hours, a block of two hours filling both`() {
        val hourly = ProfileHours.hourly(stored)!!
        assertThat(hourly).hasSize(24)
        assertThat(hourly[0]).isEqualTo(0.93)
        assertThat(hourly[18]).isEqualTo(1.05)
        assertThat(hourly[19]).isEqualTo(1.05)
        assertThat(hourly[23]).isEqualTo(1.17)
        assertThat(hourly.sum()).isWithin(0.001).of(26.47)
    }

    @Test
    fun `only the named hours change, and the day's total is what was announced`() {
        val before = ProfileHours.hourly(stored)!!
        val after = ProfileHours.apply(before, ProfileHours.parse(changes)!!)
        assertThat(after.subList(1, 9)).containsExactly(1.05, 1.05, 1.05, 1.00, 0.95, 1.05, 1.00, 0.95).inOrder()
        assertThat(after[0]).isEqualTo(before[0])
        assertThat(after.subList(9, 24)).isEqualTo(before.subList(9, 24))
        assertThat(after.sum()).isWithin(0.001).of(24.63)
    }

    @Test
    fun `equal neighbouring hours are joined into one block again, and the blocks read back as the same hours`() {
        val after = ProfileHours.apply(ProfileHours.hourly(stored)!!, ProfileHours.parse(changes)!!)
        val blocks = ProfileHours.blocks(after)
        // 01:00-03:59 at 1.05 is one block; 18:00-19:59 stays one.
        assertThat((0 until blocks.length()).map { blocks.getJSONObject(it).getString("time") }).containsNoneOf("02:00", "03:00", "19:00")
        assertThat(blocks.getJSONObject(1).getString("time")).isEqualTo("01:00")
        assertThat(blocks.getJSONObject(1).getInt("timeAsSeconds")).isEqualTo(3600)
        assertThat(ProfileHours.hourly(blocks)).isEqualTo(after)
    }

    @Test
    fun `what is not hour=value pairs, or names no hour of the day, is not taken`() {
        assertThat(ProfileHours.parse("")).isNull()
        assertThat(ProfileHours.parse("24=1.0")).isNull()
        assertThat(ProfileHours.parse("3=-1")).isNull()
        assertThat(ProfileHours.parse("3:1.05")).isNull()
        assertThat(ProfileHours.parse("7-3=1.0")).isNull()
        assertThat(ProfileHours.parse("3=1=2")).isNull()
        assertThat(ProfileHours.parse(" 3 = 1.05 , 4=1 ")).containsExactly(3, 1.05, 4, 1.0)
    }

    @Test
    fun `a span of hours includes both its ends`() {
        assertThat(ProfileHours.parse("6-10=9.0")).containsExactly(6, 9.0, 7, 9.0, 8, 9.0, 9, 9.0, 10, 9.0)
        assertThat(ProfileHours.parse("0-6=6.0, 23=6.5")!!.keys).containsExactly(0, 1, 2, 3, 4, 5, 6, 23)
    }

    @Test
    fun `blocks that do not start at midnight or on the hour are not read`() {
        assertThat(ProfileHours.hourly(JSONArray("""[{"time":"01:00","timeAsSeconds":3600,"value":1.0}]"""))).isNull()
        assertThat(ProfileHours.hourly(JSONArray("""[{"time":"00:00","timeAsSeconds":0,"value":1.0},{"time":"01:30","timeAsSeconds":5400,"value":1.1}]"""))).isNull()
    }

    @Test
    fun `the morning carb ratio of 16 November is one block from six to eleven`() {
        val before = ProfileHours.hourly(JSONArray("""[{"time":"00:00","timeAsSeconds":0,"value":10.3}]"""))!!
        val changes = ProfileHours.parse("6-10=9.0")!!
        assertThat(ProfileHours.Limits.carbRatio(before, changes)).isNull()
        val blocks = ProfileHours.blocks(ProfileHours.apply(before, changes))
        assertThat((0 until blocks.length()).map { blocks.getJSONObject(it).getString("time") to blocks.getJSONObject(it).getDouble("value") })
            .containsExactly("00:00" to 10.3, "06:00" to 9.0, "11:00" to 10.3).inOrder()
    }

    @Test
    fun `the night target of 16 November moves low and high together`() {
        val night = ProfileHours.hourly(JSONArray("""[{"time":"00:00","timeAsSeconds":0,"value":5.5},{"time":"07:00","timeAsSeconds":25200,"value":6.5}]"""))!!
        val changes = ProfileHours.parse("0-6=6.0")!!
        assertThat(ProfileHours.Limits.target(night, night, changes, mgdl = false)).isNull()
        val after = ProfileHours.apply(night, changes)
        assertThat(after.subList(0, 7)).containsExactly(6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0)
        assertThat(after.subList(7, 24).toSet()).containsExactly(6.5)
    }

    @Test
    fun `a slip of the hand is refused - a rate off the pump's steps, a change of more than a third, a target in the wrong units`() {
        val basal = ProfileHours.hourly(stored)!!
        assertThat(ProfileHours.Limits.basal(basal, ProfileHours.parse(changes)!!, comboGrid)).isNull()
        assertThat(ProfileHours.Limits.basal(basal, mapOf(3 to 1.02), comboGrid)).contains("not a rate the pump holds")
        assertThat(ProfileHours.Limits.basal(basal, mapOf(3 to 0.5), comboGrid)).contains("more than a third")
        assertThat(ProfileHours.Limits.basal(basal, mapOf(3 to 10.5), comboGrid)).contains("more than a third")
        val ratio = List(24) { 10.3 }
        assertThat(ProfileHours.Limits.carbRatio(ratio, mapOf(7 to 1.0))).contains("outside")
        assertThat(ProfileHours.Limits.carbRatio(ratio, mapOf(7 to 6.0))).contains("more than a third")
        val target = List(24) { 5.5 }
        // 108 is a target in mg/dl; this profile is in mmol/l.
        assertThat(ProfileHours.Limits.target(target, target, mapOf(0 to 108.0), mgdl = false)).contains("outside")
        assertThat(ProfileHours.Limits.target(target, target, mapOf(0 to 7.5), mgdl = false)).contains("step of more than")
        assertThat(ProfileHours.Limits.target(target, target, mapOf(0 to 4.0), mgdl = false)).contains("outside")
    }

    @Test
    fun `the changed hours are named for the owner, equal neighbours as one span`() {
        val before = ProfileHours.hourly(stored)!!
        val after = ProfileHours.apply(before, mapOf(5 to 0.9, 9 to 0.9, 10 to 0.9, 18 to 1.0, 19 to 1.0))
        assertThat(ProfileHours.describe(before, after)).containsExactly("05:00 1,23 → 0,90", "09:00 0,94 → 0,90", "10:00 1,02 → 0,90", "18:00–20:00 1,05 → 1,00").inOrder()
        assertThat(ProfileHours.describe(before, before)).isEmpty()
    }
}
