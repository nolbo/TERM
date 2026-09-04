package dev.terminal.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed interface PortEvent {
    data class Listening(val port: Int, val label: String? = null) : PortEvent
    data class Closed(val port: Int) : PortEvent
}

object PortPayloadCodec {
    private const val LISTENING: Byte = 1
    private const val CLOSED: Byte = 2
    private const val MAX_LABEL_BYTES = 128

    fun encode(event: PortEvent): ByteArray = when (event) {
        is PortEvent.Listening -> {
            val label = event.label?.encodeToByteArray() ?: ByteArray(0)
            require(event.port in 1..65_535 && label.size <= MAX_LABEL_BYTES)
            ByteBuffer.allocate(5 + label.size).order(ByteOrder.BIG_ENDIAN).apply {
                put(LISTENING); putShort(event.port.toShort()); putShort(label.size.toShort()); put(label)
            }.array()
        }
        is PortEvent.Closed -> {
            require(event.port in 1..65_535)
            ByteBuffer.allocate(3).order(ByteOrder.BIG_ENDIAN).put(CLOSED).putShort(event.port.toShort()).array()
        }
    }

    fun decode(payload: ByteArray): PortEvent {
        require(payload.isNotEmpty()) { "Missing port event" }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        return when (buffer.get()) {
            LISTENING -> {
                require(buffer.remaining() >= 4) { "Truncated listening event" }
                val port = buffer.short.toInt() and 0xffff
                val size = buffer.short.toInt() and 0xffff
                require(port > 0 && size <= MAX_LABEL_BYTES && buffer.remaining() == size) { "Invalid listening event" }
                PortEvent.Listening(port, ByteArray(size).also(buffer::get).decodeToString(throwOnInvalidSequence = true).ifBlank { null })
            }
            CLOSED -> {
                require(buffer.remaining() == 2) { "Invalid closed event" }
                PortEvent.Closed(buffer.short.toInt() and 0xffff)
            }
            else -> throw IllegalArgumentException("Unknown port event")
        }
    }
}
