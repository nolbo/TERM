package dev.terminal.runtime.lifecycle

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Guest RPCs after exit must never run in the collector that drains their responses. */
internal class SessionExitReconciler(
    scope: CoroutineScope,
    private val reconcile: suspend () -> Unit,
    private val onFailure: (Exception) -> Unit = {},
) : Closeable {
    // One running reconciliation and at most one pending pass, even during an exit burst.
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val worker = scope.launch {
        for (request in requests) {
            try { reconcile() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { runCatching { onFailure(failure) } }
        }
    }.also { it.invokeOnCompletion { requests.cancel() } }

    fun request(): Boolean = requests.trySend(Unit).isSuccess

    override fun close() {
        requests.cancel()
        worker.cancel()
    }
}
