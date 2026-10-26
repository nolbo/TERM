package dev.terminal.runtime.session

import android.os.ParcelFileDescriptor
import dev.terminal.emulator.TerminalCheckpoint
import dev.terminal.emulator.TerminalEmulator
import java.io.DataOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.Closeable
import java.util.concurrent.Executor
import dev.terminal.runtime.ipc.ITerminalStateListener
import dev.terminal.runtime.lifecycle.BoundedCallbackQueue

internal class QueuedTerminalSink(directory: File, listener: ITerminalStateListener,
    executor: Executor, private val captureBudget: CheckpointBudget = CheckpointBudget.shared,
    onFailure: (Exception) -> Unit) : RuntimeTerminalState.Sink, Closeable {
    private val queue = BoundedCallbackQueue(executor, onFailure)
    private val cache = directory
    private val target = listener
    override fun checkpoint(emulator: TerminalEmulator, viewport: RuntimeTerminalState.Viewport?) {
        val snapshot = try { TerminalCheckpointSnapshot.capture(emulator, viewport, captureBudget) }
        catch (_: CheckpointBudget.Exhausted) {
            // Preserve large histories and all existing compressed-checkpoint limits.
            // Under capture pressure only this session uses the original disk path.
            val descriptor = terminalCheckpointFile(cache, viewport) { TerminalCheckpoint.write(emulator, it) }
            check(queue.offer(dispose = { descriptor.close() }) { descriptor.use { target.onCheckpoint(it) } })
            return
        }
        check(queue.offer(dispose = { snapshot.close() }) {
            try { terminalCheckpointFile(cache, snapshot).use { target.onCheckpoint(it) } }
            finally { snapshot.close() }
        })
    }
    override fun output(bytes: ByteArray) {
        check(queue.offerOutput(bytes) { target.onOutput(it) })
    }
    override fun resized(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        check(queue.offer { target.onResize(columns, rows, cellWidth, cellHeight) })
    }
    override fun exited(code: Int) { check(queue.offer { target.onExited(code) }) }
    override fun close() = queue.close()
}

/** Serialize an already frozen snapshot on the callback worker, outside every terminal lock. */
internal fun terminalCheckpointFile(directory: File, snapshot: TerminalCheckpointSnapshot): ParcelFileDescriptor {
    return terminalCheckpointFile(directory, snapshot.viewport, snapshot::writeTo)
}

private fun terminalCheckpointFile(directory: File, viewport: RuntimeTerminalState.Viewport?,
    write: (OutputStream) -> Unit): ParcelFileDescriptor {
    val file = File.createTempFile("terminal-checkpoint-", ".tmp", directory)
    var descriptor: ParcelFileDescriptor? = null
    try {
        DataOutputStream(LimitedCheckpointOutput(file.outputStream().buffered())).use { out ->
            out.writeBoolean(viewport != null)
            if (viewport != null) {
                out.writeInt(viewport.row); out.writeFloat(viewport.offset); out.writeLong(viewport.transcriptShift)
            }
            write(out)
        }
        descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        check(file.delete()) { "Unable to unlink terminal checkpoint" }
        return descriptor
    } catch (error: Throwable) {
        runCatching { descriptor?.close() }
        throw error
    } finally { file.delete() }
}

private class LimitedCheckpointOutput(output: OutputStream) : FilterOutputStream(output) {
    private var written = 0L
    override fun write(value: Int) { reserve(1); out.write(value) }
    override fun write(bytes: ByteArray, offset: Int, length: Int) { reserve(length); out.write(bytes, offset, length) }
    private fun reserve(length: Int) {
        if (written + length > 64L * 1024 * 1024) throw IOException("Terminal checkpoint exceeds 64 MiB")
        written += length
    }
}
