package dev.terminal.runtime.lifecycle

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BoundedCallbackQueueTest {
    @Test fun smallOutputsCoalesceWithoutAliasingOrCrossingControlAndSessionBoundaries() {
        lateinit var worker: Runnable
        val events = mutableListOf<String>()
        val queue = BoundedCallbackQueue(Executor { worker = it }, { throw AssertionError(it) })
        val original = "first".toByteArray()
        queue.offerOutput(original, "a") { events += "a:" + it.decodeToString() }
        original.fill(0)
        queue.offerOutput("second".toByteArray(), "a") { fail("Adjacent output must coalesce") }
        queue.offer { events += "resize" }
        queue.offerOutput("third".toByteArray(), "a") { events += "a:" + it.decodeToString() }
        queue.offerOutput("sibling".toByteArray(), "b") { events += "b:" + it.decodeToString() }
        worker.run()
        assertEquals(listOf("a:firstsecond", "resize", "a:third", "b:sibling"), events)
    }

    @Test fun checkpointOutputResizeExitAndDisposalKeepOneOrder() {
        lateinit var worker: Runnable
        val events = mutableListOf<String>()
        val queue = BoundedCallbackQueue(Executor { worker = it }, { throw AssertionError(it) })
        queue.offer(dispose = { events += "checkpoint.closed" }) { events += "checkpoint" }
        queue.offer(10) { events += "output" }
        queue.offer { events += "resize" }
        queue.offer { events += "exit" }
        worker.run()
        assertEquals(listOf("checkpoint", "checkpoint.closed", "output", "resize", "exit"), events)
    }

    @Test fun aBlockedClientCannotBlockAnotherQueueAndOverflowRequiresResync() {
        val workers = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sibling = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val errors = java.util.concurrent.LinkedBlockingQueue<Exception>()
        val slow = BoundedCallbackQueue(workers, { errors.offer(it); failed.countDown() }, maxBytes = 8)
        val fast = BoundedCallbackQueue(workers, { throw AssertionError(it) })
        var disposed = 0
        try {
            assertTrue(slow.offer(8) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            // The in-flight bytes count against the bound; the caller never waits for release.
            assertFalse(slow.offer(1, dispose = { disposed++ }) { fail("Stale output must not be delivered") })
            assertTrue(failed.await(1, TimeUnit.SECONDS))
            assertEquals(BoundedCallbackQueue.RESYNC_REQUIRED, errors.remove().message)
            assertTrue(fast.offer { sibling.countDown() })
            assertTrue(sibling.await(1, TimeUnit.SECONDS))
            assertFalse(slow.offer { fail("An invalidated stream cannot resume raw output") })
            assertEquals(1, disposed)
        } finally { release.countDown(); slow.close(); fast.close(); workers.shutdownNow() }
    }

    @Test fun closeDisposesQueuedCheckpointWithoutCallingTheClient() {
        lateinit var worker: Runnable
        val queue = BoundedCallbackQueue(Executor { worker = it }, { fail("Explicit close is not a delivery error") })
        var disposed = 0
        queue.offer(dispose = { disposed++ }) { fail("Detached client") }
        queue.close(); queue.close()
        worker.run()
        assertFalse(queue.offer(dispose = { disposed++ }) { fail("Closed client") })
        assertEquals(2, disposed)
    }

    @Test fun failedBinderCallClosesRemainingDescriptorsAndReportsOnlyOnce() {
        lateinit var worker: Runnable
        val errors = mutableListOf<Exception>()
        val queue = BoundedCallbackQueue(Executor { worker = it }, { errors += it })
        var disposed = 0
        val failure = IOException("client died")
        queue.offer(dispose = { disposed++ }) { throw failure }
        queue.offer(dispose = { disposed++ }) { fail("Output after a broken checkpoint") }
        worker.run()
        queue.close()
        assertEquals(listOf(failure), errors)
        assertEquals(2, disposed)
    }

    @Test fun taskLimitAlsoBoundsClientsThatNeverConsumeMetadata() {
        lateinit var worker: Runnable
        var failures = 0
        val queue = BoundedCallbackQueue(Executor { worker = it }, { failures++ }, maxTasks = 2)
        assertTrue(queue.offer { fail("Invalidated") })
        assertTrue(queue.offer { fail("Invalidated") })
        assertFalse(queue.offer { fail("Overflow") })
        worker.run()
        assertEquals(1, failures)
    }

    @Test fun exhaustedExecutorCannotLeakCheckpointOrBlockProducer() {
        var disposed = 0
        var failed = false
        val queue = BoundedCallbackQueue(Executor { throw RejectedExecutionException() }, { failed = true })
        assertFalse(queue.offer(dispose = { disposed++ }) { fail("No worker") })
        assertTrue(failed)
        assertEquals(1, disposed)
    }
}
