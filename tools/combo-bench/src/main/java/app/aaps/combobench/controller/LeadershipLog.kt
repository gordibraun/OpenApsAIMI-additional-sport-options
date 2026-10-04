package app.aaps.combobench.controller

import app.aaps.pump.combowatch.executor.AutonomyPolicy
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Who leads the pump's basal, and every moment that changed: the phone, by its lease; the watch,
 * once the phone has been silent long enough; nobody, when the phone is away and the watch cannot
 * or may not step in. One line per change, dated when the change happened rather than when the
 * watch happened to look, in the owner's language and with the reason. The face shows the current
 * leader; the log screen shows the lines.
 */
class LeadershipLog(
    private val load: () -> List<JSONObject>,
    private val save: (List<JSONObject>) -> Unit
) {

    enum class Leader { PHONE, WATCH, WATCH_OBSERVING, NOBODY }

    class Entry(val atEpochMs: Long, val leader: Leader, val text: String) {

        fun toJson(): JSONObject = JSONObject().put("at", atEpochMs).put("leader", leader.name).put("text", text)

        companion object {

            fun fromJson(json: JSONObject) = Entry(
                json.getLong("at"), runCatching { Leader.valueOf(json.getString("leader")) }.getOrDefault(Leader.NOBODY), json.optString("text")
            )
        }
    }

    private val entries: MutableList<Entry> = runCatching { load().map { Entry.fromJson(it) } }.getOrDefault(emptyList()).toMutableList()

    @Synchronized fun current(): Entry? = entries.lastOrNull()

    @Synchronized fun entries(): List<Entry> = entries.toList()

    /**
     * Look at the standing now and write a line if the leader changed.
     *
     * @param leaseExpiresEpochMs when the phone's last lease ran or runs out, by the phone's clock.
     * @param leaseRevoked true when the phone's last word was a revocation: it drives the pump itself.
     */
    @Synchronized fun observe(
        standing: AutonomyPolicy.Standing,
        mode: AutonomyPolicy.Mode,
        nowEpochMs: Long,
        phoneHeardEpochMs: Long,
        leaseExpiresEpochMs: Long?,
        leaseRevoked: Boolean
    ): Entry? {
        val leader = leaderOf(standing, mode) ?: return null
        val previous = entries.lastOrNull()
        if (leader == previous?.leader) return null
        val phoneAway = standing is AutonomyPolicy.Standing.Alone || (standing is AutonomyPolicy.Standing.NotAlone && standing.reason !in PHONE_REASONS)
        val at = if (phoneAway) aloneSince(phoneHeardEpochMs, leaseExpiresEpochMs, nowEpochMs) else nowEpochMs
        val entry = Entry(at, leader, text(leader, standing, phoneHeardEpochMs, leaseExpiresEpochMs, leaseRevoked, previous?.leader))
        entries += entry
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        runCatching { save(entries.map { it.toJson() }) }
        return entry
    }

    private fun leaderOf(standing: AutonomyPolicy.Standing, mode: AutonomyPolicy.Mode): Leader? = when (standing) {
        is AutonomyPolicy.Standing.Alone    -> when (mode) {
            AutonomyPolicy.Mode.ACTIVE  -> Leader.WATCH
            AutonomyPolicy.Mode.OBSERVE -> Leader.WATCH_OBSERVING
            AutonomyPolicy.Mode.OFF     -> Leader.NOBODY
        }

        is AutonomyPolicy.Standing.NotAlone -> when (standing.reason) {
            in PHONE_REASONS     -> Leader.PHONE
            // Passing states inside the watch's own work: not a change of who leads.
            in TRANSIENT_REASONS -> null
            else                 -> Leader.NOBODY
        }
    }

    /** The later of the lease running out and the silence growing long enough; now if neither is known. */
    private fun aloneSince(phoneHeardEpochMs: Long, leaseExpiresEpochMs: Long?, nowEpochMs: Long): Long {
        val silence = phoneHeardEpochMs.takeIf { it > 0L }?.let { it + AutonomyPolicy.MIN_PHONE_SILENCE_MS }
        val since = listOfNotNull(silence, leaseExpiresEpochMs).maxOrNull() ?: nowEpochMs
        return since.coerceAtMost(nowEpochMs)
    }

    private fun text(
        leader: Leader, standing: AutonomyPolicy.Standing, phoneHeardEpochMs: Long, leaseExpiresEpochMs: Long?, leaseRevoked: Boolean, previous: Leader?
    ): String {
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
        val heard = phoneHeardEpochMs.takeIf { it > 0L }?.let { "Телефона не слышно с ${clock.format(Date(it))}" } ?: "Телефона не слышно"
        val lease = leaseExpiresEpochMs?.let { ", аренда истекла ${clock.format(Date(it))}" } ?: ""
        return when (leader) {
            Leader.WATCH           -> "$heard$lease: базал ведут часы."
            Leader.WATCH_OBSERVING -> "$heard$lease: часы одни, но только наблюдают (режим наблюдения), помпа идёт по профилю."
            Leader.NOBODY          -> when (standing) {
                is AutonomyPolicy.Standing.Alone    -> "$heard$lease, автономия выключена: помпа идёт по профилю, часы ничего не делают."
                is AutonomyPolicy.Standing.NotAlone -> "$heard: часы вести не могут, ${reasonText(standing.reason)}."
            }

            Leader.PHONE           -> when {
                leaseRevoked                                    -> "Телефон забрал управление себе: помпу ведёт он сам."
                previous == null                                -> "Телефон на связи: ведёт телефон."
                else                                            -> "Телефон снова на связи: ведёт телефон."
            }
        }
    }

    private fun reasonText(reason: String): String = when (reason) {
        "no pump is paired with this watch"                 -> "помпа не привязана"
        "the phone has never been in charge of this watch" -> "телефон ещё ни разу не передавал управление"
        "the phone left no snapshot"                        -> "телефон не оставил данных для расчёта"
        "the phone's permission has run out"                -> "данным телефона больше суток"
        "the phone's lease was for another pump",
        "the phone's snapshot is of another pump"           -> "телефон настроен на другую помпу"
        else                                                -> reason
    }

    companion object {

        private const val MAX_ENTRIES = 200
        private val PHONE_REASONS = setOf("the phone is in charge", "the phone took control back")
        private val TRANSIENT_REASONS = setOf("an earlier command is not settled", "the pump is in use")
    }
}
