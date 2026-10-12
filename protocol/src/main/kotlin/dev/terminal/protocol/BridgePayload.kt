package dev.terminal.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Typed payloads for the capability-gated guest ↔ Android bridge service. */
sealed interface BridgeRequest {
    data object ClipboardGet : BridgeRequest
    data class ClipboardSet(val text: String) : BridgeRequest
    data class OpenUrl(val url: String) : BridgeRequest
    data class PostNotification(val title: String, val text: String) : BridgeRequest
    data object BatteryStatus : BridgeRequest
}

sealed interface BridgeResponse {
    data class Text(val text: String) : BridgeResponse
    data object Acknowledged : BridgeResponse
}

object BridgePayloadCodec {
    private const val CLIPBOARD_GET: Byte = 1
    private const val CLIPBOARD_SET: Byte = 2
    private const val OPEN_URL: Byte = 3
    private const val POST_NOTIFICATION: Byte = 4
    private const val BATTERY_STATUS: Byte = 5
    private const val RESPONSE_TEXT: Byte = 0x80.toByte()
    private const val RESPONSE_ACK: Byte = 0x81.toByte()
    private const val MAX_STRING_BYTES = 65_535

    fun encodeRequest(request: BridgeRequest): ByteArray = when (request) {
        BridgeRequest.ClipboardGet -> byteArrayOf(CLIPBOARD_GET)
        is BridgeRequest.ClipboardSet -> encodeStrings(CLIPBOARD_SET, listOf(request.text))
        is BridgeRequest.OpenUrl -> encodeStrings(OPEN_URL, listOf(request.url))
        is BridgeRequest.PostNotification -> encodeStrings(POST_NOTIFICATION, listOf(request.title, request.text))
        BridgeRequest.BatteryStatus -> byteArrayOf(BATTERY_STATUS)
    }

    fun decodeRequest(payload: ByteArray): BridgeRequest {
        require(payload.isNotEmpty()) { "Missing bridge request code" }
        return when (payload[0]) {
            CLIPBOARD_GET -> {
                require(payload.size == 1) { "Clipboard get request must be empty" }
                BridgeRequest.ClipboardGet
            }
            CLIPBOARD_SET -> BridgeRequest.ClipboardSet(decodeStrings(payload, 1).single())
            OPEN_URL -> BridgeRequest.OpenUrl(decodeStrings(payload, 1).single())
            POST_NOTIFICATION -> decodeStrings(payload, 2).let { BridgeRequest.PostNotification(it[0], it[1]) }
            BATTERY_STATUS -> { require(payload.size == 1) { "Battery request must be empty" }; BridgeRequest.BatteryStatus }
            else -> throw IllegalArgumentException("Unknown bridge request code")
        }
    }

    fun encodeResponse(response: BridgeResponse): ByteArray = when (response) {
        is BridgeResponse.Text -> encodeStrings(RESPONSE_TEXT, listOf(response.text))
        BridgeResponse.Acknowledged -> byteArrayOf(RESPONSE_ACK)
    }

    fun decodeResponse(payload: ByteArray): BridgeResponse {
        require(payload.isNotEmpty()) { "Missing bridge response code" }
        return when (payload[0]) {
            RESPONSE_TEXT -> BridgeResponse.Text(decodeStrings(payload, 1).single())
            RESPONSE_ACK -> {
                require(payload.size == 1) { "Bridge acknowledgement must be empty" }
                BridgeResponse.Acknowledged
            }
            else -> throw IllegalArgumentException("Unknown bridge response code")
        }
    }

    private fun encodeStrings(type: Byte, strings: List<String>): ByteArray {
        val encoded = strings.map { it.encodeToByteArray().also { bytes -> require(bytes.size <= MAX_STRING_BYTES) } }
        val size = 1 + encoded.sumOf { 2 + it.size }
        return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN).apply {
            put(type)
            encoded.forEach { bytes -> putShort(bytes.size.toShort()); put(bytes) }
        }.array()
    }

    private fun decodeStrings(payload: ByteArray, count: Int): List<String> {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        buffer.get()
        return buildList {
            repeat(count) {
                require(buffer.remaining() >= 2) { "Truncated bridge string length" }
                val length = buffer.short.toInt() and 0xffff
                require(buffer.remaining() >= length) { "Truncated bridge string" }
                add(ByteArray(length).also(buffer::get).decodeToString(throwOnInvalidSequence = true))
            }
            require(!buffer.hasRemaining()) { "Unexpected trailing bridge bytes" }
        }
    }
}
