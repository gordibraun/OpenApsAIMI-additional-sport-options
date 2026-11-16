package app.aaps.pump.combowatch

/**
 * Which temporary basal an "ended" event of the pump may end.
 *
 * When the driver replaces one temporary basal by another it reports two events, in this order: the old one
 * ended, the new one started. Their times are the other way round: the start carries the moment before the
 * pump was programmed, the end the moment after - some twenty seconds later on the watch. AAPS files an end by
 * its time alone ("whatever runs at that moment ends there"), which is right the first time, while the old
 * basal is still the one on record. But events reach the phone at least once, not exactly once: the pair comes
 * in the command's answer and again in the watch's own event message whenever the acknowledgement has not got
 * back yet. The second time the end's moment lies inside the NEW basal, and AAPS cut that one short - to the
 * twenty seconds. The pump went on running it; the phone's records, and with them insulin on board and what
 * the loop believes is running, did not.
 *
 * Found on 16.11.2026: a 340 % basal ran on the pump for 15 minutes (07:24-07:39) and stood in the records as
 * 26 seconds, 0.7 U the loop did not count while glucose went from 111 to 47; at 07:33 the loop wanted the
 * profile's rate, believed nothing was running and sent nothing. Over six weeks about a third of all temporary
 * basals had been filed this way.
 *
 * So the order the pump reported things in is remembered: an end that comes before a start already filed, in
 * that order, and whose moment lies at or after that start, has nothing left to end - what it was about ended
 * where the start replaced it.
 *
 * @param load the newest start filed so far, as (its number in the watch's order, when it started), or null
 * @param save keeps that pair across restarts of the app: a copy of an end can arrive after one
 */
internal class TbrEventOrder(private val load: () -> Pair<Long, Long>?, private val save: (Long, Long) -> Unit) {

    private var newestStart: Pair<Long, Long>? = null
    private var loaded = false

    private fun newest(): Pair<Long, Long>? {
        if (!loaded) {
            newestStart = runCatching(load).getOrNull()
            loaded = true
        }
        return newestStart
    }

    /** A start has been filed: [seq] is its number in the watch's order of events. */
    @Synchronized
    fun started(seq: Long, startedAtEpochMs: Long) {
        val known = newest()
        // A copy of the same start, or of an older one, changes nothing. Numbers far below the known one are
        // not old copies: the watch began counting anew (its data was cleared), and its order is the new one.
        if (known != null && seq <= known.first && known.first - seq <= SEQ_WINDOW) return
        newestStart = seq to startedAtEpochMs
        runCatching { save(seq, startedAtEpochMs) }
    }

    /**
     * True when the end numbered [seq] is of a basal that a start already filed has replaced, so that filing it
     * now would cut the one that replaced it.
     */
    @Synchronized
    fun endIsOfAReplacedBasal(seq: Long, endedAtEpochMs: Long): Boolean {
        val (startSeq, startedAt) = newest() ?: return false
        return seq < startSeq && startSeq - seq <= SEQ_WINDOW && startedAt <= endedAtEpochMs
    }

    companion object {

        /** Copies come within a few events of the original; a gap beyond this is the watch counting anew. */
        const val SEQ_WINDOW = 500L
    }
}
