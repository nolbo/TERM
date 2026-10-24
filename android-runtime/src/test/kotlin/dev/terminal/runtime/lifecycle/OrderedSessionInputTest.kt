package dev.terminal.runtime.lifecycle

import dev.terminal.runtime.session.SessionInputTarget
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class OrderedSessionInputTest {
    private fun target(owner: Any, id: String, current: () -> Boolean = { true },
        write: suspend (ByteArray) -> Unit): SessionInputTarget = SessionInputTarget(owner, id, current) { bytes ->
        write(bytes); Result.success(bytes.size)
    }

    @Test fun sameEnvironmentRemainsOrderedWhileAnotherEnvironmentMakesProgress() = runBlocking {
        val worker = CoroutineScope(coroutineContext + SupervisorJob())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val siblingDone = CompletableDeferred<Unit>()
        val received = mutableListOf<String>()
        val input = OrderedSessionInput(worker)
        val owner = Any()
        val first = target(owner, "session-a") { bytes ->
            if (received.isEmpty()) {
                entered.complete(Unit)
                release.await()
            }
            received += bytes.decodeToString()
            if (received.size == 3) done.complete(Unit)
        }
        val second = target(owner, "session-b") { bytes ->
            received += bytes.decodeToString()
            if (received.size == 3) done.complete(Unit)
        }
        val sibling = target(Any(), "another-environment") { siblingDone.complete(Unit) }
        try {
            assertTrue(input.offer(first, "echo ".toByteArray()))
            withTimeout(5000) { entered.await() }
            val text = "one\\\rtwo".toByteArray()
            assertTrue(input.offer(second, text))
            text.fill(0)
            assertTrue(input.offer(first, "\r".toByteArray()))
            assertTrue(input.offer(sibling, byteArrayOf(1)))
            withTimeout(5_000) { siblingDone.await() }
            assertTrue(received.isEmpty())
            release.complete(Unit)
            withTimeout(5000) { done.await() }
            assertEquals(listOf("echo ", "one\\\rtwo", "\r"), received)
        } finally { input.close(); worker.cancel() }
    }

    @Test fun capacityIsSharedByAllOwnersAndIncludesInFlightWrites() = runBlocking {
        val worker = CoroutineScope(coroutineContext + SupervisorJob())
        val input = OrderedSessionInput(worker, capacity = 3)
        val enteredA = CompletableDeferred<Unit>(); val enteredB = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>(); val releaseB = CompletableDeferred<Unit>()
        val ownerA = Any()
        val first = target(ownerA, "a") { enteredA.complete(Unit); releaseA.await() }
        val second = target(Any(), "b") { enteredB.complete(Unit); releaseB.await() }
        val queued = target(ownerA, "a-second") { }
        val delivered = CompletableDeferred<Unit>()
        val third = target(Any(), "c") { delivered.complete(Unit) }
        try {
            assertTrue(input.offer(first, byteArrayOf(1)))
            assertTrue(input.offer(second, byteArrayOf(2)))
            withTimeout(5_000) { enteredA.await(); enteredB.await() }
            assertTrue(input.offer(queued, byteArrayOf(3)))
            assertFalse(input.offer(third, byteArrayOf(4)))
            releaseB.complete(Unit)
            withTimeout(5_000) { while (!input.offer(third, byteArrayOf(4))) yield(); delivered.await() }
            assertFalse("Owner A remains suspended", releaseA.isCompleted)
        } finally { input.close(); worker.cancel() }
    }

    @Test fun queuedInputIsNeverSentToAReplacementOwner() = runBlocking {
        val worker = CoroutineScope(coroutineContext + SupervisorJob())
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val replacementDone = CompletableDeferred<Unit>(); val rejected = CompletableDeferred<Unit>()
        val input = OrderedSessionInput(worker, onFailure = { id, _ -> if (id == "old-queued") rejected.complete(Unit) })
        val oldOwner = Any()
        var oldCurrent = true
        val received = mutableListOf<String>()
        val first = target(oldOwner, "old-in-flight", { oldCurrent }) { entered.complete(Unit); release.await() }
        val stale = target(oldOwner, "old-queued", { oldCurrent }) { received += "stale" }
        val replacement = target(Any(), "new-owner") { received += "new"; replacementDone.complete(Unit) }
        try {
            assertTrue(input.offer(first, byteArrayOf(1)))
            withTimeout(5_000) { entered.await() }
            assertTrue(input.offer(stale, byteArrayOf(2)))
            oldCurrent = false
            assertTrue(input.offer(replacement, byteArrayOf(3)))
            withTimeout(5_000) { replacementDone.await() }
            assertFalse(input.offer(stale, byteArrayOf(4)))
            release.complete(Unit)
            withTimeout(5_000) { rejected.await() }
            assertEquals(listOf("new"), received)
        } finally { input.close(); worker.cancel() }
    }

    @Test fun failureIsReportedOnceWithoutRetryAndCannotBlockHealthyOwners() = runBlocking {
        val worker = CoroutineScope(coroutineContext + SupervisorJob())
        val failed = CompletableDeferred<Unit>(); val healthyDone = CompletableDeferred<Unit>()
        var calls = 0
        var notifications = 0
        val input = OrderedSessionInput(worker, onFailure = { _, _ ->
            notifications++; failed.complete(Unit); throw IllegalStateException("observer failed")
        })
        try {
            assertTrue(input.offer(target(Any(), "broken") { calls++; throw IOException("unknown result") }, byteArrayOf(1)))
            assertTrue(input.offer(target(Any(), "healthy") { healthyDone.complete(Unit) }, byteArrayOf(2)))
            withTimeout(5_000) { failed.await(); healthyDone.await() }
            assertEquals(1, calls); assertEquals(1, notifications)
            assertTrue(worker.isActive)
        } finally { input.close(); worker.cancel() }
    }

    @Test fun closingCancelsOnlyTheQueueAndNeverDeliversItsPendingInput() = runBlocking {
        val worker = CoroutineScope(coroutineContext + SupervisorJob())
        val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val input = OrderedSessionInput(worker)
        var queuedWrites = 0
        val owner = Any()
        try {
            assertTrue(input.offer(target(owner, "active") {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }, byteArrayOf(1)))
            withTimeout(5_000) { entered.await() }
            val queued = target(owner, "queued") { queuedWrites++ }
            assertTrue(input.offer(queued, byteArrayOf(2)))
            input.close(); input.close()
            withTimeout(5_000) { cancelled.await() }
            assertEquals(0, queuedWrites)
            assertFalse(input.offer(queued, byteArrayOf(3)))
            assertTrue(worker.isActive)
        } finally { input.close(); worker.cancel() }
    }

    @Test fun aCancelledLaneDropsUnsentInputAndReleasesTheSharedCapacity() = runBlocking {
        val worker = CoroutineScope(coroutineContext + SupervisorJob())
        val failed = CompletableDeferred<Unit>(); val healthyDone = CompletableDeferred<Unit>()
        val failures = mutableListOf<String>()
        val input = OrderedSessionInput(worker, capacity = 2, onFailure = { id, _ ->
            failures += id; if (failures.size == 2) failed.complete(Unit)
        })
        val owner = Any()
        var queuedWrites = 0
        try {
            assertTrue(input.offer(target(owner, "active") { throw CancellationException("owner cancelled") }, byteArrayOf(1)))
            assertTrue(input.offer(target(owner, "queued") { queuedWrites++ }, byteArrayOf(2)))
            withTimeout(5_000) { failed.await() }
            assertEquals(listOf("active", "queued"), failures)
            assertEquals(0, queuedWrites)
            assertTrue(input.offer(target(Any(), "healthy") { healthyDone.complete(Unit) }, byteArrayOf(3)))
            withTimeout(5_000) { healthyDone.await() }
            assertTrue(worker.isActive)
        } finally { input.close(); worker.cancel() }
    }

    @Test fun cancellingTheParentBeforeWorkersStartRejectsLaterInput() = runBlocking {
        val worker = CoroutineScope(coroutineContext + SupervisorJob())
        val input = OrderedSessionInput(worker)
        var delivered = 0
        val destination = target(Any(), "session") { delivered++ }
        assertTrue(input.offer(destination, byteArrayOf(1)))
        worker.cancel()
        assertFalse(input.offer(destination, byteArrayOf(2)))
        yield()
        assertEquals(0, delivered)
        input.close()
    }
}
