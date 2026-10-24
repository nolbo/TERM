package dev.terminal.runtime.lifecycle

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Binder arrival order per session, with a shared bound including suspended operations. */
internal class TerminalOperationQueue(
    private val scope: CoroutineScope,
    capacity: Int = 512,
    private val onFailure: (Throwable) -> Unit,
) : Closeable {
    init { require(capacity > 0) }
    private class Request(val sessionId: String, val action: suspend () -> Unit)
    private val permits = Semaphore(capacity)
    private val incoming = Channel<Request>(capacity, onUndeliveredElement = { permits.release() })
    private val lifecycle = Any()
    private val tails = ConcurrentHashMap<String, Job>()
    private val workers = ConcurrentHashMap.newKeySet<Job>()
    private val closed = AtomicBoolean()
    private val parent = scope.coroutineContext[Job]
    private val dispatch = scope.launch {
        try {
            for (request in incoming) {
                synchronized(lifecycle) {
                    if (closed.get()) { permits.release(); continue }
                    val previous = tails[request.sessionId]
                    val worker = scope.launch(start = CoroutineStart.LAZY) {
                        previous?.join()
                        try { request.action() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Throwable) { runCatching { onFailure(failure) } }
                    }
                    tails[request.sessionId] = worker
                    workers.add(worker)
                    worker.invokeOnCompletion { failure ->
                        workers.remove(worker); tails.remove(request.sessionId, worker); permits.release()
                        if (failure != null && !closed.get() && parent?.isActive != false)
                            runCatching { onFailure(failure) }
                    }
                    worker.start()
                }
            }
        } finally {
            incoming.cancel()
        }
    }

    fun offer(sessionId: String, action: suspend () -> Unit): Boolean {
        if (closed.get() || parent?.isActive == false || !permits.tryAcquire()) return false
        if (incoming.trySend(Request(sessionId, action)).isSuccess) return true
        permits.release()
        return false
    }

    override fun close() {
        synchronized(lifecycle) {
            if (!closed.compareAndSet(false, true)) return
            incoming.cancel(); dispatch.cancel(); workers.forEach { it.cancel() }
        }
    }
}
