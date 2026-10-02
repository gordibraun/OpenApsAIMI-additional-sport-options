package app.aaps.pump.combowatch.executor

import app.aaps.pump.combowatch.executor.CommandJournal.Companion.isResolved
import app.aaps.pump.combowatch.protocol.CommandKind
import app.aaps.pump.combowatch.protocol.Outcome

/**
 * Remembers what was done, so that nothing is done twice.
 *
 * The order matters more than the storage: an entry is written *before* the pump is touched and
 * updated after. A watch that dies mid-command therefore comes back knowing that the command was
 * started and that its outcome is unknown — which is exactly the state that blocks further
 * therapy until the pump has been read back.
 */
interface CommandJournal {

    data class Entry(
        val id: String,
        val startedAtEpochMs: Long,
        val outcome: Outcome?,
        val reason: String?,
        /**
         * What the command was, kept so that an unresolved one can be reconciled against the
         * right evidence: a temporary basal against the pump's screen, a bolus against its history.
         */
        val kind: CommandKind? = null,
        val bolusTenthsIU: Int? = null,
        val tbrPercentage: Int? = null
    )

    /** Null when this id was never started. */
    fun entry(id: String): Entry?

    /**
     * The recorded answer for an id that already finished. Null while an entry exists but has no
     * outcome yet, because that command is unresolved rather than answered.
     */
    fun recordedOutcome(id: String): Entry?

    /** Write the entry that says "this was started". Must reach storage before the pump is touched. */
    fun markStarted(
        id: String,
        startedAtEpochMs: Long,
        kind: CommandKind? = null,
        bolusTenthsIU: Int? = null,
        tbrPercentage: Int? = null
    )

    /** Record how it ended. [Outcome.UNKNOWN] leaves the executor awaiting reconciliation. */
    fun markFinished(id: String, outcome: Outcome, reason: String?)

    /** True when some command was started and never resolved. Blocks everything but reads. */
    fun hasUnresolved(): Boolean

    /**
     * The oldest unresolved entry, if any, so the executor knows what it is reconciling.
     * [excludingId] leaves out the command that is running right now: it is unresolved only
     * because it has not finished, and reading the pump at the start of its own session must
     * not be taken as evidence about it.
     */
    fun unresolved(excludingId: String? = null): Entry?

    /** Every entry, oldest first, for persistence and inspection. */
    fun entries(): List<Entry>

    companion object {

        /**
         * One definition of "finished with it", used both for answering a resend and for deciding
         * what may be trimmed. [Outcome.UNKNOWN] is not finished: it is the state that holds
         * therapy back, so treating it as resolved anywhere would quietly release that hold.
         */
        fun Entry.isResolved(): Boolean = outcome != null && outcome != Outcome.UNKNOWN
    }
}

/**
 * In-memory journal with a pluggable persistence hook.
 *
 * [persist] is called on every change and must be durable before it returns; the executor relies
 * on a started entry surviving a crash that happens one instruction later.
 */
class SimpleCommandJournal(
    initial: List<CommandJournal.Entry> = emptyList(),
    private val maxEntries: Int = 200,
    private val persist: (List<CommandJournal.Entry>) -> Unit = {}
) : CommandJournal {

    private val entries = LinkedHashMap<String, CommandJournal.Entry>().apply {
        initial.forEach { put(it.id, it) }
    }

    @Synchronized
    override fun entry(id: String): CommandJournal.Entry? = entries[id]

    @Synchronized
    override fun recordedOutcome(id: String): CommandJournal.Entry? = entries[id]?.takeIf { it.outcome != null }

    @Synchronized
    override fun markStarted(id: String, startedAtEpochMs: Long, kind: CommandKind?, bolusTenthsIU: Int?, tbrPercentage: Int?) {
        entries[id] = CommandJournal.Entry(
            id, startedAtEpochMs, outcome = null, reason = null,
            kind = kind, bolusTenthsIU = bolusTenthsIU, tbrPercentage = tbrPercentage
        )
        // Trim only resolved entries: an unresolved one is the reason therapy is being held, and
        // dropping it to save space would silently unblock the pump.
        while (entries.size > maxEntries) {
            val oldestResolved = entries.values.firstOrNull { it.isResolved() } ?: break
            entries.remove(oldestResolved.id)
        }
        persist(entries.values.toList())
    }

    @Synchronized
    override fun markFinished(id: String, outcome: Outcome, reason: String?) {
        val existing = entries[id] ?: CommandJournal.Entry(id, startedAtEpochMs = 0, outcome = null, reason = null)
        entries[id] = existing.copy(outcome = outcome, reason = reason)
        persist(entries.values.toList())
    }

    @Synchronized
    override fun hasUnresolved(): Boolean = unresolved() != null

    @Synchronized
    override fun unresolved(excludingId: String?): CommandJournal.Entry? =
        entries.values.firstOrNull { !it.isResolved() && (it.id != excludingId) }

    @Synchronized
    override fun entries(): List<CommandJournal.Entry> = entries.values.toList()
}
