package dev.terminal.runtime.lifecycle

import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.Executor

/** A slow Binder client may block its worker, never the producer or another client's queue.
 * onFailure must be non-blocking (Binder notifications use oneway methods).
 */
class BoundedCallbackQueue(
    private val executor: Executor,
    private val onFailure: (Exception) -> Unit,
    private val maxBytes: Int = 1024 * 1024,
    private val maxTasks: Int = 256,
) : Closeable {
    private class Task(var bytes: Int, val dispose: () -> Unit, val action: () -> Unit,
        val output: ByteArrayOutputStream? = null, val key: Any? = null, val deliver: ((ByteArray) -> Unit)? = null)
    private val pending = ArrayDeque<Task>()
    private var bytes = 0
    private var running = false
    private var closed = false

    init { require(maxBytes > 0 && maxTasks > 0) }

    fun offer(bytes: Int = 0, dispose: () -> Unit = {}, action: () -> Unit): Boolean {
        require(bytes >= 0)
        return enqueue(Task(bytes, dispose, action))
    }

    /** Adjacent output may share one Binder call, but never cross a resize/exit/checkpoint. */
    fun offerOutput(output: ByteArray, key: Any = Unit, deliver: (ByteArray) -> Unit): Boolean {
        synchronized(this) {
            if (closed) return false
            val last = pending.peekLast()
            if (last?.output != null && last.key == key && last.bytes + output.size <= 64 * 1024 &&
                output.size <= maxBytes - bytes) {
                last.output.write(output)
                last.bytes += output.size
                bytes += output.size
                return true
            }
        }
        val data = ByteArrayOutputStream(output.size).apply { write(output) }
        return enqueue(Task(output.size, {}, {}, data, key, deliver))
    }

    private fun enqueue(task: Task): Boolean {
        var start = false
        var overflow = false
        synchronized(this) {
            if (!closed) {
                overflow = task.bytes > maxBytes - bytes || pending.size >= maxTasks
                if (!overflow) {
                    pending.addLast(task)
                    bytes += task.bytes
                    if (!running) { running = true; start = true }
                }
            } else { runCatching(task.dispose); return false }
        }
        if (overflow) {
            runCatching(task.dispose)
            fail(IOException(RESYNC_REQUIRED))
            return false
        }
        if (start) try { executor.execute(::drain) }
        catch (error: Exception) { fail(error); return false }
        return true
    }

    private fun drain() {
        while (true) {
            val task = synchronized(this) {
                if (closed || pending.isEmpty()) { running = false; return }
                pending.removeFirst()
            }
            try {
                if (task.output != null) task.deliver!!.invoke(task.output.toByteArray()) else task.action()
            }
            catch (error: Exception) { fail(error); return }
            finally {
                runCatching(task.dispose)
                synchronized(this) { bytes -= task.bytes }
            }
        }
    }

    private fun fail(error: Exception) {
        if (discard()) runCatching { onFailure(error) }
    }

    private fun discard(): Boolean {
        val removed = synchronized(this) {
            if (closed) return false
            closed = true
            pending.toList().also { tasks ->
                pending.clear()
                bytes -= tasks.sumOf { it.bytes }
            }
        }
        removed.forEach { runCatching(it.dispose) }
        return true
    }

    override fun close() { discard() }

    companion object { const val RESYNC_REQUIRED = "TERMINAL_RESYNC_REQUIRED" }
}
