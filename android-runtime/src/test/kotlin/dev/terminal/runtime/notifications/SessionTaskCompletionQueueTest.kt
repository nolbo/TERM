package dev.terminal.runtime.notifications

import org.junit.Assert.*
import org.junit.Test

class SessionTaskCompletionQueueTest {
    @Test fun unreadMessagesSurviveRepeatedSnapshotsAndAcknowledgeOnlyTheDisplayedItem() {
        val queue = SessionTaskCompletionQueue()
        queue.add("native-a", "Build", 0)
        queue.add("native-b", "Tests", 1)
        val first = queue.snapshot()
        assertEquals(first, queue.snapshot())
        assertEquals(listOf("Build", "Tests"), first.map { it.title })
        assertTrue(queue.acknowledge(first.first().id))
        assertFalse(queue.acknowledge(first.first().id))
        assertEquals(listOf(first.last()), queue.snapshot())
        queue.clear()
        assertTrue(queue.snapshot().isEmpty())
        queue.add("native-c", "New", null)
        assertTrue(queue.snapshot().single().id > first.last().id)
    }

    @Test fun queueIsBoundedAndSessionTitlesCannotInjectControlCharacters() {
        val queue = SessionTaskCompletionQueue()
        repeat(20) { queue.add("native-$it", "\u001b\n\u202e\u2066" + "x".repeat(100), it) }
        val pending = queue.snapshot()
        assertEquals(16, pending.size)
        assertEquals("native-4", pending.first().sessionId)
        assertEquals("native-19", pending.last().sessionId)
        assertTrue(pending.all { it.title == "x".repeat(80) })
    }
}
