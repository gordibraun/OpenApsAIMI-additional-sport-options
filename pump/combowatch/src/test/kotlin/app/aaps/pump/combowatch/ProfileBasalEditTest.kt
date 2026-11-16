package app.aaps.pump.combowatch

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.junit.jupiter.api.Test

/** The owner's profile «post» as it stood on 16.11.2026, and the hours 01-08 as lowered that day. */
class ProfileBasalEditTest {

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

    @Test
    fun `blocks are spread to 24 hours, a block of two hours filling both`() {
        val hourly = ProfileBasalEdit.hourly(stored)!!
        assertThat(hourly).hasSize(24)
        assertThat(hourly[0]).isEqualTo(0.93)
        assertThat(hourly[18]).isEqualTo(1.05)
        assertThat(hourly[19]).isEqualTo(1.05)
        assertThat(hourly[23]).isEqualTo(1.17)
        assertThat(hourly.sum()).isWithin(0.001).of(26.47)
    }

    @Test
    fun `only the named hours change, and the day's total is what was announced`() {
        val before = ProfileBasalEdit.hourly(stored)!!
        val after = ProfileBasalEdit.apply(before, ProfileBasalEdit.parse(changes)!!)
        assertThat(after.subList(1, 9)).containsExactly(1.05, 1.05, 1.05, 1.00, 0.95, 1.05, 1.00, 0.95).inOrder()
        assertThat(after[0]).isEqualTo(before[0])
        assertThat(after.subList(9, 24)).isEqualTo(before.subList(9, 24))
        assertThat(after.sum()).isWithin(0.001).of(24.63)
    }

    @Test
    fun `equal neighbouring hours are joined into one block again, and the blocks read back as the same hours`() {
        val after = ProfileBasalEdit.apply(ProfileBasalEdit.hourly(stored)!!, ProfileBasalEdit.parse(changes)!!)
        val blocks = ProfileBasalEdit.blocks(after)
        // 01:00-03:59 at 1.05 is one block; 18:00-19:59 stays one.
        assertThat((0 until blocks.length()).map { blocks.getJSONObject(it).getString("time") }).containsNoneOf("02:00", "03:00", "19:00")
        assertThat(blocks.getJSONObject(1).getString("time")).isEqualTo("01:00")
        assertThat(blocks.getJSONObject(1).getInt("timeAsSeconds")).isEqualTo(3600)
        assertThat(ProfileBasalEdit.hourly(blocks)).isEqualTo(after)
    }

    @Test
    fun `rates that are not hour=rate pairs, or name no hour of the day, are not taken`() {
        assertThat(ProfileBasalEdit.parse("")).isNull()
        assertThat(ProfileBasalEdit.parse("24=1.0")).isNull()
        assertThat(ProfileBasalEdit.parse("3=-1")).isNull()
        assertThat(ProfileBasalEdit.parse("3:1.05")).isNull()
        assertThat(ProfileBasalEdit.parse(" 3 = 1.05 , 4=1 ")).containsExactly(3, 1.05, 4, 1.0)
    }

    @Test
    fun `blocks that do not start at midnight or on the hour are not read`() {
        assertThat(ProfileBasalEdit.hourly(JSONArray("""[{"time":"01:00","timeAsSeconds":3600,"value":1.0}]"""))).isNull()
        assertThat(ProfileBasalEdit.hourly(JSONArray("""[{"time":"00:00","timeAsSeconds":0,"value":1.0},{"time":"01:30","timeAsSeconds":5400,"value":1.1}]"""))).isNull()
    }
}
