package app.aaps.core.objects.basaltuner

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the basal tuner remembers between its runs, as plain data that reads from and writes to JSON:
 * the last calculation (the table the owner checks it by), the proposal that awaits his answer, the proposals
 * before it and what became of them, the hours that are being left alone for now, and the profile it started
 * from. Nothing here decides anything.
 */
object BasalTunerState {

    /** One hour of the profile in the last calculation. */
    class Row(
        val hour: Int, val profileUph: Double, val samples: Int, val nights: Int,
        val needUph: Double, val q1Uph: Double, val q3Uph: Double,
        val actedUph: Double, val slopeMgdlPerHour: Double, val glucoseMgdl: Double,
        val verdict: BasalTunerRules.Verdict, val toUph: Double?
    ) {

        fun estimate() = BasalNeed.HourEstimate(hour, samples, nights, needUph, q1Uph, q3Uph, actedUph, slopeMgdlPerHour, glucoseMgdl)
        fun judged() = BasalTunerRules.Judged(hour, verdict, toUph?.let { BasalTunerRules.Change(hour, profileUph, it) })

        fun toJson(): JSONObject = JSONObject().put("hour", hour).put("profile", profileUph).put("samples", samples).put("nights", nights)
            .put("need", needUph).put("q1", q1Uph).put("q3", q3Uph).put("acted", actedUph).put("slope", slopeMgdlPerHour).put("glucose", glucoseMgdl)
            .put("verdict", verdict.name).put("to", toUph ?: JSONObject.NULL)

        companion object {

            fun fromJson(o: JSONObject) = Row(
                o.getInt("hour"), o.getDouble("profile"), o.getInt("samples"), o.getInt("nights"),
                o.getDouble("need"), o.getDouble("q1"), o.getDouble("q3"), o.getDouble("acted"), o.getDouble("slope"), o.getDouble("glucose"),
                runCatching { BasalTunerRules.Verdict.valueOf(o.getString("verdict")) }.getOrDefault(BasalTunerRules.Verdict.NO_DATA),
                if (o.isNull("to")) null else o.getDouble("to")
            )
        }
    }

    /** One calculation. [note] says why nothing could be proposed at all (a profile run at a percentage, say). */
    class Run(
        val atEpochMs: Long, val fromEpochMs: Long, val toEpochMs: Long, val days: Int,
        val totalDailyU: Double, val sensitivityMgdlPerU: Double, val cleanHours: Int, val refused: Map<String, Int>,
        val rows: List<Row>, val note: String?
    ) {

        fun toJson(): JSONObject = JSONObject().put("at", atEpochMs).put("from", fromEpochMs).put("to", toEpochMs).put("days", days)
            .put("totalDaily", totalDailyU).put("sensitivity", sensitivityMgdlPerU).put("cleanHours", cleanHours)
            .put("refused", JSONObject().apply { refused.forEach { (reason, count) -> put(reason, count) } })
            .put("rows", JSONArray().apply { rows.forEach { put(it.toJson()) } }).put("note", note ?: JSONObject.NULL)

        companion object {

            fun fromJson(o: JSONObject): Run {
                val refused = o.optJSONObject("refused") ?: JSONObject()
                val rows = o.getJSONArray("rows")
                return Run(
                    o.getLong("at"), o.getLong("from"), o.getLong("to"), o.getInt("days"), o.getDouble("totalDaily"), o.getDouble("sensitivity"), o.getInt("cleanHours"),
                    refused.keys().asSequence().associateWith { refused.getInt(it) },
                    List(rows.length()) { Row.fromJson(rows.getJSONObject(it)) }, if (o.isNull("note")) null else o.getString("note")
                )
            }
        }
    }

    enum class Status(val text: String) {
        PENDING("ждёт ответа"), APPLIED("применено"), DECLINED("отклонено"), OUTDATED("устарело"), FAILED("не удалось применить")
    }

    class Proposal(
        val createdAtEpochMs: Long, val changes: List<BasalTunerRules.Change>, val headline: String, val reasons: List<String>,
        val totalBeforeU: Double, val totalAfterU: Double,
        val status: Status = Status.PENDING, val decidedAtEpochMs: Long = 0L, val outcome: String? = null
    ) {

        fun decided(status: Status, atEpochMs: Long, outcome: String? = null) =
            Proposal(createdAtEpochMs, changes, headline, reasons, totalBeforeU, totalAfterU, status, atEpochMs, outcome)

        /** The same hours to the same rates: a proposal the owner already has in front of him. */
        fun sameChangesAs(other: List<BasalTunerRules.Change>) =
            changes.map { Triple(it.hour, it.fromUph, it.toUph) }.toSet() == other.map { Triple(it.hour, it.fromUph, it.toUph) }.toSet()

        fun toJson(): JSONObject = JSONObject().put("createdAt", createdAtEpochMs)
            .put("changes", JSONArray().apply { changes.forEach { put(JSONObject().put("hour", it.hour).put("from", it.fromUph).put("to", it.toUph)) } })
            .put("headline", headline).put("reasons", JSONArray(reasons)).put("totalBefore", totalBeforeU).put("totalAfter", totalAfterU)
            .put("status", status.name).put("decidedAt", decidedAtEpochMs).put("outcome", outcome ?: JSONObject.NULL)

        companion object {

            fun fromJson(o: JSONObject): Proposal {
                val changes = o.getJSONArray("changes")
                val reasons = o.getJSONArray("reasons")
                return Proposal(
                    o.getLong("createdAt"),
                    List(changes.length()) { changes.getJSONObject(it) }.map { BasalTunerRules.Change(it.getInt("hour"), it.getDouble("from"), it.getDouble("to")) },
                    o.getString("headline"), List(reasons.length()) { reasons.getString(it) }, o.getDouble("totalBefore"), o.getDouble("totalAfter"),
                    runCatching { Status.valueOf(o.getString("status")) }.getOrDefault(Status.OUTDATED), o.optLong("decidedAt"), if (o.isNull("outcome")) null else o.getString("outcome")
                )
            }
        }
    }

    /** Hours left alone until a moment: changed lately, or a proposal for them declined. */
    class Rest(val until: Map<Int, Long>, val declined: Set<Int>) {

        fun after(hours: Collection<Int>, untilEpochMs: Long, declined: Boolean) =
            Rest(until + hours.associateWith { untilEpochMs }, if (declined) this.declined + hours else this.declined - hours.toSet())

        /** Without what has run out. */
        fun at(nowEpochMs: Long): Rest {
            val live = until.filterValues { it > nowEpochMs }
            return Rest(live, declined.intersect(live.keys))
        }

        fun toJson(): JSONObject = JSONObject().put("until", JSONObject().apply { until.forEach { (hour, moment) -> put(hour.toString(), moment) } }).put("declined", JSONArray(declined.sorted()))

        companion object {

            val NONE = Rest(emptyMap(), emptySet())

            fun fromJson(o: JSONObject): Rest {
                val until = o.optJSONObject("until") ?: JSONObject()
                val declined = o.optJSONArray("declined") ?: JSONArray()
                return Rest(until.keys().asSequence().associate { it.toInt() to until.getLong(it) }, List(declined.length()) { declined.getInt(it) }.toSet())
            }
        }
    }
}
