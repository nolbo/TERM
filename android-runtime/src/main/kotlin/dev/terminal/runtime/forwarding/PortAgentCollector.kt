package dev.terminal.runtime.forwarding

import dev.terminal.protocol.FrameKind
import dev.terminal.protocol.FrameTransport
import dev.terminal.protocol.PortEvent
import dev.terminal.protocol.PortPayloadCodec
import dev.terminal.protocol.Service
import kotlinx.coroutines.flow.collect

/** Accepts validated reports only; forwarding sockets are deliberately a separate layer. */
class PortAgentCollector(
    private val registry: PortForwardRegistry,
    private val onListening: (Int) -> Unit = {},
    private val onClosed: (Int) -> Unit = {},
) {
    suspend fun collect(transport: FrameTransport) {
        transport.incomingFrames().collect { frame ->
            if (frame.service != Service.PORT || frame.kind != FrameKind.STREAM) return@collect
            when (val event = runCatching { PortPayloadCodec.decode(frame.payload) }.getOrNull() ?: return@collect) {
                is PortEvent.Listening -> registry.reportListening(event.port, event.label).onSuccess { onListening(event.port) }
                is PortEvent.Closed -> { registry.remove(event.port); onClosed(event.port) }
            }
        }
    }
}
