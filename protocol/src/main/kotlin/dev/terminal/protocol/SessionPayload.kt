package dev.terminal.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Typed session payloads. They are always sent in a [Service.SESSION] frame. */
sealed interface SessionRequest {
    data class Create(
        val shell: String,
        val cwd: String,
        val env: Map<String, String> = emptyMap(),
        val rows: UShort,
        val cols: UShort,
    ) : SessionRequest

    data object List : SessionRequest
    data class Attach(val sessionId: String) : SessionRequest
    data class Detach(val sessionId: String) : SessionRequest
    data class Resize(val sessionId: String, val rows: UShort, val cols: UShort) : SessionRequest
    data class Write(val sessionId: String, val bytes: ByteArray) : SessionRequest
    data class Terminate(val sessionId: String, val signal: Int) : SessionRequest
}

/**
 * Binary payload codec for the session service. Strings are UTF-8 and length
 * prefixed with an unsigned 16-bit integer; arbitrary terminal input has a
 * 32-bit length to preserve binary escapes and pasted data.
 */
object SessionPayloadCodec {
    private const val CREATE = 1
    private const val LIST = 2
    private const val ATTACH = 3
    private const val DETACH = 4
    private const val RESIZE = 5
    private const val WRITE = 6
    private const val TERMINATE = 7
    private const val MAX_STRING_BYTES = 4096
    private const val MAX_ENVIRONMENT_ENTRIES = 64

    fun encode(request: SessionRequest): ByteArray = Writer().apply {
        when (request) {
            is SessionRequest.Create -> {
                require(request.rows > 0u && request.cols > 0u) { "PTY dimensions must be positive" }
                require(request.env.size <= MAX_ENVIRONMENT_ENTRIES) { "Too many environment entries" }
                byte(CREATE)
                string(request.shell)
                string(request.cwd)
                u16(request.rows)
                u16(request.cols)
                u16(request.env.size.toUShort())
                request.env.toSortedMap().forEach { (key, value) ->
                    require(key.isNotBlank() && '=' !in key && '\u0000' !in key) { "Invalid environment key" }
                    string(key)
                    string(value)
                }
            }
            SessionRequest.List -> byte(LIST)
            is SessionRequest.Attach -> {
                byte(ATTACH)
                string(request.sessionId)
            }
            is SessionRequest.Detach -> {
                byte(DETACH)
                string(request.sessionId)
            }
            is SessionRequest.Resize -> {
                require(request.rows > 0u && request.cols > 0u) { "PTY dimensions must be positive" }
                byte(RESIZE)
                string(request.sessionId)
                u16(request.rows)
                u16(request.cols)
            }
            is SessionRequest.Write -> {
                require(request.bytes.size <= Protocol.MAX_PAYLOAD_BYTES) { "Input exceeds protocol limit" }
                byte(WRITE)
                string(request.sessionId)
                u32(request.bytes.size)
                raw(request.bytes)
            }
            is SessionRequest.Terminate -> {
                byte(TERMINATE)
                string(request.sessionId)
                i32(request.signal)
            }
        }
    }.toByteArray()

    fun decode(payload: ByteArray): SessionRequest {
        val reader = Reader(payload)
        val request = when (reader.byte()) {
            CREATE -> {
                val shell = reader.string()
                val cwd = reader.string()
                val rows = reader.u16()
                val cols = reader.u16()
                require(rows > 0u && cols > 0u) { "PTY dimensions must be positive" }
                val envCount = reader.u16().toInt()
                require(envCount <= MAX_ENVIRONMENT_ENTRIES) { "Too many environment entries" }
                val environment = buildMap {
                    repeat(envCount) {
                        val key = reader.string()
                        require(key.isNotBlank() && '=' !in key && '\u0000' !in key) { "Invalid environment key" }
                        require(put(key, reader.string()) == null) { "Duplicate environment key" }
                    }
                }
                SessionRequest.Create(shell, cwd, environment, rows, cols)
            }
            LIST -> SessionRequest.List
            ATTACH -> SessionRequest.Attach(reader.string())
            DETACH -> SessionRequest.Detach(reader.string())
            RESIZE -> SessionRequest.Resize(reader.string(), reader.positiveDimension(), reader.positiveDimension())
            WRITE -> {
                val id = reader.string()
                val inputSize = reader.u32()
                require(inputSize <= Protocol.MAX_PAYLOAD_BYTES) { "Input exceeds protocol limit" }
                SessionRequest.Write(id, reader.raw(inputSize))
            }
            TERMINATE -> SessionRequest.Terminate(reader.string(), reader.i32())
            else -> throw IllegalArgumentException("Unknown session request")
        }
        reader.requireFullyRead()
        return request
    }

    private class Writer {
        private val bytes = ByteArrayOutputStream()

        fun byte(value: Int) = bytes.write(value)
        fun u16(value: UShort) = raw(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort()).array())
        fun u32(value: Int) = raw(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array())
        fun i32(value: Int) = u32(value)
        fun string(value: String) {
            val encoded = value.encodeToByteArray()
            require(encoded.size <= MAX_STRING_BYTES) { "String exceeds session protocol limit" }
            u16(encoded.size.toUShort())
            raw(encoded)
        }
        fun raw(value: ByteArray) = bytes.write(value)
        fun toByteArray(): ByteArray = bytes.toByteArray()
    }

    private class Reader(payload: ByteArray) {
        private val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)

        fun byte(): Int = take(1).get().toInt() and 0xff
        fun u16(): UShort = take(2).short.toUShort()
        fun u32(): Int = take(4).int.also { require(it >= 0) { "Negative binary length" } }
        fun i32(): Int = take(4).int
        fun string(): String {
            val length = u16().toInt()
            require(length <= MAX_STRING_BYTES) { "String exceeds session protocol limit" }
            return raw(length).decodeToString(throwOnInvalidSequence = true)
        }
        fun raw(length: Int): ByteArray = ByteArray(length).also { take(length).get(it) }
        fun positiveDimension(): UShort = u16().also { require(it > 0u) { "PTY dimensions must be positive" } }
        fun requireFullyRead() = require(!buffer.hasRemaining()) { "Unexpected bytes after session request" }
        private fun take(length: Int): ByteBuffer {
            require(length >= 0 && buffer.remaining() >= length) { "Truncated session request" }
            return buffer
        }
    }
}
