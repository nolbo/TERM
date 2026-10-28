package dev.terminal.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.*
import org.junit.Test

class SessionIdentitiesTest {
    private fun descriptor(time: Long) = SessionDescriptor("session-1", "Shell", "/bin/sh", "/", time, 24u, 80u, SessionStatus.RUNNING)

    @Test fun environmentAndCreationIdentityFenceReusedGuestCounters() {
        val first = SessionIdentities("avf-first-boot")
        val other = SessionIdentities("avf-second-boot")
        fun id(map: SessionIdentities, time: Long) = (map.response(SessionResponse.Created(descriptor(time))) as SessionResponse.Created).session.id
        val old = id(first, 100)
        assertNotEquals(old, id(other, 100))
        assertEquals(old, id(SessionIdentities("avf-first-boot"), 100)) // Socket replacement.
        val replacement = id(first, 200) // Session daemon restarted and reused session-1.
        assertNotEquals(old, replacement)
        assertThrows(ProtocolRequestException::class.java) { first.request(SessionRequest.Write(old, byteArrayOf(1))) }
        assertEquals("session-1", (first.request(SessionRequest.Write(replacement, byteArrayOf(1))) as SessionRequest.Write).sessionId)
    }

    @Test fun outputBeforeListWaitsForIdentityWithoutBlockingTheListResponse() = runBlocking {
        val incoming = Channel<ProtocolFrame>(Channel.UNLIMITED)
        val sent = Channel<ProtocolFrame>(Channel.UNLIMITED)
        val transport = object : FrameTransport {
            override suspend fun send(frame: ProtocolFrame): Result<Unit> { sent.send(frame); return Result.success(Unit) }
            override fun incomingFrames() = incoming.receiveAsFlow()
            override fun close() { incoming.close() }
        }
        val outputs = Channel<SessionEvent>(Channel.UNLIMITED)
        ProtocolSessionClient(transport, scope = this, sessionNamespace = "avf-test-boot").use { client ->
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { client.events.collect { outputs.send(it) } }
            try { withTimeout(2_000) {
                val list = async { client.list().getOrThrow() }
                val request = sent.receive()
                incoming.send(ProtocolFrame(service = Service.SESSION, kind = FrameKind.STREAM, requestId = 0,
                    payload = SessionEventCodec.encode(SessionEvent.Output("session-1", "BEFORE_LIST".toByteArray()))))
                incoming.send(request.copy(kind = FrameKind.RESPONSE,
                    payload = SessionResponseCodec.encode(SessionResponse.Listed(listOf(descriptor(100))))))
                val session = list.await().single()
                val output = outputs.receive() as SessionEvent.Output
                assertEquals(session.id, output.sessionId)
                assertEquals("BEFORE_LIST", output.bytes.decodeToString())
                val write = async { client.write(session.id, byteArrayOf(1)).getOrThrow() }
                val wire = sent.receive()
                assertEquals("session-1", (SessionPayloadCodec.decode(wire.payload) as SessionRequest.Write).sessionId)
                incoming.send(wire.copy(kind = FrameKind.RESPONSE,
                    payload = SessionResponseCodec.encode(SessionResponse.Written("session-1", 1))))
                assertEquals(1, write.await())
            } } finally { collector.cancelAndJoin() }
        }
    }
}
