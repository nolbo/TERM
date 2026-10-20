package dev.terminal.runtime.image

import dev.terminal.protocol.FrameKind
import dev.terminal.protocol.ProtocolFrame
import dev.terminal.protocol.Service
import dev.terminal.runtime.api.GuestConnection
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AvfPackageInitializerTest {
    @Test fun updateAndHealthRequestsHaveDistinctArgumentFreeOpcodes() = runBlocking {
        for (health in listOf(false, true)) {
            val connection = Connection()
            val initialized = async { AvfPackageInitializer.initialize(connection, maintenance = !health, healthCheck = health) }
            val request = connection.sent.receive()
            assertArrayEquals(byteArrayOf(if (health) 0x42 else 0x41), request.payload)
            connection.frames.send(reply(FrameKind.RESPONSE, ByteBuffer.allocate(8).putLong(123).array()))
            assertEquals(123L, initialized.await())
            assertTrue(connection.closed)
        }
    }
    @Test fun sendsOnlyTheFixedRequestAndAcceptsACompletedRefreshAfterProgress() = runBlocking {
        val connection = Connection()
        val initialized = async { AvfPackageInitializer.initialize(connection) }
        val request = connection.sent.receive()
        assertEquals(Service.LIFECYCLE, request.service)
        assertArrayEquals(byteArrayOf(0x40), request.payload)
        connection.frames.send(reply(FrameKind.STREAM, byteArrayOf(0x40)))
        connection.frames.send(reply(FrameKind.RESPONSE, ByteBuffer.allocate(8).putLong(1_800_000_000).array()))
        assertEquals(1_800_000_000L, initialized.await())
        assertTrue(connection.closed)
    }

    @Test fun readFailureIsContainedAndDoesNotCancelTheRuntimeOwner() = runBlocking {
        val connection = Connection()
        val initialized = async { runCatching { AvfPackageInitializer.initialize(connection) } }
        connection.sent.receive()
        connection.frames.close(IOException("vsock reset"))
        assertTrue(initialized.await().exceptionOrNull() is IOException)
        assertTrue(connection.closed)
        assertTrue(isActive)
    }

    @Test fun malformedOrWrongServiceResultsCannotCompleteInitialization() = runBlocking {
        for (frame in listOf(reply(FrameKind.RESPONSE, byteArrayOf()),
            reply(FrameKind.RESPONSE, ByteArray(8)),
            ProtocolFrame(service = Service.SESSION, kind = FrameKind.RESPONSE, requestId = 1, payload = ByteArray(8)))) {
            val connection = Connection()
            val initialized = async { runCatching { AvfPackageInitializer.initialize(connection) } }
            connection.sent.receive()
            connection.frames.send(frame)
            assertTrue(initialized.await().isFailure)
            assertTrue(connection.closed)
        }
    }

    @Test fun timeoutClosesTheDescriptorAndUnblocksTheIoProducer() = runBlocking {
        val released = CountDownLatch(1)
        var closed = false
        val connection = object : GuestConnection {
            override val guestCid = 2
            override suspend fun send(frame: ProtocolFrame) = Result.success(Unit)
            override fun incomingFrames(): Flow<ProtocolFrame> = channelFlow {
                withContext(Dispatchers.IO) {
                    check(released.await(5, TimeUnit.SECONDS)) { "Descriptor close did not unblock the reader" }
                }
            }
            override fun close() { closed = true; released.countDown() }
        }
        val result = withTimeout(2_000) { runCatching { AvfPackageInitializer.initialize(connection, 100) } }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException)
        assertTrue(closed)
        assertTrue(isActive)
    }

    private fun reply(kind: FrameKind, payload: ByteArray) =
        ProtocolFrame(service = Service.LIFECYCLE, kind = kind, requestId = 1, payload = payload)

    private class Connection : GuestConnection {
        override val guestCid = 2
        val sent = Channel<ProtocolFrame>(Channel.UNLIMITED)
        val frames = Channel<ProtocolFrame>(Channel.UNLIMITED)
        var closed = false
        override suspend fun send(frame: ProtocolFrame): Result<Unit> { sent.send(frame); return Result.success(Unit) }
        override fun incomingFrames() = frames.receiveAsFlow()
        override fun close() { closed = true; frames.close() }
    }
}
