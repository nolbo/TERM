package dev.terminal.protocol

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
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
