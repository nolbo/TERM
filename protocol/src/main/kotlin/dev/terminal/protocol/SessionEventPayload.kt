package dev.terminal.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Events carried in `FrameKind.STREAM` frames after a session is attached. */
sealed interface SessionEvent {
    data class Output(val sessionId: String, val bytes: ByteArray) : SessionEvent
    data class Exited(val sessionId: String, val exitCode: Int) : SessionEvent
}

object SessionEventCodec {
    private const val OUTPUT = 0x90
    private const val EXITED = 0x91

    fun encode(event: SessionEvent): ByteArray = EventWriter().apply {
        when (event) {
            is SessionEvent.Output -> {
                require(event.bytes.size <= Protocol.MAX_PAYLOAD_BYTES) { "Output exceeds protocol limit" }
                byte(OUTPUT); string(event.sessionId); i32(event.bytes.size); raw(event.bytes)
            }
            is SessionEvent.Exited -> { byte(EXITED); string(event.sessionId); i32(event.exitCode) }
        }
    }.toByteArray()

    fun decode(payload: ByteArray): SessionEvent {
        val reader = EventReader(payload)
        val event = when (reader.byte()) {
            OUTPUT -> {
                val id = reader.string(); val size = reader.i32()
                require(size >= 0 && size <= Protocol.MAX_PAYLOAD_BYTES) { "Invalid output length" }
                SessionEvent.Output(id, reader.raw(size))
            }
            EXITED -> SessionEvent.Exited(reader.string(), reader.i32())
            else -> throw IllegalArgumentException("Unknown session event")
        }
        reader.requireFullyRead()
        return event
    }

    private class EventWriter {
        private val bytes = ByteArrayOutputStream()
        fun byte(value: Int) = bytes.write(value)
        fun i32(value: Int) = raw(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array())
        fun string(value: String) {
            val encoded = value.encodeToByteArray(); require(encoded.size <= 4096) { "Session ID exceeds protocol limit" }
            raw(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(encoded.size.toShort()).array()); raw(encoded)
        }
        fun raw(value: ByteArray) = bytes.write(value)
        fun toByteArray(): ByteArray = bytes.toByteArray()
    }

    private class EventReader(payload: ByteArray) {
        private val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        fun byte(): Int = take(1).get().toInt() and 0xff
        fun i32(): Int = take(4).int
        fun string(): String {
            val length = take(2).short.toInt() and 0xffff; require(length <= 4096) { "Session ID exceeds protocol limit" }
            return raw(length).decodeToString(throwOnInvalidSequence = true)
        }
        fun raw(length: Int): ByteArray = ByteArray(length).also { take(length).get(it) }
        fun requireFullyRead() = require(!buffer.hasRemaining()) { "Unexpected bytes after session event" }
        private fun take(length: Int): ByteBuffer {
            require(length >= 0 && buffer.remaining() >= length) { "Truncated session event" }; return buffer
        }
    }
}
