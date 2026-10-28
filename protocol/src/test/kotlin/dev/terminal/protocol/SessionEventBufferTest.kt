package dev.terminal.protocol

import org.junit.Assert.*
import org.junit.Test

class SessionEventBufferTest {
    private fun output(id: String, size: Int) = SessionEvent.Output(id, ByteArray(size))
    private fun drain(buffer: SessionEventBuffer) = buildList { while (true) add(buffer.poll() ?: break) }

    @Test fun healthyDeliveryKeepsExactOrdering() {
        val buffer = SessionEventBuffer()
        val events = listOf(output("a", 100), SessionEvent.OutputGap("a", -1),
            SessionEvent.Resized("a", 30u, 90u, Any()), output("a", 200), SessionEvent.Exited("a", 0))
        events.forEach { assertTrue(buffer.offer(it)) }
        assertEquals(events, drain(buffer))
    }

    @Test fun oneMaximumGuestImageFitsWithoutArtificialLoss() {
        val buffer = SessionEventBuffer()
        repeat(293) { assertTrue(buffer.offer(output("image", 29 * 1024))) }
        val events = drain(buffer)
        assertEquals(293, events.size)
        assertTrue(events.all { it is SessionEvent.Output })
    }

    @Test fun overflowReportsLostBytesBeforeResizeAndExitWithoutMixingSessions() {
        val buffer = SessionEventBuffer(maxBytes = 100, maxEvents = 20)
        val resize = SessionEvent.Resized("a", 40u, 120u, Any())
        val exit = SessionEvent.Exited("b", 7)
        val newest = output("a", 40)
        listOf(output("a", 40), output("b", 20), resize, output("a", 20), exit, newest)
            .forEach { assertTrue(buffer.offer(it)) }
        assertEquals(listOf(SessionEvent.OutputGap("a", 40), resize, SessionEvent.OutputGap("b", 20), exit,
            SessionEvent.OutputGap("a", 20), newest), drain(buffer))
    }

    @Test fun repeatedOverflowBoundsMetadataAndRetainsUnknownGap() {
        val buffer = SessionEventBuffer(maxBytes = 10, maxEvents = 4)
        assertTrue(buffer.offer(SessionEvent.OutputGap("a", -1)))
        val packet = output("a", 1)
        repeat(1000) { assertTrue(buffer.offer(packet)) }
        val events = drain(buffer)
        assertTrue(events.size <= 4)
        assertEquals(SessionEvent.OutputGap("a", -1), events.first())
        assertTrue(events.filterIsInstance<SessionEvent.Output>().sumOf { it.bytes.size } <= 10)
    }

    @Test fun oversizedSingleOutputBecomesGapAndControlsStillPass() {
        val buffer = SessionEventBuffer(maxBytes = 10)
        assertTrue(buffer.offer(output("a", 20)))
        val exit = SessionEvent.Exited("a", 0)
        assertTrue(buffer.offer(exit))
        assertEquals(listOf(SessionEvent.OutputGap("a", 20), exit), drain(buffer))
    }

    @Test fun controlOnlyOverloadFailsClosedInsteadOfLosingAnOrderingMarker() {
        val buffer = SessionEventBuffer(maxEvents = 2)
        repeat(2) { assertTrue(buffer.offer(SessionEvent.Resized("a", 30u, 90u, Any()))) }
        assertFalse(buffer.offer(SessionEvent.Exited("a", 0)))
        assertNull(buffer.poll())
        assertFalse(buffer.offer(output("a", 1)))
    }

    @Test fun closeReleasesOutputAndRejectsLateEvents() {
        val buffer = SessionEventBuffer()
        assertTrue(buffer.offer(output("a", 100)))
        buffer.close()
        assertNull(buffer.poll())
        assertFalse(buffer.offer(output("a", 1)))
    }
}
