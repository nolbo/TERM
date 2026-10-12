package dev.terminal.protocol

/** Initial SAF-backed filesystem RPC contract; handles are broker-owned opaque IDs. */
sealed interface StorageRequest {
    data class Read(val handle: String, val offset: Long, val length: Int) : StorageRequest
    data class Write(val handle: String, val offset: Long, val bytes: ByteArray) : StorageRequest
}

sealed interface StorageResponse {
    data class Bytes(val bytes: ByteArray) : StorageResponse
    data class Written(val count: Int) : StorageResponse
}
