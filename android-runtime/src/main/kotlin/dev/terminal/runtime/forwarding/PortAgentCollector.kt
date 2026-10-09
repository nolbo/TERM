package dev.terminal.runtime.forwarding

import dev.terminal.protocol.FrameKind
import dev.terminal.protocol.FrameTransport
import dev.terminal.protocol.PortEvent
import dev.terminal.protocol.PortPayloadCodec
import dev.terminal.protocol.Service
import kotlinx.coroutines.flow.collect

/** Decodes bounded events; the caller supplies the authenticated Environment, never the guest. */
class PortAgentCollector(private val onEvent: (PortEvent) -> Unit) {
    suspend fun collect(transport: FrameTransport) {
        transport.incomingFrames().collect { frame ->
            if (frame.service != Service.PORT || frame.kind != FrameKind.STREAM || frame.payload.size > 180) return@collect
            val event = runCatching { PortPayloadCodec.decode(frame.payload) }.getOrNull() ?: return@collect
            onEvent(event)
        }
    }
}
