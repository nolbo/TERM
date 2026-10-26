package dev.terminal.runtime.session

import dev.terminal.protocol.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class TerminalQueryReplyTest {
    private class Guest(private val prefix: String = "avf") : FrameTransport {
        val incoming = Channel<ProtocolFrame>(Channel.UNLIMITED)
        val writes = Channel<ProtocolFrame>(Channel.UNLIMITED)
        var burstBeforeReply = false
        override suspend fun send(frame: ProtocolFrame): Result<Unit> = runCatching {
            when (val request = SessionPayloadCodec.decode(frame.payload)) {
                SessionRequest.List -> respond(frame, SessionResponse.Listed(listOf(descriptor("$prefix-query"), descriptor("$prefix-sibling"))))
                is SessionRequest.Write -> {
                    writes.send(frame)
                    if (burstBeforeReply) {
                        // These frames precede the WRITE acknowledgement on the same
                        // socket, exceeding ProtocolSessionClient's event buffer.
                        repeat(96) { output(request.sessionId, "burst-$it\r\n") }
                        acknowledge(frame)
                        output(request.sessionId, "WORK_FINISHED")
                    }
                }
                else -> error("Unexpected request")
            }
        }
        suspend fun output(id: String, bytes: String) {
            incoming.send(ProtocolFrame(service = Service.SESSION, kind = FrameKind.STREAM, requestId = 0,
                payload = SessionEventCodec.encode(SessionEvent.Output(id, bytes.toByteArray()))))
        }
        suspend fun acknowledge(frame: ProtocolFrame) {
            val request = SessionPayloadCodec.decode(frame.payload) as SessionRequest.Write
            respond(frame, SessionResponse.Written(request.sessionId, request.bytes.size))
        }
        private suspend fun respond(frame: ProtocolFrame, response: SessionResponse) {
            incoming.send(frame.copy(kind = FrameKind.RESPONSE, payload = SessionResponseCodec.encode(response)))
        }
        override fun incomingFrames() = incoming.receiveAsFlow()
        override fun close() { incoming.close() }
        private fun descriptor(id: String) = SessionDescriptor(id, id, "/bin/sh", "/", 0, 24u, 80u, SessionStatus.RUNNING)
    }

    @Test fun parsedActivityPrecedesOutputWithoutASecondObserverOfTheByteStream() = runBlocking {
        val events = mutableListOf<List<String>>()
        val guest = Guest()
        val client = ProtocolSessionClient(guest)
        val registry = UnifiedSessionRegistry(onOutputActivity = { _, parsed -> synchronized(events) { events.add(parsed) } })
        try { withTimeout(3000) {
            registry.register(SessionRuntime.AVF, client)
            registry.list().getOrThrow()
            registry.terminalStates.withSession("avf-query") { registry.terminalStates.create("avf-query", 80, 24) }
            val finished = async(start = CoroutineStart.UNDISPATCHED) {
                registry.events.first { it is SessionEvent.Output && it.bytes.decodeToString().endsWith("DONE") }
            }
            guest.output("avf-query", "plain")
            guest.output("avf-query", "\u001b]133;C\u0007work\u001b]133;D;0\u0007DONE")
            finished.await()
            assertEquals(listOf(emptyList<String>(), listOf("133;C", "133;D;0")), synchronized(events) { events.toList() })
        } } finally { registry.close() }
    }

    @Test fun detachedAvfQueryReplyDrainsOutputBeforeItsWriteAcknowledgement() = withGuest { guest, client, registry ->
        guest.burstBeforeReply = true
        // No UI sink is attached: the runtime alone must answer the TUI.
        val finished = async(start = CoroutineStart.UNDISPATCHED) {
            registry.events.first { it is SessionEvent.Output && it.bytes.decodeToString() == "WORK_FINISHED" }
        }
        guest.output("avf-query", "\u001b[6n")
        val request = SessionPayloadCodec.decode(guest.writes.receive().payload) as SessionRequest.Write
        assertEquals("\u001b[1;1R", request.bytes.decodeToString())
        finished.await()
        assertEquals(SessionConnectionState.CONNECTED, client.connectionState.value)
    }

    @Test fun delayedAcknowledgementKeepsParsingAndPreservesReplyOrderWithoutUserActivity() = withGuest { guest, _, registry ->
        guest.output("avf-query", "\u001b[6n")
        val first = guest.writes.receive()
        val progress = async(start = CoroutineStart.UNDISPATCHED) {
            registry.events.first { it is SessionEvent.Output && it.sessionId == "avf-sibling" }
        }
        guest.output("avf-query", "\u001b[5nBACKGROUND_PROGRESS")
        guest.output("avf-sibling", "SIBLING_PROGRESS")
        progress.await()
        assertTrue("Replies on one controller remain FIFO", guest.writes.tryReceive().isFailure)
        guest.acknowledge(first)
        val second = guest.writes.receive()
        val request = SessionPayloadCodec.decode(second.payload) as SessionRequest.Write
        assertEquals("\u001b[0n", request.bytes.decodeToString())
        guest.acknowledge(second)
    }

    @Test fun controllerReplacementDiscardsQueuedRepliesFromThePreviousConnection() = withGuest { guest, _, registry ->
        guest.output("avf-query", "\u001b[6n")
        guest.writes.receive() // Leave this WRITE pending while another reply queues.
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            registry.events.first { it is SessionEvent.Output && it.bytes.decodeToString().contains("QUEUED") }
        }
        guest.output("avf-query", "\u001b[5nQUEUED")
        queued.await()

        val replacement = Guest().apply { burstBeforeReply = true }
        val client = ProtocolSessionClient(replacement, scope = CoroutineScope(coroutineContext + SupervisorJob()))
        registry.register(SessionRuntime.AVF, client)
        registry.list().getOrThrow()
        val finished = async(start = CoroutineStart.UNDISPATCHED) {
            registry.events.first { it is SessionEvent.Output && it.bytes.decodeToString() == "WORK_FINISHED" }
        }
        replacement.output("avf-query", "\u001b[6n")
        val request = SessionPayloadCodec.decode(replacement.writes.receive().payload) as SessionRequest.Write
        assertEquals("\u001b[1;7R", request.bytes.decodeToString()) // Cursor after QUEUED.
        finished.await()
        assertTrue(guest.writes.tryReceive().isFailure)
        assertTrue(replacement.writes.tryReceive().isFailure)
        assertEquals(SessionConnectionState.CONNECTED, client.connectionState.value)
    }

    @Test fun saturatedReplyQueueDisconnectsOnlyItsAvfTransportAndRetainsTerminalState() = withGuest { guest, client, registry ->
        val siblingGuest = Guest("avf-other").apply { burstBeforeReply = true }
        val sibling = ProtocolSessionClient(siblingGuest, scope = CoroutineScope(coroutineContext + SupervisorJob()))
        val siblingEnvironment = Environment.create(SessionRuntime.AVF, "Other VM")
        registry.register(siblingEnvironment, sibling)
        registry.list().getOrThrow()
        registry.terminalStates.withSession("avf-other-query") { registry.terminalStates.create("avf-other-query", 80, 24) }
        guest.output("avf-query", "\u001b[6n")
        guest.writes.receive()
        repeat(256) { guest.output("avf-query", "\u001b[5n") }
        client.connectionState.first { it == SessionConnectionState.DISCONNECTED }
        val finished = async(start = CoroutineStart.UNDISPATCHED) {
            registry.events.first { it is SessionEvent.Output && it.sessionId == "avf-other-query" &&
                it.bytes.decodeToString() == "WORK_FINISHED" }
        }
        siblingGuest.output("avf-other-query", "\u001b[6n")
        finished.await()
        assertEquals(SessionConnectionState.CONNECTED, sibling.connectionState.value)
        assertTrue(registry.hasSessionOwner("avf-query"))
        assertTrue(registry.terminalStates.withSession("avf-query") { registry.terminalStates.sameDimensions("avf-query", 80, 24) })
    }

    private fun withGuest(test: suspend CoroutineScope.(Guest, ProtocolSessionClient, UnifiedSessionRegistry) -> Unit) = runBlocking {
        val guest = Guest()
        val client = ProtocolSessionClient(guest, requestTimeoutMillis = 1_000,
            scope = CoroutineScope(coroutineContext + SupervisorJob()))
        var userInputs = 0
        val registry = UnifiedSessionRegistry(scope = CoroutineScope(coroutineContext + SupervisorJob()),
            onInput = { _, _ -> userInputs++ })
        try { withTimeout(3_000) {
            registry.register(SessionRuntime.AVF, client)
            registry.list().getOrThrow()
            listOf("avf-query", "avf-sibling").forEach { id -> registry.terminalStates.withSession(id) { registry.terminalStates.create(id, 80, 24) } }
            test(guest, client, registry)
            assertEquals("Terminal replies must not renew shell-activity leases", 0, userInputs)
        } } finally { registry.close() }
    }
}
