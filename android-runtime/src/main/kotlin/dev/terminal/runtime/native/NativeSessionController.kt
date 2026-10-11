package dev.terminal.runtime.native

import android.util.Log
import dev.terminal.protocol.SessionConnectionState
import dev.terminal.protocol.SessionController
import dev.terminal.protocol.SessionDescriptor
import dev.terminal.protocol.SessionEvent
import dev.terminal.protocol.SessionStatus
import dev.terminal.runtime.session.pumpPtyOutput
import com.termux.terminal.PtyProcess
import com.termux.terminal.TerminalReplayBuffer
import com.termux.terminal.KittyGraphicsTransport
import dev.terminal.runtime.session.GraphicsMediaResolver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Persistent Termux-style PTY sessions, owned by the runtime process rather than the Activity. */
class NativeSessionController(
    private val root: File,
    private val hostEnvironment: (String) -> Map<String, String> = { emptyMap() },
    private val releaseHostCapabilities: (String) -> Unit = {},
) : SessionController, dev.terminal.runtime.session.SessionMemorySource {
    private class Entry(
        @Volatile var descriptor: SessionDescriptor,
        val process: PtyProcess,
    ) {
        val memoryIdentity = dev.terminal.runtime.session.awaitProcessMemoryIdentity(process.processId)
        val outputMutex = Mutex()
        val inputMutex = Mutex()
        private val transcript = TerminalReplayBuffer(MAX_TRANSCRIPT_BYTES)
        fun append(bytes: ByteArray, count: Int) = transcript.append(bytes, count)
        fun snapshot(): ByteArray = transcript.snapshot()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val output = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 128)
    private val connection = MutableStateFlow(SessionConnectionState.CONNECTED)
    override val events: SharedFlow<SessionEvent> = output.asSharedFlow()
    override val connectionState: StateFlow<SessionConnectionState> = connection.asStateFlow()

    override fun memoryRoots() = entries.mapValues { (_, entry) ->
        entry.memoryIdentity.takeIf { entry.descriptor.state == SessionStatus.RUNNING }
    }

    override suspend fun create(shell: String, cwd: String, env: Map<String, String>, rows: UShort, cols: UShort): Result<SessionDescriptor> = runCatching {
        val id = "native-${UUID.randomUUID()}"
        val home = File(root, "home").apply { mkdirs() }
        val shellRc = prepareNativeShellRc(home)
        val temporary = File(root, "tmp").apply { mkdirs() }
        val media = GraphicsMediaResolver(
            listOf(
                GraphicsMediaResolver.Mapping(root.absolutePath, root.canonicalFile),
                GraphicsMediaResolver.Mapping(root.canonicalPath, root.canonicalFile),
            ),
            setOf(temporary.absolutePath, temporary.canonicalPath),
        )
        val transport = KittyGraphicsTransport(media)
        val argv = dev.terminal.runtime.session.SessionCommand.decode(env)
        val command = argv?.first() ?: shell.takeIf { it.startsWith('/') && File(it).canExecute() } ?: DEFAULT_NATIVE_SHELL
        val loginShell = env["DROID_LOGIN_SHELL"]?.toBooleanStrictOrNull() ?: true
        require(cwd.isEmpty() || (cwd.startsWith('/') && File(cwd).isDirectory)) { "Working directory does not exist: $cwd" }
        val capabilities = hostEnvironment(id)
        val environment = buildList {
            add("HOME=${home.absolutePath}")
            add("TMPDIR=${File(root, "tmp").absolutePath}")
            add("PATH=/system/bin:/system/xbin")
            add("SHELL=$command")
            add("TERM=xterm-256color")
            add("COLORTERM=truecolor")
            // Android's mksh resets PS1 from /system/etc/mkshrc. ENV is read
            // after that startup file for every interactive shell, including a
            // non-login shell, so it is the authoritative default prompt.
            add("ENV=${shellRc.absolutePath}")
            add("PS1=~ \\${'$'} ")
            env.filterKeys { it != "DROID_LOGIN_SHELL" && it != dev.terminal.runtime.session.SessionCommand.KEY && !it.startsWith("TERMINAL_USB_") }.forEach { (key, value) -> add("$key=$value") }
            capabilities.forEach { (key, value) -> add("$key=$value") }
        }.associate { assignment -> assignment.substringBefore('=') to assignment.substringAfter('=') }
            .map { (key, value) -> "$key=$value" }.toTypedArray()
        val workingDirectory = cwd.ifEmpty { home.absolutePath }
        val process = try { PtyProcess.start(
            command, workingDirectory, argv?.toTypedArray() ?: if (loginShell) arrayOf(command, "-l") else arrayOf(command), environment,
            rows.toInt(), cols.toInt(), 0, 0,
        ) } catch (failure: Exception) { releaseHostCapabilities(id); throw failure }
        val descriptor = SessionDescriptor(id, "Native shell", command, workingDirectory, System.currentTimeMillis(), rows, cols, SessionStatus.RUNNING)
        entries[id] = Entry(descriptor, process)
        val reader = scope.launch {
            pumpPtyOutput(
                process.inputStream,
                onOutput = { bytes ->
                    entries[id]?.let { entry ->
                        entry.outputMutex.withLock {
                            val resolved = transport.accept(bytes)
                            entry.inputMutex.withLock {
                                resolved.replies.forEach { process.outputStream.write(it) }
                            }
                            resolved.bytes.forEach { frame ->
                                entry.append(frame, frame.size)
                                output.emit(SessionEvent.Output(id, frame))
                            }
                        }
                    }
                },
                onReadFailure = { error ->
                    if (entries.containsKey(id)) Log.w(TAG, "PTY output failed for $id", error)
                },
            )
            entries[id]?.let { entry ->
                entry.outputMutex.withLock {
                    transport.finish().bytes.forEach { frame ->
                        entry.append(frame, frame.size)
                        output.emit(SessionEvent.Output(id, frame))
                    }
                }
            }
        }
        scope.launch {
            val code = process.waitFor()
            reader.join()
            releaseHostCapabilities(id)
            entries[id]?.descriptor = entries[id]?.descriptor?.copy(state = SessionStatus.EXITED, exitCode = code) ?: return@launch
            output.emit(SessionEvent.Exited(id, code))
        }
        descriptor
    }

    override suspend fun list(): Result<List<SessionDescriptor>> = Result.success(entries.values.map { it.descriptor })
    override suspend fun attach(sessionId: String): Result<SessionDescriptor> = runCatching {
        val entry = entries[sessionId] ?: error("Session not found")
        entry.outputMutex.withLock {
            val snapshot = entry.snapshot()
            output.emit(SessionEvent.Output(sessionId, byteArrayOf(), replay = true))
            for (offset in snapshot.indices step REPLAY_FRAME_BYTES) {
                output.emit(SessionEvent.Output(sessionId,
                    snapshot.copyOfRange(offset, minOf(offset + REPLAY_FRAME_BYTES, snapshot.size)), replay = true))
            }
            output.emit(SessionEvent.Output(sessionId, byteArrayOf(), replay = true))
            entry.descriptor
        }
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
        val entry = entries[sessionId] ?: error("Session not found")
        entry.inputMutex.withLock {
            entry.process.outputStream.write(bytes)
            entry.process.outputStream.flush()
            bytes.size
        }
    }
    override suspend fun terminate(sessionId: String, signal: Int): Result<Unit> = runCatching {
        val entry = entries[sessionId] ?: error("Session not found")
        entry.process.terminate(signal)
        releaseHostCapabilities(sessionId)
        entries.remove(sessionId, entry)
        entry.process.close()
    }
    override fun close() {
        entries.forEach { (id, entry) ->
            entry.process.terminate(SIGKILL)
            releaseHostCapabilities(id)
            entry.process.close()
        }
        entries.clear()
        connection.value = SessionConnectionState.DISCONNECTED
        scope.cancel()
    }

    private companion object {
        const val TAG = "NativeSessionController"
        const val SIGKILL = 9
        const val MAX_TRANSCRIPT_BYTES = 8 * 1024 * 1024
        const val REPLAY_FRAME_BYTES = 32 * 1024
    }
}

/** Writes the app-owned interactive-shell defaults without modifying a user's profile. */
internal fun prepareNativeShellRc(home: File): File = File(home, ".droid-shrc").apply {
    writeText(
        """
        # The packaged Android client is an alias, not a writable-data executable.
        if [ -n "${'$'}{TERMINAL_USB_CLIENT-}" ] && [ -x "${'$'}TERMINAL_USB_CLIENT" ]; then
          alias terminal-usb='"${'$'}TERMINAL_USB_CLIENT"'
        fi
        # Emit prompt boundaries directly to the PTY, outside PS1 width accounting.
        case ${'$'}- in *i*) DROID_PROMPT_MARKERS=1 ;; esac
        PS1='${'$'}(droid_status=${'$'}?
        if [ "${'$'}{DROID_PROMPT_MARKERS-}" = 1 ]; then
          printf "\033]133;D;%s\007\033]133;A\007" "${'$'}droid_status" > /dev/tty
        fi
        p=${'$'}PWD; h=${'$'}HOME
        case "${'$'}p" in
          "${'$'}h"|"${'$'}h"/*) ;;
          *) p=${'$'}(pwd -P); h=${'$'}(cd -- "${'$'}HOME" && pwd -P) || h=${'$'}HOME ;;
        esac
        case "${'$'}p" in
          "${'$'}h") printf "%s" "~" ;;
          "${'$'}h"/*) printf "%s" "~/${'$'}{p#"${'$'}h"/}" ;;
          *) printf "%s" "${'$'}PWD" ;;
        esac)'
        # mksh needs non-printing delimiters so ANSI codes do not shift the input cursor.
        # Only the Android mksh prompt uses this encoding; other shells keep plain text.
        if [ -z "${'$'}{NO_COLOR-}" ] && [ "${'$'}{TERM-dumb}" != dumb ]; then
          if [ -n "${'$'}{KSH_VERSION-}" ]; then
            droid_mark=${'$'}(printf '\001')
            droid_cr=${'$'}(printf '\r')
            droid_esc=${'$'}(printf '\033')
            PS1="${'$'}droid_mark${'$'}droid_cr${'$'}droid_mark${'$'}droid_esc[0;32m${'$'}droid_mark${'$'}PS1${'$'}droid_mark${'$'}droid_esc[0m${'$'}droid_mark \${'$'} "
            PS2="${'$'}droid_mark${'$'}droid_cr${'$'}droid_mark${'$'}droid_esc[1;33m${'$'}droid_mark> ${'$'}droid_mark${'$'}droid_esc[0m${'$'}droid_mark"
            unset droid_mark droid_cr droid_esc
          else
            PS1="${'$'}PS1 \${'$'} "
          fi
          # Probe the installed tools: Android versions and user-provided binaries differ.
          # auto keeps redirected output and pipelines free of colour escapes.
          if command ls --color=auto -d / >/dev/null 2>&1; then
            alias ls='ls --color=auto'
          fi
          command grep --color=auto x /dev/null >/dev/null 2>&1
          if [ "${'$'}?" -le 1 ]; then
            alias grep='grep --color=auto'
          fi
        else
          PS1="${'$'}PS1 \${'$'} "
        fi
        # mksh has no PS0. Opt into echoed-input boundaries after the visible prompt.
        # Keep the OSC outside prompt width accounting, including with NO_COLOR/TERM=dumb.
        if [ "${'$'}{DROID_PROMPT_MARKERS-}" = 1 ] && [ -n "${'$'}{KSH_VERSION-}" ]; then
          droid_mark=${'$'}(printf '\001'); droid_cr=${'$'}(printf '\r')
          droid_esc=${'$'}(printf '\033'); droid_bel=${'$'}(printf '\007')
          case "${'$'}PS1" in "${'$'}droid_mark"*) ;; *) PS1="${'$'}droid_mark${'$'}droid_cr${'$'}droid_mark${'$'}PS1" ;; esac
          PS1="${'$'}PS1${'$'}droid_mark${'$'}droid_esc]133;B;droid=mksh${'$'}droid_bel${'$'}droid_mark"
          case "${'$'}{PS2-> }" in "${'$'}droid_mark"*) ;; *) PS2="${'$'}droid_mark${'$'}droid_cr${'$'}droid_mark${'$'}{PS2-> }" ;; esac
          PS2="${'$'}PS2${'$'}droid_mark${'$'}droid_esc]133;B;droid=mksh-continuation${'$'}droid_bel${'$'}droid_mark"
          unset droid_mark droid_cr droid_esc droid_bel
        fi
        """.trimIndent() + System.lineSeparator(),
    )
}
