package dev.terminal.runtime.session

import dev.terminal.emulator.TerminalCheckpoint
import dev.terminal.emulator.TerminalEmulator
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.io.SequenceInputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** A process-wide, nonblocking capture budget; a slow observer cannot exhaust the runtime heap. */
internal class CheckpointBudget(private val limit: Int = 64 * 1024 * 1024) {
    class Exhausted : IOException("Terminal checkpoint capture budget exceeded")
    companion object { val shared = CheckpointBudget() }
    private var used = 0
    @Synchronized fun acquire(bytes: Int) {
        if (bytes > limit - used) throw Exhausted()
        used += bytes
    }
    @Synchronized fun release(bytes: Int) { used -= bytes; check(used >= 0) }
}

/** Frozen serialized state. Compression and filesystem I/O happen later on the delivery worker. */
internal class TerminalCheckpointSnapshot private constructor(
    private val chunks: List<ByteArray>, private val length: Int,
    val viewport: RuntimeTerminalState.Viewport?, private val budget: CheckpointBudget,
) : Closeable {
    private val closed = AtomicBoolean()
    fun writeTo(output: OutputStream) {
        check(!closed.get())
        val inputs = chunks.mapIndexed { index, bytes ->
            ByteArrayInputStream(bytes, 0, minOf(bytes.size, length - index * CHUNK))
        }
        SequenceInputStream(Collections.enumeration(inputs)).use { TerminalCheckpoint.writeCaptured(it, output) }
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) budget.release(chunks.size * CHUNK)
    }
    companion object {
        private const val CHUNK = 32 * 1024
        fun capture(emulator: TerminalEmulator, viewport: RuntimeTerminalState.Viewport?,
            budget: CheckpointBudget = CheckpointBudget.shared): TerminalCheckpointSnapshot {
            val chunks = mutableListOf<ByteArray>()
            var length = 0
            val output = object : OutputStream() {
                private fun room(): ByteArray {
                    if (length % CHUNK == 0) {
                        budget.acquire(CHUNK)
                        try { chunks += ByteArray(CHUNK) }
                        catch (failure: Throwable) { budget.release(CHUNK); throw failure }
                    }
                    return chunks.last()
                }
                override fun write(value: Int) { room()[length++ % CHUNK] = value.toByte() }
                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    var position = offset
                    while (position < offset + count) {
                        val target = room()
                        val size = minOf(offset + count - position, CHUNK - length % CHUNK)
                        bytes.copyInto(target, length % CHUNK, position, position + size)
                        length += size; position += size
                    }
                }
            }
            try {
                TerminalCheckpoint.capture(emulator, output)
                return TerminalCheckpointSnapshot(chunks.toList(), length, viewport, budget)
            } catch (failure: Throwable) {
                budget.release(chunks.size * CHUNK)
                throw failure
            }
        }
    }
}
