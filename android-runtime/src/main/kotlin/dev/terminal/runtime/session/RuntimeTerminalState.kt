package dev.terminal.runtime.session

import dev.terminal.emulator.TerminalEmulator
import dev.terminal.emulator.TerminalOutput
import java.util.Properties

/** Authoritative terminal state, independent of every renderer and UI-process connection.
 * Concurrent callers use withSession, including immutable checkpoint capture and ordered enqueue.
 * Production sinks never wait for UI Binder calls while holding this mutex.
 */
class RuntimeTerminalState(private val transcriptRows: Int = 10_000) {
    interface Sink {
        fun checkpoint(emulator: TerminalEmulator, viewport: Viewport?)
        fun output(bytes: ByteArray)
        fun resized(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int)
        fun exited(code: Int)
    }
    data class Viewport(val row: Int, val offset: Float, val transcriptShift: Long)
    private class Entry(val emulator: TerminalEmulator, val replies: MutableList<ByteArray>, val activity: MutableList<String>) {
        val sinks = mutableSetOf<Sink>()
        var exitCode: Int? = null
        var viewport: Viewport? = null
    }
    private val locks = SessionOperationLocks()
    suspend fun <T> withSession(id: String, action: suspend () -> T): T = locks.withSession(id, action)
    // close() may discard the registry while a cancelled PTY reader unwinds.
    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    internal fun identity(id: String): Any? = entries[id]

    fun create(id: String, columns: Int, rows: Int) {
        if (entries.containsKey(id)) return
        val replies = mutableListOf<ByteArray>()
        val activity = mutableListOf<String>()
        val output = object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) { replies += data.copyOfRange(offset, offset + count) }
            override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
            override fun onCopyTextToClipboard(text: String?) = Unit
            override fun onPasteTextFromClipboard() = Unit
            override fun onBell() = Unit
            override fun onColorsChanged() = Unit
            override fun onSessionActivity(command: String) { activity += command }
        }
        entries[id] = Entry(TerminalEmulator(output, columns, rows, 0, 0, transcriptRows, null), replies, activity)
    }

    fun output(id: String, bytes: ByteArray): List<ByteArray> {
        // A reader may finish an already queued frame after explicit termination.
        val entry = entries[id] ?: return emptyList()
        entry.emulator.append(bytes, bytes.size)
        deliver(entry) { it.output(bytes) }
        return entry.replies.toList().also { entry.replies.clear() }
    }

    fun takeActivity(id: String): List<String> = entries[id]?.activity?.let { events ->
        if (events.isEmpty()) emptyList() else events.toList().also { events.clear() }
    } ?: emptyList()

    fun resize(id: String, columns: Int, rows: Int, cellWidth: Int? = null, cellHeight: Int? = null) {
        val entry = entries[id] ?: return
        val emulator = entry.emulator
        val width = cellWidth ?: emulator.cellWidthPixels
        val height = cellHeight ?: emulator.cellHeightPixels
        if (columns == emulator.mColumns && rows == emulator.mRows && width == emulator.cellWidthPixels && height == emulator.cellHeightPixels) return
        emulator.resize(columns, rows, width, height)
        deliver(entry) { it.resized(columns, rows, width, height) }
    }

    fun palette(id: String, properties: Properties) { entries[id]?.emulator?.mColors?.setDefaultColors(properties) }
    fun sameDimensions(id: String, columns: Int, rows: Int): Boolean = entries[id]?.emulator?.let {
        it.mColumns == columns && it.mRows == rows
    } ?: false
    fun viewport(id: String, value: Viewport) { entries[id]?.viewport = value }

    fun subscribe(id: String, sink: Sink) {
        val entry = entries[id] ?: error("Terminal state missing: $id")
        sink.checkpoint(entry.emulator, entry.viewport)
        entry.exitCode?.let(sink::exited)
        entry.sinks += sink
    }
    fun unsubscribe(id: String, sink: Sink) { entries[id]?.sinks?.remove(sink) }
    fun exited(id: String, code: Int) {
        entries[id]?.let { entry -> entry.exitCode = code; deliver(entry) { it.exited(code) } }
    }
    fun remove(id: String) { entries.remove(id) }
    fun clear() { entries.clear() }

    private fun deliver(entry: Entry, action: (Sink) -> Unit) {
        entry.sinks.removeAll { sink -> runCatching { action(sink) }.isFailure }
    }
}
