package dev.terminal.runtime.lifecycle

import dev.terminal.runtime.session.SessionInputTarget
import java.io.Closeable
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** FIFO per controller/session pair; a blocked PTY never occupies a sibling's input lane. */
internal class OrderedSessionInput(
    private val scope: CoroutineScope,
    private val capacity: Int = 256,
    private val perSessionCapacity: Int = minOf(capacity, 64),
    private val onFailure: (String, Throwable) -> Unit = { _, _ -> },
) : Closeable {
    enum class Admission { ACCEPTED, CLOSED, INACTIVE, FULL, STALE }
    private class Request(val target: SessionInputTarget, val bytes: ByteArray)
    private class Key(val owner: Any, val session: String) {
        override fun equals(other: Any?) = other is Key && owner === other.owner && session == other.session
        override fun hashCode() = 31 * System.identityHashCode(owner) + session.hashCode()
    }
    private class Lane(val key: Key) {
        val pending = ArrayDeque<Request>()
        var worker: Job? = null
        var outstanding = 0
    }
    private val lock = Any()
    private val lanes = HashMap<Key, Lane>()
    private val parent = scope.coroutineContext[Job]
    private var outstanding = 0
    private var closed = false

    init { require(capacity > 0 && perSessionCapacity in 1..capacity) }

    fun offer(target: SessionInputTarget, bytes: ByteArray): Boolean = offerResult(target, bytes) == Admission.ACCEPTED

    fun diagnostics(): Map<String, String> = synchronized(lock) {
        mapOf("queue_outstanding" to outstanding.toString(), "queue_capacity" to capacity.toString(),
            "queue_sessions" to lanes.size.toString(), "queue_closed" to closed.toString())
    }

    fun offerResult(target: SessionInputTarget, bytes: ByteArray): Admission {
        var start: Job? = null
        synchronized(lock) {
            if (closed) return Admission.CLOSED
            if (parent?.isActive == false) return Admission.INACTIVE
            if (outstanding >= capacity) return Admission.FULL
            if (!target.isCurrent()) return Admission.STALE
            val key = Key(target.owner, target.sessionId)
            val lane = lanes.getOrPut(key) { Lane(key) }
            if (lane.outstanding >= perSessionCapacity) return Admission.FULL
            lane.pending.addLast(Request(target, bytes.copyOf()))
            lane.outstanding++
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
                    if (lanes[lane.key] === lane) lanes.remove(lane.key)
                    return
                }
            }
            try { request.target.write(request.bytes).getOrThrow() }
            catch (cancelled: CancellationException) {
                if (reporting()) failed(request, cancelled)
                throw cancelled
            }
            catch (failure: Throwable) { if (reporting()) failed(request, failure) }
            finally { synchronized(lock) { outstanding--; lane.outstanding-- } }
        }
    }

    private fun finished(lane: Lane, failure: Throwable?) {
        val dropped = synchronized(lock) {
            if (lanes[lane.key] === lane) lanes.remove(lane.key)
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
