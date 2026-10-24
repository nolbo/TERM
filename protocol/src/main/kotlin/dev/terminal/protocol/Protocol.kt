package dev.terminal.protocol

import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Versioned, length-delimited frames carried by the host/guest transport.
 *
 * The transport is deliberately not part of this module: vsock is the
 * production transport, while tests may use an in-memory stream.
 */
object Protocol {
    const val VERSION: UShort = 1u
    const val HEADER_SIZE = 24
    const val MAX_PAYLOAD_BYTES = 1_048_576

    private const val MAGIC = 0x4450_5459 // "DPTY"

    fun encode(frame: ProtocolFrame): ByteArray {
        require(frame.payload.size <= MAX_PAYLOAD_BYTES) { "Payload exceeds protocol limit" }
        val buffer = ByteBuffer.allocate(HEADER_SIZE + frame.payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.putShort(frame.version.toShort())
        buffer.put(frame.service.code.toByte())
        buffer.put(frame.kind.code.toByte())
        buffer.putShort(frame.flags.toShort())
        buffer.putShort(0) // Reserved; must remain zero in v1.
        buffer.putLong(frame.requestId)
        buffer.putInt(frame.payload.size)
        buffer.put(frame.payload)
        return buffer.array()
    }

    fun decode(bytes: ByteArray): ProtocolFrame {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return decodeFrame(buffer) { payloadSize ->
            require(bytes.size == HEADER_SIZE + payloadSize) { "Frame length does not match header" }
            ByteArray(payloadSize).also(buffer::get)
        }
    }

    /** Owns one fresh payload array; never joins and then splits the wire frame. */
    fun read(input: DataInputStream): ProtocolFrame {
        val header = ByteArray(HEADER_SIZE).also(input::readFully)
        return decodeFrame(ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)) { payloadSize ->
            ByteArray(payloadSize).also(input::readFully)
        }
    }

    private inline fun decodeFrame(buffer: ByteBuffer, readPayload: (Int) -> ByteArray): ProtocolFrame {
        require(buffer.remaining() >= HEADER_SIZE) { "Frame is shorter than its header" }
        require(buffer.int == MAGIC) { "Invalid protocol magic" }
        val version = buffer.short.toUShort()
        require(version == VERSION) { "Unsupported protocol version: $version" }
        val service = Service.fromCode(buffer.get().toUByte())
        val kind = FrameKind.fromCode(buffer.get().toUByte())
        val flags = buffer.short.toUShort()
        require(buffer.short.toInt() == 0) { "Reserved header field must be zero" }
        val requestId = buffer.long
        val payloadSize = buffer.int
        require(payloadSize in 0..MAX_PAYLOAD_BYTES) { "Invalid payload size: $payloadSize" }
        val payload = readPayload(payloadSize)
        return ProtocolFrame(version, service, kind, flags, requestId, payload)
    }
}

data class ProtocolFrame(
    val version: UShort = Protocol.VERSION,
    val service: Service,
    val kind: FrameKind,
    val flags: UShort = 0u,
    val requestId: Long,
    val payload: ByteArray = ByteArray(0),
)

enum class Service(val code: UByte) {
    SESSION(1u),
    LIFECYCLE(2u),
    BRIDGE(3u),
    PORT(4u);

    companion object {
        fun fromCode(code: UByte) = entries.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("Unknown service: $code")
    }
}

enum class FrameKind(val code: UByte) {
    REQUEST(1u),
    RESPONSE(2u),
    STREAM(3u),
    CANCEL(4u),
    ERROR(5u);

    companion object {
        fun fromCode(code: UByte) = entries.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("Unknown frame kind: $code")
    }
}

enum class ProtocolErrorCode {
    UNSUPPORTED,
    PERMISSION_DENIED,
    CAPABILITY_DENIED,
    NOT_FOUND,
    INVALID_ARGUMENT,
    TIMEOUT,
    DISCONNECTED,
    GUEST_ERROR,
    HOST_ERROR,
}

data class ProtocolErrorPayload(
    val code: ProtocolErrorCode,
    val message: String,
)

object ProtocolErrorCodec {
    fun encode(error: ProtocolErrorPayload): ByteArray {
        val message = error.message.encodeToByteArray()
        require(message.size <= 4096) { "Error message exceeds protocol limit" }
        return ByteBuffer.allocate(3 + message.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(error.code.ordinal.toByte())
            putShort(message.size.toShort())
            put(message)
        }.array()
    }

    fun decode(payload: ByteArray): ProtocolErrorPayload {
        require(payload.size >= 3) { "Truncated error payload" }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val code = ProtocolErrorCode.entries.getOrNull(buffer.get().toInt() and 0xff)
            ?: throw IllegalArgumentException("Unknown protocol error code")
        val length = buffer.short.toInt() and 0xffff
        require(length <= 4096 && buffer.remaining() == length) { "Invalid error payload length" }
        val message = ByteArray(length).also(buffer::get).decodeToString(throwOnInvalidSequence = true)
        return ProtocolErrorPayload(code, message)
    }
}
