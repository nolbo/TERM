package dev.terminal.protocol

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolSessionClientTest {
    @Test
    fun mapsAResponseToTheMatchingRequestId() = runBlocking {
        val transport = FakeTransport()
        ProtocolSessionClient(transport, scope = this).use { client ->
            val pending = async { client.list() }
            val request = transport.sent.receive()
            transport.receive(
                ProtocolFrame(
                    service = Service.SESSION,
                    kind = FrameKind.RESPONSE,
                    requestId = request.requestId,
                    payload = SessionResponseCodec.encode(SessionResponse.Listed(emptyList())),
                ),
            )

            assertEquals(emptyList<SessionDescriptor>(), pending.await().getOrThrow())
        }
    }

    @Test
    fun forwardsStreamOutputWithoutCompletingPendingRequests() = runBlocking {
        val transport = FakeTransport()
        ProtocolSessionClient(transport, scope = this).use { client ->
            val output = async<SessionEvent> { withTimeout(1_000) { client.events.first() } }
            yield()
            transport.receive(
                ProtocolFrame(
                    service = Service.SESSION,
                    kind = FrameKind.STREAM,
                    requestId = 0,
                    payload = SessionEventCodec.encode(SessionEvent.Output("session-1", "hello".encodeToByteArray())),
                ),
            )

            assertEquals("hello", (output.await() as SessionEvent.Output).bytes.decodeToString())
        }
    }

    @Test
    fun marksTheClientDisconnectedWhenItsTransportEnds() = runBlocking {
        ProtocolSessionClient(EndingTransport, scope = this).use { client ->
            assertEquals(
                SessionConnectionState.DISCONNECTED,
                withTimeout(1_000) { client.connectionState.first { it == SessionConnectionState.DISCONNECTED } },
            )
        }
    }

    @Test
    fun transportReadFailureFailsPendingRequestsWithoutCancellingTheOwner() = runBlocking {
        val transport = ClosingTransport()
        ProtocolSessionClient(transport, scope = this).use { client ->
            val pending = async { client.list() }
            transport.sent.receive()
            val failure = IOException("AVF vsock connection reset during stop")
            transport.incoming.close(failure)

            withTimeout(1_000) {
                client.connectionState.first { it == SessionConnectionState.DISCONNECTED }
                val error = pending.await().exceptionOrNull()
                assertTrue(error is IOException)
                assertEquals(failure.message, error?.message)
            }
            assertTrue("A failed transport must not cancel its owning scope", isActive)
        }
    }

    @Test
    fun malformedTransportFramesDisconnectWithoutCancellingTheOwner() = runBlocking {
        val transport = ClosingTransport()
        ProtocolSessionClient(transport, scope = this).use { client ->
            transport.incoming.close(IllegalArgumentException("Invalid guest frame length"))
            withTimeout(1_000) {
                client.connectionState.first { it == SessionConnectionState.DISCONNECTED }
            }
            assertTrue(isActive)
        }
    }

    @Test
    fun disconnectedClientRejectsNewRequestsWithoutSendingOrWaitingForTimeout() = runBlocking {
        val transport = ClosingTransport()
        ProtocolSessionClient(transport, scope = this).use { client ->
            transport.incoming.close()
            withTimeout(1_000) {
                client.connectionState.first { it == SessionConnectionState.DISCONNECTED }
                val result = client.list()
                assertTrue(result.isFailure)
                assertFalse(result.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException)
            }
            assertTrue(transport.sent.tryReceive().isFailure)
        }
    }

    @Test
    fun transportCleanupFailureDoesNotEscapeToTheOwner() = runBlocking {
        val transport = ClosingTransport(closeFailure = IOException("Closed vsock descriptor"))
        ProtocolSessionClient(transport, scope = this).use { client ->
            transport.incoming.close()
            withTimeout(1_000) {
                client.connectionState.first { it == SessionConnectionState.DISCONNECTED }
            }
            client.close()
            assertTrue(isActive)
        }
    }

    @Test fun readFailureRecordsItsOriginalStageOnceBeforeExplicitCleanup() = runBlocking {
        val transport = ClosingTransport()
        val records = mutableListOf<Pair<String, Throwable>>()
        val client = ProtocolSessionClient(transport, scope = this, onDiagnostic = { event, error -> records += event to error })
        val original = IOException("original reset")
        transport.incoming.close(original)
        withTimeout(1_000) { client.connectionState.first { it == SessionConnectionState.DISCONNECTED } }
        client.close(); client.close()
        assertEquals(1, records.size)
        assertEquals("session.read.failed", records.single().first)
        assertTrue(records.single().second is IOException)
        assertEquals(original.message, records.single().second.message)
    }

    @Test fun explicitCloseIsDistinctFromEofAndDiagnosticFailureCannotCancelOwner() = runBlocking {
        val transport = ClosingTransport()
        val events = mutableListOf<String>()
        val client = ProtocolSessionClient(transport, scope = this, onDiagnostic = { event, _ ->
            events += event
            throw IOException("diagnostic sink unavailable")
        })
        client.close()
        yield()
        assertEquals(listOf("session.closed"), events)
        assertTrue(isActive)
        assertEquals(SessionConnectionState.DISCONNECTED, client.connectionState.value)
    }

    @Test fun cleanEofIsRecordedWithoutInventingAGuestExit() = runBlocking {
        val events = mutableListOf<String>()
        ProtocolSessionClient(EndingTransport, scope = this, onDiagnostic = { event, _ -> events += event }).use { client ->
            withTimeout(1_000) { client.connectionState.first { it == SessionConnectionState.DISCONNECTED } }
            assertEquals(listOf("session.eof"), events)
        }
        assertEquals(listOf("session.eof"), events)
    }

    @Test fun sendFailureDisconnectsAnOtherwiseOpenReaderWithoutRetryingInput() = runBlocking {
        val calls = AtomicInteger()
        val transport = object : FrameTransport {
            override suspend fun send(frame: ProtocolFrame): Result<Unit> {
                calls.incrementAndGet()
                return Result.failure(IOException("write failed"))
            }
            override fun incomingFrames() = MutableSharedFlow<ProtocolFrame>()
            override fun close() = Unit
        }
        ProtocolSessionClient(transport, scope = this).use { client ->
            assertTrue(client.write("session-1", byteArrayOf(1)).isFailure)
            assertEquals(SessionConnectionState.DISCONNECTED, client.connectionState.value)
            assertTrue(client.write("session-1", byteArrayOf(1)).isFailure)
            assertEquals(1, calls.get())
            assertTrue(isActive)
        }
    }

    @Test fun oneResponseDeadlineFailsAllPendingCallsAndStopsTheReader() = runBlocking {
        val transport = ClosingTransport()
        val diagnostics = mutableListOf<String>()
        ProtocolSessionClient(transport, requestTimeoutMillis = 100, scope = this,
            onDiagnostic = { event, _ -> diagnostics += event }).use { client ->
            val first = async { client.list() }
            val second = async { client.write("session-1", byteArrayOf(1)) }
            withTimeout(1_000) {
                assertTrue(first.await().exceptionOrNull() is ProtocolRequestTimeoutException)
                assertTrue(second.await().exceptionOrNull() is ProtocolRequestTimeoutException)
            }
            assertEquals(SessionConnectionState.DISCONNECTED, client.connectionState.value)
            assertEquals(listOf("session.request.timeout"), diagnostics)
            assertTrue(isActive)
        }
    }

    @Test fun deadlineClosesAndUnblocksATransportStillSending() = runBlocking {
        val transport = BlockedTransport()
        ProtocolSessionClient(transport, requestTimeoutMillis = 100, scope = this).use { client ->
            val request = async { client.write("session-1", ByteArray(1024)) }
            yield()
            assertTrue(transport.entered.await(1, TimeUnit.SECONDS))
            withTimeout(1_000) {
                assertTrue(request.await().exceptionOrNull() is ProtocolRequestTimeoutException)
            }
            assertTrue(transport.finished.await(1, TimeUnit.SECONDS))
            assertEquals(SessionConnectionState.DISCONNECTED, client.connectionState.value)
        }
    }

    @Test fun cancellingACallClosesBeforeJoiningItsBlockedSender() = runBlocking {
        val transport = BlockedTransport()
        ProtocolSessionClient(transport, scope = this).use { client ->
            val request = async { client.list() }
            // Let the request coroutine dispatch its IO sender before waiting on the latch.
            yield()
            assertTrue(transport.entered.await(1, TimeUnit.SECONDS))
            withTimeout(1_000) { request.cancelAndJoin() }
            assertTrue(transport.finished.await(1, TimeUnit.SECONDS))
            assertEquals(SessionConnectionState.DISCONNECTED, client.connectionState.value)
            assertTrue(isActive)
        }
    }

    private class BlockedTransport : FrameTransport {
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        private val released = CountDownLatch(1)
        override suspend fun send(frame: ProtocolFrame): Result<Unit> {
            entered.countDown()
            try { released.await(); return Result.failure(IOException("closed while sending")) }
            finally { finished.countDown() }
        }
        override fun incomingFrames() = MutableSharedFlow<ProtocolFrame>()
        override fun close() { released.countDown() }
    }

    private class ClosingTransport(private val closeFailure: IOException? = null) : FrameTransport {
        val sent = Channel<ProtocolFrame>(Channel.UNLIMITED)
        val incoming = Channel<ProtocolFrame>(Channel.UNLIMITED)
        override suspend fun send(frame: ProtocolFrame): Result<Unit> {
            sent.send(frame)
            return Result.success(Unit)
        }
        override fun incomingFrames(): Flow<ProtocolFrame> = incoming.receiveAsFlow()
        override fun close() {
            incoming.close()
            sent.close()
            closeFailure?.let { throw it }
        }
    }

    private class FakeTransport : FrameTransport {
        val sent = Channel<ProtocolFrame>(Channel.UNLIMITED)
        private val incoming = MutableSharedFlow<ProtocolFrame>(extraBufferCapacity = 8)

        override suspend fun send(frame: ProtocolFrame): Result<Unit> {
            sent.send(frame)
            return Result.success(Unit)
        }

        override fun incomingFrames(): Flow<ProtocolFrame> = incoming
        fun receive(frame: ProtocolFrame) { check(incoming.tryEmit(frame)) }
        override fun close() { sent.close() }
    }

    private object EndingTransport : FrameTransport {
        override suspend fun send(frame: ProtocolFrame): Result<Unit> = Result.failure(IllegalStateException("closed"))
        override fun incomingFrames(): Flow<ProtocolFrame> = emptyFlow()
        override fun close() = Unit
    }
}
