package app.aaps.combobench

import org.junit.Assert.*
import org.junit.Test

class ControlSessionWriterTest {
    @Test fun everyPacketIncludingDisconnectWaitsAfterPreviousWrite() {
        var clock = 0L
        val sentAt = mutableListOf<Long>()
        val writer = ControlSessionWriter({ clock }, { clock += it }, {}, { "CONTROL" },
            { sentAt.add(clock); clock += 10 }, {}, {})
        repeat(3) { writer.send(emptyList()) }
        assertEquals(listOf(0L, 210L, 420L), sentAt)
    }

    @Test fun failedWriteNeverClaimsCompletion() {
        var completed = false
        val writer = ControlSessionWriter({ 0L }, {}, {}, { "CTRL_DISCONNECT" },
            { error("simulated write failure") }, {}, { completed = true })
        assertTrue(runCatching { writer.send(emptyList()) }.isFailure)
        assertFalse(completed)
    }

    @Test fun deadlineOrPacketGateStopsBeforeWrite() {
        for (activeFailure in listOf(true, false)) {
            var writes = 0
            val writer = ControlSessionWriter({ 0L }, {}, { check(!activeFailure) },
                { error("forbidden packet") }, { writes++ }, {}, {})
            assertTrue(runCatching { writer.send(emptyList()) }.isFailure)
            assertEquals(0, writes)
        }
    }
}
