package dev.terminal.runtime.rootless

import android.util.Log
import com.termux.terminal.PtyProcess
import com.termux.terminal.TerminalReplayBuffer
import com.termux.terminal.KittyGraphicsTransport
import dev.terminal.runtime.session.GraphicsMediaResolver
import dev.terminal.protocol.SessionConnectionState
import dev.terminal.protocol.SessionController
import dev.terminal.protocol.SessionDescriptor
import dev.terminal.protocol.SessionEvent
import dev.terminal.protocol.SessionStatus
import dev.terminal.runtime.session.pumpPtyOutput
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
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** PTY owner for PRoot sessions. Detach never closes the PTY or PRoot process. */
class RootlessSessionController(
    private val layout: RootlessDebianLayout,
    private val proot: File,
    private val loader: File,
    private val sharedStorage: () -> File? = { null },
    private val usbBroker: dev.terminal.runtime.usb.UsbBroker? = null,
    private val cacheDirectory: File = layout.tmp,
) : SessionController, dev.terminal.runtime.session.SessionMemorySource {
    init {
        require(proot.isFile && proot.canExecute()) { "Packaged PRoot is not executable" }
        require(loader.isFile && loader.canExecute()) { "Packaged PRoot loader is not executable" }
    }

    private class Entry(@Volatile var descriptor: SessionDescriptor, val process: PtyProcess) {
        val memoryIdentity = dev.terminal.runtime.session.processMemoryIdentity(process.processId)
        val outputMutex = Mutex()
        val inputMutex = Mutex()
        private val transcript = TerminalReplayBuffer(MAX_TRANSCRIPT_BYTES)
        fun append(bytes: ByteArray, count: Int) = transcript.append(bytes, count)
        fun snapshot(): ByteArray = transcript.snapshot()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = ConcurrentHashMap<String, Entry>()
    private var nssCache: RootlessNssCache? = null
    private var cacheClosed = false
    @Synchronized private fun cache(): RootlessNssCache? {
        check(!cacheClosed) { "Rootless controller closed" }
        if (nssCache == null && dev.terminal.runtime.BuildConfig.ROOTLESS_ACCELERATION &&
            layout.distribution == RootlessDistribution.DEBIAN &&
            resolveRootlessFile(layout.rootfs, "usr/sbin/nscd")?.canExecute() == true) {
            nssCache = runCatching { RootlessNssCache(layout, proot, loader, cacheDirectory) }
                .onFailure { Log.w(TAG, "NSS cache unavailable", it) }.getOrNull()
        }
        return nssCache
    }
    private val mutableEvents = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 128)
    private val mutableConnection = MutableStateFlow(SessionConnectionState.CONNECTED)
    override val events: SharedFlow<SessionEvent> = mutableEvents.asSharedFlow()
    override val connectionState: StateFlow<SessionConnectionState> = mutableConnection.asStateFlow()

    override fun memoryRoots() = entries.mapValues { (_, entry) ->
        entry.memoryIdentity.takeIf { entry.descriptor.state == SessionStatus.RUNNING }
    }

    override suspend fun create(shell: String, cwd: String, env: Map<String, String>, rows: UShort, cols: UShort): Result<SessionDescriptor> = runCatching {
        layout.validateInstalledRootfs().getOrThrow()
        val cache = cache()
        if (cache != null) {
            val ready = runInterruptible(Dispatchers.IO) {
                try { cache.ensureStarted() } catch (error: java.io.IOException) {
                    Log.w(TAG, "NSS cache process unavailable", error)
                    false
                }
            }
            if (!ready) Log.w(TAG, "NSS cache startup failed; using direct lookups")
        }
        val id = "${layout.distribution.runtime.idPrefix}${UUID.randomUUID()}"
        val storage = sharedStorage()
        val graphicsShm = File(layout.tmp, "graphics-shm").apply { mkdirs() }
        val media = GraphicsMediaResolver(
            buildList {
                add(GraphicsMediaResolver.Mapping("/", layout.rootfs))
                add(GraphicsMediaResolver.Mapping("/root", layout.home))
                add(GraphicsMediaResolver.Mapping("/workspace", layout.workspace))
                add(GraphicsMediaResolver.Mapping("/dev/shm", graphicsShm))
                storage?.let { add(GraphicsMediaResolver.Mapping("/sdcard", it)) }
            }, setOf("/tmp", "/dev/shm"), "/dev/shm",
        )
        val transport = KittyGraphicsTransport(media)
        val usbEnvironment = usbBroker?.register(id, layout.distribution.displayName).orEmpty()
        val launch = try { buildRootlessLaunchSpec(layout, proot, loader, shell, cwd, env, storage,
            usbBroker?.clientFile, usbEnvironment,
            accelerationLibrary = if (dev.terminal.runtime.BuildConfig.ROOTLESS_ACCELERATION)
                File(proot.parentFile, "libproot-faststat.so") else null,
            nssBindings = cache?.bindings.orEmpty()) } catch (error: Exception) { usbBroker?.unregister(id); throw error }
        val (process, descriptor) = try { synchronized(this) {
            check(!cacheClosed) { "Rootless controller closed" }
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
            val descriptor = SessionDescriptor(id, layout.distribution.displayName, launch.guestShell, launch.guestCwd, System.currentTimeMillis(), rows, cols, SessionStatus.RUNNING)
            entries[id] = Entry(descriptor, process)
            process to descriptor
        } } catch (error: Exception) { usbBroker?.unregister(id); throw error }
        scope.launch {
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
                                mutableEvents.emit(SessionEvent.Output(id, frame))
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
                        mutableEvents.emit(SessionEvent.Output(id, frame))
                    }
                }
            }
        }
        scope.launch {
            val code = process.waitFor()
            usbBroker?.unregister(id)
            entries[id]?.let { it.descriptor = it.descriptor.copy(state = SessionStatus.EXITED, exitCode = code) }
            mutableEvents.emit(SessionEvent.Exited(id, code))
        }
        descriptor
    }

    override suspend fun list(): Result<List<SessionDescriptor>> = Result.success(entries.values.map { it.descriptor })
    override suspend fun attach(sessionId: String): Result<SessionDescriptor> = runCatching {
        val entry = entries[sessionId] ?: error("Session not found")
        entry.outputMutex.withLock {
            val snapshot = entry.snapshot()
            mutableEvents.emit(SessionEvent.Output(sessionId, byteArrayOf(), replay = true))
            // Keep Binder payloads small and serialize replay before subsequent live bytes.
            for (offset in snapshot.indices step REPLAY_FRAME_BYTES) {
                mutableEvents.emit(SessionEvent.Output(sessionId,
                    snapshot.copyOfRange(offset, minOf(offset + REPLAY_FRAME_BYTES, snapshot.size)), replay = true))
            }
            mutableEvents.emit(SessionEvent.Output(sessionId, byteArrayOf(), replay = true))
            entry.descriptor
        }
    }
    override suspend fun detach(sessionId: String): Result<Unit> = if (entries.containsKey(sessionId)) Result.success(Unit) else Result.failure(NoSuchElementException("Session not found"))
    override suspend fun resize(sessionId: String, rows: UShort, cols: UShort): Result<SessionDescriptor> = runCatching {
        val entry = entries[sessionId] ?: error("Session not found")
        entry.process.resize(rows.toInt(), cols.toInt(), 0, 0)
        entry.descriptor.copy(rows = rows, cols = cols).also { entry.descriptor = it }
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
        usbBroker?.unregister(sessionId)
        entries.remove(sessionId, entry)
        entry.process.close()
    }
    @Synchronized override fun close() {
        entries.values.forEach { usbBroker?.unregister(it.descriptor.id); it.process.terminate(SIGKILL); it.process.close() }
        entries.clear(); mutableConnection.value = SessionConnectionState.DISCONNECTED; scope.cancel()
        cacheClosed = true
        nssCache?.close()
        nssCache = null
    }

    private companion object {
        const val TAG = "RootlessSessionController"
        const val SIGKILL = 9
        const val MAX_TRANSCRIPT_BYTES = 8 * 1024 * 1024
        const val REPLAY_FRAME_BYTES = 32 * 1024
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
    sharedStorage: File? = null,
    usbClientFile: File? = null,
    usbEnvironment: Map<String, String> = emptyMap(),
    accelerationLibrary: File? = null,
    nssBindings: List<String> = emptyList(),
): RootlessLaunchSpec {
    check(!RootlessNativeMigration.pending(layout.root.parentFile.toPath())) {
        "중단된 가속 형식 이전이 있습니다. 설정에서 해당 배포판의 이전을 다시 실행해 복구하세요"
    }
    val nativeLinks = RootlessStorageFormat.resolveForLaunch(layout.rootfs.toPath()) ==
        RootlessStorageFormat.LaunchFormat.NATIVE
    accelerationLibrary?.let {
        require(it.isFile && it.canRead()) { "Packaged Rootless acceleration library is missing" }
    }
    val acceleration = accelerationLibrary?.let { RootlessAccelerationProfile(it) }
    val argv = dev.terminal.runtime.session.SessionCommand.decode(env)
    val guestCwd = cwd.takeIf { it.startsWith('/') } ?: "/root"
    val guestShell = shell.takeIf { it.startsWith('/') } ?: "/bin/sh"
    val arguments = buildList {
        // PtyProcess passes this array directly to execvp(), so argv[0] is explicit.
        add(proot.absolutePath)
        if (!nativeLinks) add("--link2symlink")
        addAll(listOf("-0", "-r", layout.rootfs.absolutePath))
        addAll(listOf("-b", "/dev", "-b", "/proc", "-b", "/sys"))
        addAll(listOf("-b", "${File(layout.tmp, "graphics-shm").absolutePath}:/dev/shm"))
        addAll(listOf("-b", "${layout.home.absolutePath}:/root"))
        addAll(listOf("-b", "${layout.workspace.absolutePath}:/workspace"))
        sharedStorage?.let { addAll(listOf("-b", "${it.absolutePath}:/sdcard")) }
        usbClientFile?.let { addAll(listOf("-b", "${it.absolutePath}:/usr/local/bin/terminal-usb")) }
        acceleration?.let { addAll(listOf("-b", it.binding)) }
        nssBindings.forEach { addAll(listOf("-b", it)) }
        addAll(listOf("-w", guestCwd, "/usr/bin/env", "-i"))
        add("HOME=/root")
        add("USER=root")
        rootlessLocaleEnvironment(layout).forEach { (key, value) -> add("$key=$value") }
        add("TERM=${env["TERM"] ?: "xterm-256color"}")
        add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        env.filterKeys { it.matches(ENV_KEY) && it !in RESERVED_ENV &&
            (acceleration == null || (it != "LD_PRELOAD" && !it.startsWith("PROOT_"))) }.forEach { (key, value) -> add("$key=$value") }
        acceleration?.guestEnvironment(env["LD_PRELOAD"])?.forEach { (key, value) -> add("$key=$value") }
        usbEnvironment.filterKeys { it in setOf("TERMINAL_USB_SOCKET", "TERMINAL_USB_TOKEN") }
            .forEach { (key, value) -> add("$key=$value") }
        if (argv != null) addAll(argv) else {
            add(guestShell)
            if (env["DROID_LOGIN_SHELL"]?.toBooleanStrictOrNull() != false) add("-l")
        }
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
        ) + (acceleration?.hostEnvironment ?: emptyArray()),
        guestShell = guestShell,
        guestCwd = guestCwd,
    )
}

private val ENV_KEY = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
private val RESERVED_ENV = setOf("HOME", "PATH", "USER", "TMPDIR", "PROOT_TMP_DIR", "PROOT_LOADER", "DROID_LOGIN_SHELL", "DROID_SESSION_ARGV",
    "TERMINAL_USB_SOCKET", "TERMINAL_USB_TOKEN")
