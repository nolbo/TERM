package dev.terminal.runtime.avf.system

import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AvfGuestBootTest {
    private class Connection : Closeable {
        var closed = false
        override fun close() { closed = true }
    }

    @Test fun debianReadinessDependsOnSessiondWithoutAnyPayloadCallback() = runBlocking {
        val boot = AvfGuestBoot()
        val connection = Connection()
        var attempts = 0
        val ready = boot.awaitSessiond(1_000, 1) {
            if (++attempts < 3) throw IOException("Connection refused")
            connection
        }
        assertSame(connection, ready)
        assertEquals(3, attempts)
        assertFalse(connection.closed)
        ready.close()
    }

    @Test fun platformFailureInterruptsRetriesAndRetainsItsReason() = runBlocking {
        val boot = AvfGuestBoot()
        val platformError = IllegalStateException("AVF error 4: failed to launch crosvm")
        var attempts = 0
        val failure = runCatching {
            boot.awaitSessiond<Connection>(1_000, 1) {
                attempts++
                boot.fail(platformError)
                throw IOException("Connection refused")
            }
        }.exceptionOrNull()
        assertEquals(platformError.javaClass, failure?.javaClass)
        assertEquals(platformError.message, failure?.message)
        assertEquals(1, attempts)
    }

    @Test fun vmDeathDuringConnectionClosesTheDescriptor() = runBlocking {
        val boot = AvfGuestBoot()
        val connection = Connection()
        val stopped = IllegalStateException("AVF stopped (reason 6)")
        val failure = runCatching {
            boot.awaitSessiond(1_000, 1) { boot.fail(stopped); connection }
        }.exceptionOrNull()
        assertEquals(stopped.javaClass, failure?.javaClass)
        assertEquals(stopped.message, failure?.message)
        assertTrue(connection.closed)
    }

    @Test fun unreachableSessiondHasABoundedActionableFailure() = runBlocking {
        val refused = IOException("Connection refused")
        val failure = runCatching {
            AvfGuestBoot().awaitSessiond<Connection>(50, 1) { throw refused }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("vsock port 40500"))
        assertSame(refused, failure.cause)
    }

    @Test fun ownerCancellationIsNotConvertedToABootFailure() = runBlocking {
        val attempt = async {
            AvfGuestBoot().awaitSessiond<Connection>(10_000, 1) { throw IOException("not yet listening") }
        }
        delay(10)
        attempt.cancel()
        assertTrue(runCatching { attempt.await() }.exceptionOrNull() is CancellationException)
    }
}
