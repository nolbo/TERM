package dev.terminal.runtime.lifecycle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class TerminalOperationQueueTest {
    @Test fun sameSessionKeepsArrivalOrderWhileSiblingMakesProgressAndCapacityIncludesWaiters() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val sibling = CompletableDeferred<Unit>(); val done = CompletableDeferred<Unit>()
        val order = mutableListOf<Int>()
        val queue = TerminalOperationQueue(scope, capacity = 3, onFailure = { throw AssertionError(it) })
        try {
            assertTrue(queue.offer("a") { entered.complete(Unit); release.await(); order += 1 })
            withTimeout(5000) { entered.await() }
            assertTrue(queue.offer("a") { order += 2; done.complete(Unit) })
            assertTrue(queue.offer("b") { sibling.complete(Unit) })
            assertFalse(queue.offer("c") { fail("Over capacity") })
            withTimeout(5000) { sibling.await() }
            assertTrue(order.isEmpty())
            release.complete(Unit)
            withTimeout(5000) { done.await() }
            assertEquals(listOf(1, 2), order)
        } finally { queue.close(); scope.cancel() }
    }

    @Test fun failureReleasesCapacityAndDoesNotCancelFollowingCommands() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val failure = CompletableDeferred<Throwable>(); val done = CompletableDeferred<Unit>()
        val queue = TerminalOperationQueue(scope, capacity = 1, onFailure = { failure.complete(it) })
        try {
            assertTrue(queue.offer("a") { error("failed resize") })
            withTimeout(5000) { assertEquals("failed resize", failure.await().message) }
            withTimeout(5000) { while (!queue.offer("a") { done.complete(Unit) }) yield(); done.await() }
        } finally { queue.close(); scope.cancel() }
    }

    @Test fun closeCancelsActiveAndQueuedCommandsWithoutCancellingTheService() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val errors = mutableListOf<Throwable>(); var queuedRan = false
        val queue = TerminalOperationQueue(scope, onFailure = { errors += it })
        try {
            assertTrue(queue.offer("a") {
                try { entered.complete(Unit); awaitCancellation() } finally { cancelled.complete(Unit) }
            })
            withTimeout(5000) { entered.await() }
            assertTrue(queue.offer("a") { queuedRan = true })
            queue.close()
            withTimeout(5000) { cancelled.await() }
            assertFalse(queue.offer("b") { fail("Closed queue accepted command") })
            yield()
            assertFalse(queuedRan); assertTrue(errors.isEmpty())
            assertTrue(scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
        } finally { queue.close(); scope.cancel() }
    }

    @Test fun closeBeforeDispatcherStartsDiscardsAllCommands() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var ran = false
        val queue = TerminalOperationQueue(scope, onFailure = { })
        repeat(512) { assertTrue(queue.offer("session-$it") { ran = true }) }
        assertFalse(queue.offer("overflow") { })
        queue.close(); yield()
        assertFalse(ran)
        assertFalse(queue.offer("closed") { })
        scope.cancel()
    }
}
