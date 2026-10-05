package dev.terminal.protocol

import java.io.IOException
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
