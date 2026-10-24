package dev.terminal.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class OrderedResizeTest {
    private class Transport : FrameTransport {
        val sent = Channel<ProtocolFrame>(Channel.UNLIMITED)
        val incoming = Channel<ProtocolFrame>(Channel.UNLIMITED)
        val sentRelease = CompletableDeferred<Unit>()
        override suspend fun send(frame: ProtocolFrame): Result<Unit> {
            sent.send(frame); sentRelease.await(); return Result.success(Unit)
        }
        override fun incomingFrames() = incoming.receiveAsFlow()
        override fun close() { incoming.close(); sentRelease.complete(Unit) }
        suspend fun output(text: String) = incoming.send(ProtocolFrame(service = Service.SESSION,
            kind = FrameKind.STREAM, requestId = 0,
            payload = SessionEventCodec.encode(SessionEvent.Output("id", text.toByteArray()))))
        suspend fun response(request: ProtocolFrame, descriptor: SessionDescriptor) = incoming.send(
            ProtocolFrame(service = Service.SESSION, kind = FrameKind.RESPONSE, requestId = request.requestId,
                payload = SessionResponseCodec.encode(SessionResponse.Resized(descriptor))))
    }
    private fun descriptor(id: String = "id", rows: UShort = 30u, cols: UShort = 40u) =
        SessionDescriptor(id, id, "/bin/sh", "/", 1, rows, cols, SessionStatus.RUNNING)

    @Test fun resizeMarkerUsesResponseStreamPositionEvenBeforeRequestContinuationResumes() = runBlocking {
        withTimeout(5000) {
            val transport = Transport()
            ProtocolSessionClient(transport, scope = this).use { client ->
                val events = Channel<SessionEvent>(Channel.UNLIMITED)
                val collector = launch(start = CoroutineStart.UNDISPATCHED) { client.events.collect { events.send(it) } }
                try {
                    val barrier = Any()
                    val resize = async { client.resizeOrdered("id", 30u, 40u, barrier) }
                    val request = transport.sent.receive()
                    transport.output("before"); transport.response(request, descriptor()); transport.output("after")
                    assertEquals("before", (events.receive() as SessionEvent.Output).bytes.decodeToString())
                    val marker = events.receive() as SessionEvent.Resized
                    assertSame(barrier, marker.barrier); assertEquals(40.toUShort(), marker.cols)
                    assertEquals("after", (events.receive() as SessionEvent.Output).bytes.decodeToString())
                    assertFalse("Sender continuation is deliberately delayed", resize.isCompleted)
                    transport.sentRelease.complete(Unit); resize.await().getOrThrow(); Unit
                } finally { collector.cancelAndJoin() }
            }
        }
    }

    @Test fun invalidResponsesNeverPublishResizeMarkersAndOrdinaryResizeStaysEventless() = runBlocking {
        withTimeout(5000) {
            for (invalid in listOf(descriptor(id = "wrong"), descriptor(cols = 41u))) {
                val transport = Transport(); transport.sentRelease.complete(Unit)
                ProtocolSessionClient(transport, scope = this).use { client ->
                    val events = Channel<SessionEvent>(Channel.UNLIMITED)
                    val collector = launch(start = CoroutineStart.UNDISPATCHED) { client.events.collect { events.send(it) } }
                    try {
                        val resize = async { client.resizeOrdered("id", 30u, 40u, Any()) }
                        transport.response(transport.sent.receive(), invalid)
                        assertTrue(resize.await().isFailure)
                        transport.output("alive")
                        assertTrue(events.receive() is SessionEvent.Output)
                        val plain = async { client.resize("id", 30u, 40u) }
                        transport.response(transport.sent.receive(), descriptor()); plain.await().getOrThrow()
                        transport.output("plain")
                        assertTrue(events.receive() is SessionEvent.Output)
                    } finally { collector.cancelAndJoin() }
                }
            }
        }
    }

    @Test fun resizeMarkerCannotBeEncodedOnGuestWire() {
        assertTrue(runCatching { SessionEventCodec.encode(SessionEvent.Resized("id", 30u, 40u, Any())) }.isFailure)
    }
}
