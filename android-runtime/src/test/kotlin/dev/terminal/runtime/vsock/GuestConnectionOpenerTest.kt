package dev.terminal.runtime.vsock

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.*
import kotlinx.coroutines.launch
import org.junit.Test

class GuestConnectionOpenerTest {
    private class Connection : Closeable {
        val closes = AtomicInteger()
        val closed = CountDownLatch(1)
        override fun close() { closes.incrementAndGet(); closed.countDown() }
    }

    @Test fun suspendConnectReleasesCallerAndClosesTheCancelledLateResult() = kotlinx.coroutines.runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val connection = AutoCloseable { closed.countDown() }
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            GuestConnectionOpener.openSuspending(5_000) { entered.countDown(); release.await(); connection }
        }
        try {
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS))
            job.cancel(); job.join()
            assertEquals(1L, closed.count)
            release.countDown()
            assertTrue(closed.await(1, java.util.concurrent.TimeUnit.SECONDS))
        } finally { release.countDown() }
    }

    @Test fun lateConnectionIsClosedAfterTheCallerTimesOut() {
        val release = CountDownLatch(1)
        val connection = Connection()
        try {
            val error = runCatching { GuestConnectionOpener.open(50) { release.await(); connection } }.exceptionOrNull()
            assertTrue(error is TimeoutException)
            assertEquals(0, connection.closes.get())
        } finally { release.countDown() }
        assertTrue(connection.closed.await(2, TimeUnit.SECONDS))
        assertEquals(1, connection.closes.get())
    }

    @Test fun returnedConnectionTransfersOwnershipAndFailuresDoNotExhaustCapacity() {
        val original = IOException("connect failed")
        assertSame(original, runCatching { GuestConnectionOpener.open<Connection>(500) { throw original } }.exceptionOrNull())
        val linkage = UnsatisfiedLinkError("missing platform implementation")
        val wrapped = runCatching { GuestConnectionOpener.open<Connection>(500) { throw linkage } }.exceptionOrNull()
        assertTrue(wrapped is IOException)
        assertSame(linkage, wrapped?.cause)
        val connection = Connection()
        assertSame(connection, GuestConnectionOpener.open(500) { connection })
        assertEquals(0, connection.closes.get())
        connection.close()
    }

    @Test fun stuckPlatformCallsCannotAccumulateUnboundedWorkersOrQueuedOpens() {
        val entered = CountDownLatch(8)
        val release = CountDownLatch(1)
        val connections = List(8) { Connection() }
        val callers = connections.map { connection -> thread(isDaemon = true) {
            runCatching { GuestConnectionOpener.open(1_000) { entered.countDown(); release.await(); connection } }
        } }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val unexpected = AtomicInteger()
            val error = runCatching { GuestConnectionOpener.open(100) { unexpected.incrementAndGet(); Connection() } }.exceptionOrNull()
            assertTrue(error is RejectedExecutionException)
            assertEquals(0, unexpected.get())
        } finally {
            release.countDown()
            callers.forEach { it.join(2_000) }
            connections.forEach { it.close() }
        }
    }
}
