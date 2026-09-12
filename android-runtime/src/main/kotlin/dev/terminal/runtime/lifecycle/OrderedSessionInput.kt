package dev.terminal.runtime.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Enqueue at the Binder boundary, before dispatch can reorder writes. */
internal class OrderedSessionInput(
    scope: CoroutineScope,
    private val write: suspend (String, ByteArray) -> Unit,
) {
    private val requests = Channel<Pair<String, ByteArray>>(256)

    init {
        scope.launch {
            try {
                for ((id, bytes) in requests) write(id, bytes)
            } finally {
                requests.cancel()
            }
        }
    }

    fun offer(id: String, bytes: ByteArray): Boolean =
        requests.trySend(id to bytes.copyOf()).isSuccess
}
