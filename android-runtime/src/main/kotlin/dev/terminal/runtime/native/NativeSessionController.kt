package dev.terminal.runtime.native

import dev.terminal.protocol.SessionConnectionState
import dev.terminal.protocol.SessionController
import dev.terminal.protocol.SessionDescriptor
import dev.terminal.protocol.SessionEvent
import dev.terminal.protocol.SessionStatus
import com.termux.terminal.PtyProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Persistent Termux-style PTY sessions, owned by the runtime process rather than the Activity. */
class NativeSessionController(private val root: File) : SessionController {
    private class Entry(
        @Volatile var descriptor: SessionDescriptor,
        val process: PtyProcess,
    ) {
        private val transcript = ByteArrayOutputStream()

        fun append(bytes: ByteArray, count: Int) = synchronized(transcript) {
            transcript.write(bytes, 0, count)
            if (transcript.size() > MAX_TRANSCRIPT_BYTES) {
                val retained = transcript.toByteArray().takeLast(MAX_TRANSCRIPT_BYTES).toByteArray()
                transcript.reset()
                transcript.write(retained)
            }
        }

        fun snapshot(): ByteArray = synchronized(transcript) { transcript.toByteArray() }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val output = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 128)
    private val connection = MutableStateFlow(SessionConnectionState.CONNECTED)
    override val events: SharedFlow<SessionEvent> = output.asSharedFlow()
    override val connectionState: StateFlow<SessionConnectionState> = connection.asStateFlow()

    override suspend fun create(shell: String, cwd: String, env: Map<String, String>, rows: UShort, cols: UShort): Result<SessionDescriptor> = runCatching {
        val id = "native-${UUID.randomUUID()}"
        val home = File(root, "home").apply { mkdirs() }
        val command = shell.takeIf { it.startsWith('/') && File(it).canExecute() } ?: "/system/bin/sh"
        val loginShell = env["DROID_LOGIN_SHELL"]?.toBooleanStrictOrNull() ?: true
        val environment = buildList {
            add("HOME=${home.absolutePath}")
            add("TMPDIR=${File(root, "tmp").absolutePath}")
            add("PATH=/system/bin:/system/xbin")
            add("SHELL=$command")
            add("TERM=xterm-256color")
            add("COLORTERM=truecolor")
            // \w renders the home directory as ~ while preserving real
            // subdirectories, matching the conventional Termux prompt.
            add("PS1=\\w \\${'$'} ")
            env.filterKeys { it != "DROID_LOGIN_SHELL" }.forEach { (key, value) -> add("$key=$value") }
        }.toTypedArray()
        val process = PtyProcess.start(
            command, home.absolutePath, if (loginShell) arrayOf(command, "-l") else arrayOf(command), environment,
            rows.toInt(), cols.toInt(), 0, 0,
        )
        val descriptor = SessionDescriptor(id, "Native shell", command, home.absolutePath, System.currentTimeMillis(), rows, cols, SessionStatus.RUNNING)
        entries[id] = Entry(descriptor, process)
        scope.launch {
            process.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    entries[id]?.append(buffer, count)
                    output.emit(SessionEvent.Output(id, buffer.copyOf(count)))
                }
            }
        }
        scope.launch {
            val code = process.waitFor()
            entries[id]?.descriptor = entries[id]?.descriptor?.copy(state = SessionStatus.EXITED, exitCode = code) ?: return@launch
            output.emit(SessionEvent.Exited(id, code))
        }
        descriptor
    }

    override suspend fun list(): Result<List<SessionDescriptor>> = Result.success(entries.values.map { it.descriptor })
    override suspend fun attach(sessionId: String): Result<SessionDescriptor> = runCatching {
        val entry = entries[sessionId] ?: error("Session not found")
        entry.snapshot().takeIf { it.isNotEmpty() }?.let { output.emit(SessionEvent.Output(sessionId, it)) }
        entry.descriptor
    }
    override suspend fun detach(sessionId: String): Result<Unit> = if (entries.containsKey(sessionId)) Result.success(Unit) else Result.failure(NoSuchElementException("Session not found"))
    override suspend fun resize(sessionId: String, rows: UShort, cols: UShort): Result<SessionDescriptor> = runCatching {
        val entry = entries[sessionId] ?: error("Session not found")
        entry.process.resize(rows.toInt(), cols.toInt(), 0, 0)
        val resized = entry.descriptor.copy(rows = rows, cols = cols)
        entry.descriptor = resized
        resized
    }
    override suspend fun write(sessionId: String, bytes: ByteArray): Result<Int> = runCatching {
        val process = entries[sessionId]?.process ?: error("Session not found")
        process.outputStream.write(bytes)
        process.outputStream.flush()
        bytes.size
    }
    override suspend fun terminate(sessionId: String, signal: Int): Result<Unit> = runCatching {
        val entry = entries[sessionId] ?: error("Session not found")
        entry.process.terminate(signal)
        entries.remove(sessionId, entry)
        entry.process.close()
    }
    override fun close() {
        entries.values.forEach { entry ->
            entry.process.terminate(SIGKILL)
            entry.process.close()
        }
        entries.clear()
        connection.value = SessionConnectionState.DISCONNECTED
    }

    private companion object {
        const val SIGKILL = 9
        const val MAX_TRANSCRIPT_BYTES = 256 * 1024
    }
}
