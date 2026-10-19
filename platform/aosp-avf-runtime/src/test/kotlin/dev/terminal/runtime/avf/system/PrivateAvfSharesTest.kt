package dev.terminal.runtime.avf.system

import dev.terminal.runtime.api.GuestByteStream
import dev.terminal.runtime.api.SharedDirectoryExport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PrivateAvfSharesTest {
    private class Stream : GuestByteStream {
        override val input = ByteArrayInputStream(byteArrayOf(1)) // Explicit guest rejection.
        override val output = ByteArrayOutputStream()
        var closed = false
        override fun close() { closed = true }
    }
    private val folders = listOf(SharedDirectoryExport(UUID.randomUUID().toString(), "Code", "/unused"))

    @Test fun rejectedBootsCloseStreamsAndReturnTheGlobalConnectionBudget() {
        repeat(300) {
            val stream = Stream()
            PrivateAvfShares(folders).use { owner ->
                val failure = runCatching { owner.start { stream } }.exceptionOrNull()
                assertEquals("Guest rejected private sharing configuration", failure?.message)
                assertFalse(owner.ready)
            }
            assertTrue(stream.closed)
        }
    }

    @Test fun stopDuringConnectClosesLateConnectionAndCannotBecomeReady() {
        val owner = PrivateAvfShares(folders)
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val stream = Stream()
        val worker = Thread { runCatching { owner.start { entered.countDown(); resume.await(); stream } } }
        worker.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        owner.close()
        resume.countDown()
        worker.join(2_000)
        assertFalse(worker.isAlive)
        assertTrue(stream.closed)
        assertFalse(owner.ready)
        owner.close()
    }
}
