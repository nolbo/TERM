package dev.terminal.runtime.lifecycle

import dev.terminal.runtime.session.SessionInputTarget
import java.io.Closeable
import java.util.ArrayDeque
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** FIFO per Environment controller, with one shared bound across pending and active input. */
internal class OrderedSessionInput(
    private val scope: CoroutineScope,
    private val capacity: Int = 256,
    private val onFailure: (String, Throwable) -> Unit = { _, _ -> },
) : Closeable {
    enum class Admission { ACCEPTED, CLOSED, INACTIVE, FULL, STALE }
    private class Request(val target: SessionInputTarget, val bytes: ByteArray)
    private class Lane(val owner: Any) {
        val pending = ArrayDeque<Request>()
        var worker: Job? = null
    }
    private val lock = Any()
    private val lanes = IdentityHashMap<Any, Lane>()
    private val parent = scope.coroutineContext[Job]
    private var outstanding = 0
    private var closed = false

    init { require(capacity > 0) }

    fun offer(target: SessionInputTarget, bytes: ByteArray): Boolean = offerResult(target, bytes) == Admission.ACCEPTED

    fun diagnostics(): Map<String, String> = synchronized(lock) {
        mapOf("queue_outstanding" to outstanding.toString(), "queue_capacity" to capacity.toString(),
            "queue_owners" to lanes.size.toString(), "queue_closed" to closed.toString())
    }

    fun offerResult(target: SessionInputTarget, bytes: ByteArray): Admission {
        var start: Job? = null
        synchronized(lock) {
            if (closed) return Admission.CLOSED
            if (parent?.isActive == false) return Admission.INACTIVE
            if (outstanding >= capacity) return Admission.FULL
            if (!target.isCurrent()) return Admission.STALE
            val lane = lanes.getOrPut(target.owner) { Lane(target.owner) }
            lane.pending.addLast(Request(target, bytes.copyOf()))
            outstanding++
            if (lane.worker == null) {
                val worker = scope.launch(start = CoroutineStart.LAZY) { drain(lane) }
                lane.worker = worker
                worker.invokeOnCompletion { failure -> finished(lane, failure) }
                if (worker.isCancelled) return Admission.INACTIVE
                start = worker
            }
        }
        // A dispatcher may run inline: never execute a writer under the queue lock.
        start?.start()
        return Admission.ACCEPTED
    }

    private suspend fun drain(lane: Lane) {
        while (true) {
            coroutineContext.ensureActive()
            val request = synchronized(lock) {
                lane.pending.pollFirst() ?: run {
                    if (lanes[lane.owner] === lane) lanes.remove(lane.owner)
                    return
                }
            }
            try { request.target.write(request.bytes).getOrThrow() }
            catch (cancelled: CancellationException) {
                if (reporting()) failed(request, cancelled)
                throw cancelled
            }
            catch (failure: Throwable) { if (reporting()) failed(request, failure) }
            finally { synchronized(lock) { outstanding-- } }
        }
    }

    private fun finished(lane: Lane, failure: Throwable?) {
        val dropped = synchronized(lock) {
            if (lanes[lane.owner] === lane) lanes.remove(lane.owner)
            lane.pending.toList().also { outstanding -= it.size; lane.pending.clear() }
        }
        if (failure != null && reporting()) dropped.forEach { failed(it, failure) }
    }

    private fun reporting() = synchronized(lock) { !closed && parent?.isActive != false }
    private fun failed(request: Request, failure: Throwable) {
        runCatching { onFailure(request.target.sessionId, failure) }
    }

    override fun close() {
        val workers = synchronized(lock) {
            if (closed) return
            closed = true
            lanes.values.mapNotNull { lane ->
                outstanding -= lane.pending.size
                lane.pending.clear()
                lane.worker
            }.also { lanes.clear() }
        }
        workers.forEach { it.cancel() }
    }
}
