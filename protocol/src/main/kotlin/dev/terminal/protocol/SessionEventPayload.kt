package dev.terminal.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Session events, including host-local replay and resize ordering metadata. */
sealed interface SessionEvent {
    /** replay is host-local metadata; it is not part of the guest wire format. */
    data class Output(val sessionId: String, val bytes: ByteArray, val replay: Boolean = false) : SessionEvent
    /** -1 means unknown loss; otherwise bytes discarded by guest or host output delivery. */
    data class OutputGap(val sessionId: String, val droppedBytes: Long) : SessionEvent
    data class Exited(val sessionId: String, val exitCode: Int) : SessionEvent
    /** Host-only ordering marker; barrier is the requesting host operation's identity. */
    data class Resized(val sessionId: String, val rows: UShort, val cols: UShort, val barrier: Any) : SessionEvent
}

object SessionEventCodec {
    private const val OUTPUT = 0x90
    private const val EXITED = 0x91
    private const val OUTPUT_GAP = 0x92

    fun encode(event: SessionEvent): ByteArray = EventWriter().apply {
        when (event) {
            is SessionEvent.Output -> {
                require(!event.replay) { "Transcript replay is a host-local event" }
                require(event.bytes.size <= Protocol.MAX_PAYLOAD_BYTES) { "Output exceeds protocol limit" }
                byte(OUTPUT); string(event.sessionId); i32(event.bytes.size); raw(event.bytes)
            }
            is SessionEvent.OutputGap -> {
                require(event.droppedBytes >= -1) { "Invalid output gap" }
                byte(OUTPUT_GAP); string(event.sessionId); i64(event.droppedBytes)
            }
            is SessionEvent.Exited -> { byte(EXITED); string(event.sessionId); i32(event.exitCode) }
            is SessionEvent.Resized -> throw IllegalArgumentException("Resize barriers are host-local events")
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
            OUTPUT_GAP -> SessionEvent.OutputGap(reader.string(), reader.i64().also { require(it >= -1) { "Invalid output gap" } })
            EXITED -> SessionEvent.Exited(reader.string(), reader.i32())
            else -> throw IllegalArgumentException("Unknown session event")
        }
        reader.requireFullyRead()
        return event
    }

    private class EventWriter {
        private val bytes = ByteArrayOutputStream()
        fun byte(value: Int) = bytes.write(value)
        fun i64(value: Long) = raw(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array())
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
        fun i64(): Long = take(8).long
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
