package app.aaps.pump.combowatch.executor

import app.aaps.pump.combowatch.protocol.BolusKind
import app.aaps.pump.combowatch.protocol.PumpEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventOutboxTest {

    private fun bolus(at: Long = 1L) =
        PumpEvent(seq = 0, type = PumpEvent.Type.BOLUS_INFUSED, timestampEpochMs = at, bolusId = at, bolusTenthsIU = 3, bolusKind = BolusKind.SMB)

    private fun tbrEnded(at: Long = 1L) = PumpEvent(seq = 0, type = PumpEvent.Type.TBR_ENDED, timestampEpochMs = at)

    @Test fun `events get increasing sequence numbers and stay until acknowledged`() {
        val outbox = EventOutbox()
        val a = outbox.append(bolus(1))
        val b = outbox.append(tbrEnded(2))
        assertEquals(listOf(1L, 2L), listOf(a.seq, b.seq))
        assertEquals(2, outbox.pending().size)

        outbox.acknowledge(1L)
        assertEquals(listOf(2L), outbox.pending().map { it.seq })
    }

    @Test fun `an acknowledgement that arrives twice changes nothing`() {
        val outbox = EventOutbox()
        outbox.append(bolus())
        outbox.acknowledge(1L)
        outbox.acknowledge(1L)
        assertTrue(outbox.pending().isEmpty())
    }

    @Test fun `every change is persisted together with the next sequence number`() {
        val saved = mutableListOf<Pair<Int, Long>>()
        val outbox = EventOutbox(persist = { events, nextSeq -> saved.add(events.size to nextSeq) })
        outbox.append(bolus())
        outbox.append(bolus())
        outbox.acknowledge(1L)
        assertEquals(listOf(1 to 2L, 2 to 3L, 1 to 3L), saved)
    }

    @Test fun `a restored outbox continues numbering after what it already holds`() {
        val restored = EventOutbox(initial = listOf(bolus().copy(seq = 7)), initialNextSeq = 3)
        assertEquals(8L, restored.append(tbrEnded()).seq)
    }

    @Test fun `on overflow boluses are the last events to be dropped`() {
        val outbox = EventOutbox(maxEvents = 3)
        outbox.append(bolus(1))
        outbox.append(tbrEnded(2))
        outbox.append(bolus(3))
        outbox.append(bolus(4))

        assertEquals(1, outbox.droppedCount)
        assertTrue(outbox.pending().all { it.type == PumpEvent.Type.BOLUS_INFUSED })
    }
}
