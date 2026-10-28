package dev.terminal.app

import dev.terminal.emulator.TerminalSessionBackend

class RemoteTerminalBackend(
    private val write: (ByteArray) -> Unit,
    private val resize: (columns: Int, rows: Int) -> Unit,
    private val onStarted: () -> Unit = {},
    private val authoritative: Boolean = false,
    private val onGeometry: () -> Unit = {},
) : TerminalSessionBackend {
    var columns = 80; private set
    var rows = 24; private set
    var cellWidth = 0; private set
    var cellHeight = 0; private set
    override fun ownsTerminalState() = authoritative
    @Volatile private var callback: TerminalSessionBackend.Callback? = null
    @Volatile private var running = false
    @Volatile private var pendingExitCode: Int? = null

    override fun start(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int, callback: TerminalSessionBackend.Callback) {
        this.columns = columns; this.rows = rows
        cellWidth = cellWidthPixels; cellHeight = cellHeightPixels
        this.callback = callback
        running = true
        pendingExitCode?.let {
            pendingExitCode = null
            running = false
            callback.onExit(it)
        }
        // The service creates PTYs before the view exists (initially 80x24).
        // A first attachment must synchronize geometry even if no later layout
        // change occurs, otherwise readline redraws wrapped input at the wrong width.
        if (running && !authoritative) resize(columns, rows)
        onStarted()
    }
    override fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        if (columns <= 0 || rows <= 0 || (this.columns == columns && this.rows == rows &&
                cellWidth == cellWidthPixels && cellHeight == cellHeightPixels)) return
        this.columns = columns; this.rows = rows
        cellWidth = cellWidthPixels; cellHeight = cellHeightPixels
        if (authoritative) onGeometry() else resize(columns, rows)
    }
    fun checkpoint(state: dev.terminal.emulator.TerminalEmulator) {
        callback?.let { current ->
            // Every checkpoint is followed by the owner's current exit status, if any.
            running = true
            current.onCheckpoint(state)
        }
    }
    fun mirrorOutput(bytes: ByteArray) { callback?.onMirrorOutput(bytes, 0, bytes.size) }
    fun resized(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) { callback?.onResize(columns, rows, cellWidth, cellHeight) }
    override fun write(data: ByteArray, offset: Int, count: Int) = write(data.copyOfRange(offset, offset + count))
    fun output(data: ByteArray, replay: Boolean = false) {
        if (replay) callback?.onReplayOutput(data, 0, data.size)
        else callback?.onOutput(data, 0, data.size)
    }
    fun exit(exitCode: Int) {
        val current = callback
        if (current == null) {
            pendingExitCode = exitCode
        } else if (running) {
            running = false
            current.onExit(exitCode)
        }
    }
    override fun finish() = Unit
    override fun close() { running = false; callback = null }
    override fun isRunning() = running
    override fun getPid() = 0
    override fun getCwd(): String? = null
}
