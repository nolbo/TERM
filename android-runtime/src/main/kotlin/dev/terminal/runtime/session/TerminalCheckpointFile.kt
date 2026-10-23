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
    executor: Executor, onFailure: (Exception) -> Unit) : RuntimeTerminalState.Sink, Closeable {
    private val queue = BoundedCallbackQueue(executor, onFailure)
    private val cache = directory
    private val target = listener
    override fun checkpoint(emulator: TerminalEmulator, viewport: RuntimeTerminalState.Viewport?) {
        val descriptor = terminalCheckpointFile(cache, emulator, viewport)
        check(queue.offer(dispose = { descriptor.close() }) { target.onCheckpoint(descriptor) })
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

/** Capture under the emulator lock, then transfer an immutable, unlinked, read-only file.
 * No UI pipe reader or Binder transaction participates in taking the snapshot.
 */
internal fun terminalCheckpointFile(directory: File, emulator: TerminalEmulator,
    viewport: RuntimeTerminalState.Viewport?): ParcelFileDescriptor {
    val file = File.createTempFile("terminal-checkpoint-", ".tmp", directory)
    var descriptor: ParcelFileDescriptor? = null
    try {
        DataOutputStream(LimitedCheckpointOutput(file.outputStream().buffered())).use { out ->
            out.writeBoolean(viewport != null)
            if (viewport != null) {
                out.writeInt(viewport.row); out.writeFloat(viewport.offset); out.writeLong(viewport.transcriptShift)
            }
            TerminalCheckpoint.write(emulator, out)
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
