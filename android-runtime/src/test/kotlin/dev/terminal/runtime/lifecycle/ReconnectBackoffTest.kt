package dev.terminal.runtime.lifecycle

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ReconnectBackoffTest {
    @Test fun exponentialDelaysCapAndResetAfterSuccessOrExplicitWork() {
        val retry = ReconnectBackoff()
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 15_000L, 30_000L, 60_000L, 120_000L, 120_000L),
            List(8) { retry.nextDelayMs() })
        retry.reset()
        assertEquals(2_000L, retry.nextDelayMs())
    }

    @Test fun explicitWorkInterruptsAnOutstandingLongDelay() = runBlocking {
        val retry = ReconnectBackoff(listOf(60_000))
        val waiting = async { retry.awaitRetry() }
        delay(20)
        assertFalse(waiting.isCompleted)
        retry.reset()
        withTimeout(1_000) { waiting.await() }
    }

    @Test fun retryDemandStopsWithNoRunningSessionsOrPendingWork() {
        assertFalse(reconnectNeeded(0, 0, false, 0))
        assertTrue(reconnectNeeded(1, 0, false, 0))
        assertTrue(reconnectNeeded(0, 1, false, 0))
        assertTrue(reconnectNeeded(0, 0, true, 0))
        assertTrue(reconnectNeeded(0, 0, false, 1))
    }
}
