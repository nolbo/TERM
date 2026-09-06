package dev.terminal.runtime.rootless

import android.util.Log
import com.termux.terminal.PtyProcess
import dev.terminal.protocol.SessionConnectionState
import dev.terminal.protocol.SessionController
import dev.terminal.protocol.SessionDescriptor
import dev.terminal.protocol.SessionEvent
import dev.terminal.protocol.SessionStatus
import dev.terminal.runtime.session.pumpPtyOutput
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** PTY owner for PRoot sessions. Detach never closes the PTY or PRoot process. */
class RootlessSessionController(
    private val layout: RootlessDebianLayout,
    private val proot: File,
    private val loader: File,
) : SessionController {
    init {
        require(proot.isFile && proot.canExecute()) { "Packaged PRoot is not executable" }
        require(loader.isFile && loader.canExecute()) { "Packaged PRoot loader is not executable" }
    }

    private class Entry(@Volatile var descriptor: SessionDescriptor, val process: PtyProcess) {
        private val transcript = ByteArrayOutputStream()
        fun append(bytes: ByteArray, count: Int) = synchronized(transcript) {
            transcript.write(bytes, 0, count)
            if (transcript.size() > MAX_TRANSCRIPT_BYTES) {
                val retained = transcript.toByteArray().takeLast(MAX_TRANSCRIPT_BYTES).toByteArray()
                transcript.reset(); transcript.write(retained)
            }
        }
        fun snapshot(): ByteArray = synchronized(transcript) { transcript.toByteArray() }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val mutableEvents = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 128)
    private val mutableConnection = MutableStateFlow(SessionConnectionState.CONNECTED)
    override val events: SharedFlow<SessionEvent> = mutableEvents.asSharedFlow()
    override val connectionState: StateFlow<SessionConnectionState> = mutableConnection.asStateFlow()

    override suspend fun create(shell: String, cwd: String, env: Map<String, String>, rows: UShort, cols: UShort): Result<SessionDescriptor> = runCatching {
        layout.validateInstalledRootfs().getOrThrow()
        val id = "rootless-${UUID.randomUUID()}"
        val launch = buildRootlessLaunchSpec(layout, proot, loader, shell, cwd, env)
        val process = PtyProcess.start(
            launch.command,
            launch.hostCwd,
            launch.arguments,
            launch.hostEnvironment,
            rows.toInt(),
            cols.toInt(),
            0,
            0,
        )
        val descriptor = SessionDescriptor(id, "Rootless Debian", launch.guestShell, launch.guestCwd, System.currentTimeMillis(), rows, cols, SessionStatus.RUNNING)
        entries[id] = Entry(descriptor, process)
        scope.launch {
            pumpPtyOutput(
                process.inputStream,
                onOutput = { bytes ->
                    entries[id]?.append(bytes, bytes.size)
                    mutableEvents.emit(SessionEvent.Output(id, bytes))
                },
                onReadFailure = { error ->
                    if (entries.containsKey(id)) Log.w(TAG, "PTY output failed for $id", error)
                },
            )
        }
        scope.launch {
            val code = process.waitFor()
            entries[id]?.let { it.descriptor = it.descriptor.copy(state = SessionStatus.EXITED, exitCode = code) }
            mutableEvents.emit(SessionEvent.Exited(id, code))
        }
        descriptor
    }

    override suspend fun list(): Result<List<SessionDescriptor>> = Result.success(entries.values.map { it.descriptor })
    override suspend fun attach(sessionId: String): Result<SessionDescriptor> = runCatching {
        val entry = entries[sessionId] ?: error("Session not found")
        entry.snapshot().takeIf(ByteArray::isNotEmpty)?.let { mutableEvents.emit(SessionEvent.Output(sessionId, it)) }
        entry.descriptor
    }
    override suspend fun detach(sessionId: String): Result<Unit> = if (entries.containsKey(sessionId)) Result.success(Unit) else Result.failure(NoSuchElementException("Session not found"))
    override suspend fun resize(sessionId: String, rows: UShort, cols: UShort): Result<SessionDescriptor> = runCatching {
        val entry = entries[sessionId] ?: error("Session not found")
        entry.process.resize(rows.toInt(), cols.toInt(), 0, 0)
        entry.descriptor.copy(rows = rows, cols = cols).also { entry.descriptor = it }
    }
    override suspend fun write(sessionId: String, bytes: ByteArray): Result<Int> = runCatching {
        val process = entries[sessionId]?.process ?: error("Session not found")
        process.outputStream.write(bytes); process.outputStream.flush(); bytes.size
    }
    override suspend fun terminate(sessionId: String, signal: Int): Result<Unit> = runCatching {
        val entry = entries[sessionId] ?: error("Session not found")
        entry.process.terminate(signal)
        entries.remove(sessionId, entry)
        entry.process.close()
    }
    override fun close() {
        entries.values.forEach { it.process.terminate(SIGKILL); it.process.close() }
        entries.clear(); mutableConnection.value = SessionConnectionState.DISCONNECTED; scope.cancel()
    }

    private companion object {
        const val TAG = "RootlessSessionController"
        const val SIGKILL = 9
        const val MAX_TRANSCRIPT_BYTES = 256 * 1024
    }
}

internal data class RootlessLaunchSpec(
    val command: String,
    val hostCwd: String,
    val arguments: Array<String>,
    val hostEnvironment: Array<String>,
    val guestShell: String,
    val guestCwd: String,
)

internal fun buildRootlessLaunchSpec(
    layout: RootlessDebianLayout,
    proot: File,
    loader: File,
    shell: String,
    cwd: String,
    env: Map<String, String>,
): RootlessLaunchSpec {
    val guestCwd = cwd.takeIf { it.startsWith('/') } ?: "/root"
    val guestShell = shell.takeIf { it.startsWith('/') } ?: "/bin/sh"
    val arguments = buildList {
        // PtyProcess passes this array directly to execvp(), so argv[0] is explicit.
        add(proot.absolutePath)
        addAll(listOf("--link2symlink", "-0", "-r", layout.rootfs.absolutePath))
        addAll(listOf("-b", "/dev", "-b", "/proc", "-b", "/sys"))
        addAll(listOf("-b", "${layout.home.absolutePath}:/root"))
        addAll(listOf("-b", "${layout.workspace.absolutePath}:/workspace"))
        addAll(listOf("-w", guestCwd, "/usr/bin/env", "-i"))
        add("HOME=/root")
        add("USER=root")
        add("LANG=C.UTF-8")
        add("TERM=${env["TERM"] ?: "xterm-256color"}")
        add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        env.filterKeys { it.matches(ENV_KEY) && it !in RESERVED_ENV }.forEach { (key, value) -> add("$key=$value") }
        add(guestShell)
        if (env["DROID_LOGIN_SHELL"]?.toBooleanStrictOrNull() != false) add("-l")
    }.toTypedArray()
    return RootlessLaunchSpec(
        command = proot.absolutePath,
        hostCwd = layout.root.absolutePath,
        arguments = arguments,
        hostEnvironment = arrayOf(
            "HOME=${layout.home.absolutePath}",
            "TMPDIR=${layout.tmp.absolutePath}",
            "PROOT_TMP_DIR=${layout.tmp.absolutePath}",
            "PROOT_LOADER=${loader.absolutePath}",
        ),
        guestShell = guestShell,
        guestCwd = guestCwd,
    )
}

private val ENV_KEY = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
private val RESERVED_ENV = setOf("HOME", "PATH", "USER", "TMPDIR", "PROOT_TMP_DIR", "PROOT_LOADER", "DROID_LOGIN_SHELL")
