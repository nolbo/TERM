package dev.terminal.app

import com.termux.terminal.TerminalSessionBackend

class RemoteTerminalBackend(
    private val write: (ByteArray) -> Unit,
    private val resize: (columns: Int, rows: Int) -> Unit,
) : TerminalSessionBackend {
    @Volatile private var callback: TerminalSessionBackend.Callback? = null
    @Volatile private var running = false
    @Volatile private var pendingExitCode: Int? = null

    override fun start(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int, callback: TerminalSessionBackend.Callback) {
        this.callback = callback
        running = true
        pendingExitCode?.let {
            pendingExitCode = null
            running = false
            callback.onExit(it)
        }
    }
    override fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) = resize(columns, rows)
    override fun write(data: ByteArray, offset: Int, count: Int) = write(data.copyOfRange(offset, offset + count))
    fun output(data: ByteArray) { callback?.onOutput(data, 0, data.size) }
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
