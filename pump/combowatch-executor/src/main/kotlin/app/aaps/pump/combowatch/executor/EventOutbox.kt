package app.aaps.pump.combowatch.executor

import app.aaps.pump.combowatch.protocol.PumpEvent

/**
 * Holds what the watch's driver observed on the pump until the phone has acknowledged it.
 *
 * The phone computes insulin on board from its own records, so a bolus the watch delivered and
 * the phone never heard about is the dangerous kind of gap: the loop would dose as if that
 * insulin did not exist. Events therefore survive restarts, are resent until acknowledged, and
 * the phone's handling of them is keyed on the pump's own ids so that a repeat changes nothing.
 */
class EventOutbox(
    initial: List<PumpEvent> = emptyList(),
    initialNextSeq: Long = 1L,
    private val maxEvents: Int = 1000,
    private val persist: (events: List<PumpEvent>, nextSeq: Long) -> Unit = { _, _ -> }
) {

    private val events = ArrayDeque(initial)
    private var nextSeq = maxOf(initialNextSeq, (initial.maxOfOrNull { it.seq } ?: 0L) + 1)

    /** Events dropped because the outbox overflowed while the phone was away. Never silent. */
    var droppedCount: Int = 0
        private set

    /** Store [event] under the next sequence number; the seq it carried on the way in is ignored. */
    @Synchronized
    fun append(event: PumpEvent): PumpEvent {
        val stored = event.copy(seq = nextSeq++)
        events.addLast(stored)
        while (events.size > maxEvents) {
            // Boluses are what insulin-on-board is computed from, so they are the last to go.
            val victim = events.firstOrNull { it.type != PumpEvent.Type.BOLUS_INFUSED } ?: events.first()
            events.remove(victim)
            droppedCount++
        }
        persist(events.toList(), nextSeq)
        return stored
    }

    @Synchronized
    fun pending(): List<PumpEvent> = events.toList()

    /**
     * Drop the events [predicate] selects without the phone having seen them, and say how many.
     * This is the opposite of what the outbox is for, so it has exactly one use: the records of
     * a bench pump that was never connected to anybody, which must not reach treatment records.
     */
    @Synchronized
    fun discard(predicate: (PumpEvent) -> Boolean): Int {
        val before = events.size
        events.removeAll(predicate)
        if (events.size != before) persist(events.toList(), nextSeq)
        return before - events.size
    }

    /** The phone has processed everything up to and including [upToSeq]. */
    @Synchronized
    fun acknowledge(upToSeq: Long) {
        val before = events.size
        events.removeAll { it.seq <= upToSeq }
        if (events.size != before)
            persist(events.toList(), nextSeq)
    }
}
