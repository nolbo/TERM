package dev.terminal.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SessionDescriptor(
    val id: String,
    val title: String,
    val command: String,
    val cwd: String,
    val createdAtMs: Long,
    val rows: UShort,
    val cols: UShort,
    val state: SessionStatus,
    val exitCode: Int = NO_EXIT_CODE,
) {
    companion object { const val NO_EXIT_CODE = -1 }
}

enum class SessionStatus {
    CREATED, RUNNING, EXITED,
    /** Host-retained history after proven owner loss; never a guest wire state. */
    INTERRUPTED;
    val isLive: Boolean get() = this == CREATED || this == RUNNING
}

sealed interface SessionResponse {
    data class Resources(val usage: GuestResourceUsage) : SessionResponse
    data class Created(val session: SessionDescriptor) : SessionResponse
    data class Listed(val sessions: List<SessionDescriptor>) : SessionResponse
    data class Attached(val session: SessionDescriptor) : SessionResponse
    data class Detached(val sessionId: String) : SessionResponse
    data class Resized(val session: SessionDescriptor) : SessionResponse
    data class Written(val sessionId: String, val acceptedBytes: Int) : SessionResponse
    data class Terminating(val sessionId: String) : SessionResponse
}

object SessionResponseCodec {
    private const val CREATED = 0x81
    private const val LISTED = 0x82
    private const val ATTACHED = 0x83
    private const val DETACHED = 0x84
    private const val RESIZED = 0x85
    private const val WRITTEN = 0x86
    private const val TERMINATING = 0x87

    fun encode(response: SessionResponse): ByteArray = ResponseWriter().apply {
        when (response) {
            is SessionResponse.Resources -> {
                byte(0x88)
                with(response.usage) { i64(totalBytes); i64(availableBytes); i64(swapTotalBytes); i64(swapFreeBytes)
                    i32(cpuCount); i32(loadMilli); i32(memoryPressureMilli) }
            }
            is SessionResponse.Created -> { byte(CREATED); descriptor(response.session) }
            is SessionResponse.Listed -> {
                require(response.sessions.size <= 1024) { "Too many sessions" }
                byte(LISTED); u16(response.sessions.size.toUShort())
                response.sessions.forEach(::descriptor)
            }
            is SessionResponse.Attached -> { byte(ATTACHED); descriptor(response.session) }
            is SessionResponse.Detached -> { byte(DETACHED); string(response.sessionId) }
            is SessionResponse.Resized -> { byte(RESIZED); descriptor(response.session) }
            is SessionResponse.Written -> { byte(WRITTEN); string(response.sessionId); i32(response.acceptedBytes) }
            is SessionResponse.Terminating -> { byte(TERMINATING); string(response.sessionId) }
        }
    }.toByteArray()

    fun decode(payload: ByteArray): SessionResponse {
        val reader = ResponseReader(payload)
        val response = when (reader.byte()) {
            0x88 -> SessionResponse.Resources(GuestResourceUsage(reader.i64(), reader.i64(), reader.i64(), reader.i64(),
                reader.i32(), reader.i32(), reader.i32()))
            CREATED -> SessionResponse.Created(reader.descriptor())
            LISTED -> SessionResponse.Listed(List(reader.u16().toInt()) { reader.descriptor() })
            ATTACHED -> SessionResponse.Attached(reader.descriptor())
            DETACHED -> SessionResponse.Detached(reader.string())
            RESIZED -> SessionResponse.Resized(reader.descriptor())
            WRITTEN -> SessionResponse.Written(reader.string(), reader.i32())
            TERMINATING -> SessionResponse.Terminating(reader.string())
            else -> throw IllegalArgumentException("Unknown session response")
        }
        reader.requireFullyRead()
        return response
    }

    private class ResponseWriter {
        private val bytes = ByteArrayOutputStream()
        fun byte(value: Int) = bytes.write(value)
        fun u16(value: UShort) = raw(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort()).array())
        fun i32(value: Int) = raw(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array())
        fun i64(value: Long) = raw(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array())
        fun string(value: String) {
            val encoded = value.encodeToByteArray()
            require(encoded.size <= 4096) { "String exceeds session protocol limit" }
            u16(encoded.size.toUShort()); raw(encoded)
        }
        fun descriptor(value: SessionDescriptor) {
            require(value.state != SessionStatus.INTERRUPTED) { "Retained history is not a guest session" }
            string(value.id); string(value.title); string(value.command); string(value.cwd)
            i64(value.createdAtMs); u16(value.rows); u16(value.cols); byte(value.state.ordinal); i32(value.exitCode)
        }
        fun raw(value: ByteArray) = bytes.write(value)
        fun toByteArray(): ByteArray = bytes.toByteArray()
    }

    private class ResponseReader(payload: ByteArray) {
        private val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        fun byte(): Int = take(1).get().toInt() and 0xff
        fun u16(): UShort = take(2).short.toUShort()
        fun i32(): Int = take(4).int
        fun i64(): Long = take(8).long
        fun string(): String {
            val length = u16().toInt(); require(length <= 4096) { "String exceeds session protocol limit" }
            return ByteArray(length).also { take(length).get(it) }.decodeToString(throwOnInvalidSequence = true)
        }
        fun descriptor(): SessionDescriptor = SessionDescriptor(
            id = string(), title = string(), command = string(), cwd = string(), createdAtMs = i64(),
            rows = u16(), cols = u16(),
            state = SessionStatus.entries.take(3).getOrNull(byte()) ?: throw IllegalArgumentException("Unknown guest session state"),
            exitCode = i32(),
        )
        fun requireFullyRead() = require(!buffer.hasRemaining()) { "Unexpected bytes after session response" }
        private fun take(length: Int): ByteBuffer {
            require(length >= 0 && buffer.remaining() >= length) { "Truncated session response" }; return buffer
        }
    }
}
