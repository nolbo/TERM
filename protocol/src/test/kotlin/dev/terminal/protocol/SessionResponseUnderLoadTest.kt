package dev.terminal.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class SessionResponseUnderLoadTest {
    private class Transport : FrameTransport {
        val sent = Channel<ProtocolFrame>(Channel.UNLIMITED)
        val incoming = Channel<ProtocolFrame>(Channel.UNLIMITED)
        override suspend fun send(frame: ProtocolFrame): Result<Unit> { sent.send(frame); return Result.success(Unit) }
        override fun incomingFrames() = incoming.receiveAsFlow()
        override fun close() { incoming.close(); sent.close() }
    }

    private fun stalledConsumer(resize: Boolean) = runBlocking {
        withTimeout(5_000) {
            val transport = Transport()
            val stalled = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val observed = mutableListOf<SessionEvent>()
            ProtocolSessionClient(transport, requestTimeoutMillis = 500, scope = this).use { client ->
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    client.events.collect {
                        observed += it
                        stalled.complete(Unit)
                        release.await()
                        if (it is SessionEvent.Exited) finished.complete(Unit)
                    }
                }
                fun event(value: SessionEvent) = ProtocolFrame(service = Service.SESSION, kind = FrameKind.STREAM,
                    requestId = 0, payload = SessionEventCodec.encode(value))
                val output = event(SessionEvent.Output("busy-session", byteArrayOf(65)))
                transport.incoming.send(output)
                stalled.await()
                repeat(1024) { transport.incoming.send(output) }
                val barrier = Any()
                val request = async {
                    if (resize) client.resizeOrdered("busy-session", 40u, 120u, barrier).map { Unit }
                    else client.terminate("other-session", 9)
                }
                val sent = transport.sent.receive()
                val response = if (resize) SessionResponse.Resized(SessionDescriptor(
                    "busy-session", "Shell", "/bin/sh", "/", 0, 40u, 120u, SessionStatus.RUNNING))
                    else SessionResponse.Terminating("other-session")
                transport.incoming.send(sent.copy(kind = FrameKind.RESPONSE, payload = SessionResponseCodec.encode(response)))
                transport.incoming.send(event(SessionEvent.Output("busy-session", "AFTER".toByteArray())))
                transport.incoming.send(event(SessionEvent.Exited("busy-session", 0)))
                request.await().getOrThrow() // Must finish while the output consumer remains blocked.
                assertEquals(SessionConnectionState.CONNECTED, client.connectionState.value)
                release.complete(Unit)
                finished.await()
                assertTrue(observed.any { it is SessionEvent.OutputGap && it.droppedBytes > 0 })
                val after = observed.indexOfFirst { it is SessionEvent.Output && it.bytes.contentEquals("AFTER".toByteArray()) }
                assertTrue(after > 0)
                if (resize) {
                    val boundary = observed.indexOfFirst { it is SessionEvent.Resized && it.barrier === barrier }
                    assertTrue(boundary in 1 until after)
                    assertTrue(observed.take(boundary).any { it is SessionEvent.OutputGap })
                }
                assertTrue(observed.last() is SessionEvent.Exited)
                collector.cancelAndJoin()
            }
        }
    }

    @Test fun outputStallDoesNotDelayAnAvailableTerminateResponse() = stalledConsumer(false)
    @Test fun outputStallDoesNotDisconnectAnAcknowledgedResize() = stalledConsumer(true)
}
