package dev.terminal.protocol

import java.util.concurrent.ConcurrentHashMap

/** Host identities survive socket replacement, but never reuse a retired guest incarnation. */
internal class SessionIdentities(private val namespace: String) {
    private val toHost = ConcurrentHashMap<String, String>()
    private val toGuest = ConcurrentHashMap<String, String>()
    private val early = ArrayDeque<SessionEvent>() // Receive-job owned, never blocks response decoding.
    private var earlyBytes = 0

    private fun guest(id: String) = toGuest[id] ?: throw ProtocolRequestException("Session identity is no longer available")
    private fun descriptor(value: SessionDescriptor): SessionDescriptor {
        val host = "$namespace:${value.createdAtMs}:${value.id}"
        toHost.put(value.id, host)?.takeIf { it != host }?.let(toGuest::remove)
        toGuest[host] = value.id
        return value.copy(id = host)
    }
    private fun host(id: String) = toHost[id] ?: throw ProtocolRequestException("Guest returned an unknown session identity")

    fun request(value: SessionRequest): SessionRequest = when (value) {
        is SessionRequest.Attach -> value.copy(sessionId = guest(value.sessionId))
        is SessionRequest.Detach -> value.copy(sessionId = guest(value.sessionId))
        is SessionRequest.Resize -> value.copy(sessionId = guest(value.sessionId))
        is SessionRequest.Write -> value.copy(sessionId = guest(value.sessionId))
        is SessionRequest.Terminate -> value.copy(sessionId = guest(value.sessionId))
        else -> value
    }

    fun response(value: SessionResponse): SessionResponse = when (value) {
        is SessionResponse.Created -> value.copy(session = descriptor(value.session))
        is SessionResponse.Listed -> value.copy(sessions = value.sessions.map(::descriptor))
        is SessionResponse.Attached -> value.copy(session = descriptor(value.session))
        is SessionResponse.Resized -> value.copy(session = descriptor(value.session))
        is SessionResponse.Detached -> value.copy(sessionId = host(value.sessionId))
        is SessionResponse.Written -> value.copy(sessionId = host(value.sessionId))
        is SessionResponse.Terminating -> value.copy(sessionId = host(value.sessionId))
        is SessionResponse.Resources -> value
    }

    private fun id(event: SessionEvent): String = when (event) {
        is SessionEvent.Output -> event.sessionId
        is SessionEvent.OutputGap -> event.sessionId
        is SessionEvent.Exited -> event.sessionId
        is SessionEvent.Resized -> event.sessionId
    }
    private fun mapped(event: SessionEvent): SessionEvent = when (event) {
        is SessionEvent.Output -> event.copy(sessionId = host(event.sessionId))
        is SessionEvent.OutputGap -> event.copy(sessionId = host(event.sessionId))
        is SessionEvent.Exited -> event.copy(sessionId = host(event.sessionId))
        is SessionEvent.Resized -> error("Resize markers are already host-local")
    }
    private fun size(event: SessionEvent) = if (event is SessionEvent.Output) event.bytes.size else 0

    fun event(event: SessionEvent): SessionEvent? {
        if (toHost.containsKey(id(event))) return mapped(event)
        check(early.size < 256 && size(event) <= 256 * 1024 - earlyBytes) { "Unidentified session output limit reached" }
        early.addLast(event); earlyBytes += size(event)
        return null
    }

    fun identifiedEvents(): List<SessionEvent> = buildList {
        val iterator = early.iterator()
        while (iterator.hasNext()) {
            val event = iterator.next()
            if (toHost.containsKey(id(event))) {
                add(mapped(event)); earlyBytes -= size(event); iterator.remove()
            }
        }
    }
}
