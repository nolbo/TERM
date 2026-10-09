package dev.terminal.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed interface PortEvent {
    data class Listening(val port: Int, val label: String? = null) : PortEvent
    data class Closed(val port: Int) : PortEvent
    /** Optional v1 extension. Legacy events retain their exact encoding. */
    data class EndpointListening(val port: Int, val address: String, val label: String? = null) : PortEvent
    data class EndpointClosed(val port: Int, val address: String) : PortEvent
    data object SnapshotBegin : PortEvent
    data object SnapshotEnd : PortEvent
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
        is PortEvent.EndpointListening -> endpoint(3, event.port, event.address, event.label)
        is PortEvent.EndpointClosed -> endpoint(4, event.port, event.address, null)
        PortEvent.SnapshotBegin -> byteArrayOf(5, 1) // TCP proxy protocol version 1 on vsock 40504
        PortEvent.SnapshotEnd -> byteArrayOf(6)
    }

    private fun endpoint(kind: Int, port: Int, address: String, label: String?): ByteArray {
        require(port in 1..65_535 && validAddress(address))
        val name = label?.encodeToByteArray() ?: byteArrayOf()
        require(name.size <= MAX_LABEL_BYTES)
        val encoded = address.encodeToByteArray()
        return ByteBuffer.allocate(4 + encoded.size + if (kind == 3) 2 + name.size else 0).apply {
            put(kind.toByte()); putShort(port.toShort()); put(encoded.size.toByte()); put(encoded)
            if (kind == 3) { putShort(name.size.toShort()); put(name) }
        }.array()
    }

    /** Numeric literals only; never DNS, zone IDs, paths or URI syntax. */
    fun validAddress(address: String): Boolean = when {
        address.length !in 2..45 -> false
        ':' in address -> address.count { it == ':' } >= 2 &&
            address.all { it in "0123456789abcdefABCDEF:." } &&
            runCatching { java.net.InetAddress.getByName(address); true }.getOrDefault(false)
        else -> address.split('.').let { parts -> parts.size == 4 && parts.all {
            it.isNotEmpty() && it.length <= 3 && it.all { digit -> digit in '0'..'9' } && (it.toIntOrNull() ?: -1) in 0..255
        } }
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
                PortEvent.Closed((buffer.short.toInt() and 0xffff).also { require(it > 0) })
            }
            3.toByte(), 4.toByte() -> {
                val kind = payload[0].toInt()
                require(buffer.remaining() >= 3)
                val port = buffer.short.toInt() and 0xffff
                val size = buffer.get().toInt() and 0xff
                require(port > 0 && size in 2..45 && buffer.remaining() >= size)
                val address = ByteArray(size).also(buffer::get).decodeToString(throwOnInvalidSequence = true)
                require(validAddress(address))
                if (kind == 4) {
                    require(!buffer.hasRemaining())
                    PortEvent.EndpointClosed(port, address)
                } else {
                    require(buffer.remaining() >= 2)
                    val labelSize = buffer.short.toInt() and 0xffff
                    require(labelSize <= MAX_LABEL_BYTES && buffer.remaining() == labelSize)
                    PortEvent.EndpointListening(port, address,
                        ByteArray(labelSize).also(buffer::get).decodeToString(throwOnInvalidSequence = true).ifBlank { null })
                }
            }
            5.toByte() -> { require(buffer.remaining() == 1 && buffer.get() == 1.toByte()); PortEvent.SnapshotBegin }
            6.toByte() -> { require(!buffer.hasRemaining()); PortEvent.SnapshotEnd }
            else -> throw IllegalArgumentException("Unknown port event")
        }
    }
}
