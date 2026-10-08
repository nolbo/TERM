package dev.terminal.runtime.image

import dev.terminal.protocol.FrameKind
import dev.terminal.protocol.GuestRequestException
import dev.terminal.protocol.ProtocolErrorCodec
import dev.terminal.protocol.ProtocolFrame
import dev.terminal.protocol.Service
import dev.terminal.runtime.api.GuestConnection
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Uses a dedicated maintenance connection, never a terminal or capability RPC. */
internal object AvfAptInitializer {
    suspend fun initialize(connection: GuestConnection, timeoutMillis: Long = 360_000): Long =
        withTimeout(timeoutMillis) {
            coroutineScope {
                // Closing the descriptor on cancellation unblocks the IO flow producer.
                val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { runCatching { connection.close() } }
                }
                try {
                    connection.send(ProtocolFrame(service = Service.LIFECYCLE, kind = FrameKind.REQUEST,
                        requestId = 1, payload = byteArrayOf(0x40))).getOrThrow()
                    val response = connection.incomingFrames().first { frame ->
                        require(frame.service == Service.LIFECYCLE && frame.requestId == 1L) { "Invalid APT initialization response" }
                        frame.kind != FrameKind.STREAM
                    }
                    if (response.kind == FrameKind.ERROR) throw GuestRequestException(ProtocolErrorCodec.decode(response.payload))
                    require(response.kind == FrameKind.RESPONSE && response.payload.size == 8) { "Invalid APT initialization result" }
                    ByteBuffer.wrap(response.payload).long.also { require(it > 0) { "Invalid APT refresh timestamp" } }
                } finally {
                    runCatching { connection.close() }
                    closer.cancel()
                }
            }
        }
}
