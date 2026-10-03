package dev.terminal.runtime.lifecycle

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** Bounded retry rate. Explicit work wakes an outstanding delay without dropping healthy links. */
class ReconnectBackoff(private val delaysMs: List<Long> = listOf(2_000, 4_000, 8_000, 15_000, 30_000, 60_000, 120_000)) {
    init { require(delaysMs.isNotEmpty() && delaysMs.all { it > 0 }) }
    private var attempt = 0
    private val resetSignal = Channel<Unit>(Channel.CONFLATED)
    @Synchronized fun nextDelayMs(): Long = delaysMs[attempt].also {
        if (attempt < delaysMs.lastIndex) attempt++
    }
    @Synchronized fun reset() { attempt = 0; resetSignal.trySend(Unit) }
    suspend fun awaitRetry() {
        // A reset before this failed attempt already affected nextDelayMs().
        resetSignal.tryReceive()
        withTimeoutOrNull(nextDelayMs()) { resetSignal.receive() }
    }
}

internal fun reconnectNeeded(runningSessions: Int, pendingWork: Int, guestWork: Boolean): Boolean =
    runningSessions > 0 || pendingWork > 0 || guestWork
