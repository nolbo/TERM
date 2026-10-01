package dev.terminal.runtime.session

import dev.terminal.protocol.SessionConnectionState
import dev.terminal.protocol.SessionController
import dev.terminal.protocol.SessionDescriptor
import dev.terminal.protocol.SessionEvent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Routes session operations without exposing backend details to Binder or the renderer.
 * Controllers own PTYs; this registry owns only routing and merged event subscriptions.
 */
class UnifiedSessionRegistry(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val beforeCreate: () -> Unit = {},
    private val onInput: (String, ByteArray) -> Unit = { _, _ -> },
    private val recordLaunch: (String, SessionLaunchSpec) -> Unit = { _, _ -> },
    private val remoteCommand: (suspend (String, String) -> ByteArray?)? = null,
    val terminalStates: RuntimeTerminalState = RuntimeTerminalState(),
    private val initialPalette: (SessionLaunchSpec) -> java.util.Properties = { spec ->
        spec.palette?.let(TerminalPalette::asProperties) ?: java.util.Properties()
    },
) : SessionController {
    private data class Registration(val controller: SessionController, val jobs: List<Job>)
    private data class StorageKey(val runtime: SessionRuntime, val storageId: String = "legacy")

    private val controlStreams = ConcurrentHashMap<String, com.termux.terminal.KittyControlStream>()
    private val keyboardFlags = ConcurrentHashMap<String, Int>()
    private val commands = kotlinx.coroutines.channels.Channel<Pair<String, String>>(64)
    init {
        scope.launch {
            for ((source, command) in commands) {
                val reply = remoteCommand?.invoke(source, command)
                    ?: if (remoteCommand == null) "\u001bP@kitty-cmd{\"ok\":false,\"error\":\"Remote control disabled\"}\u001b\\".toByteArray() else null
                if (reply != null) write(source, reply)
            }
        }
    }
    fun keyboardFlags(sessionId: String): Int = keyboardFlags[sessionId] ?: 0

    private val registrations = ConcurrentHashMap<StorageKey, Registration>()
    private val owners = ConcurrentHashMap<String, StorageKey>()
    private val lastKnownSessions = ConcurrentHashMap<StorageKey, List<SessionDescriptor>>()
    private val listMutex = Mutex()
    private val mutationLocks = dev.terminal.runtime.lifecycle.StoragePreparationLocks()
    private val mutableEvents = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 128)
    private val mutableConnection = MutableStateFlow(SessionConnectionState.CONNECTED)

    override val events: SharedFlow<SessionEvent> = mutableEvents.asSharedFlow()
    override val connectionState: StateFlow<SessionConnectionState> = mutableConnection.asStateFlow()

    fun register(runtime: SessionRuntime, controller: SessionController, storageId: String = "legacy") {
        val key = StorageKey(runtime, storageId)
        registrations.remove(key)?.jobs?.forEach(Job::cancel)
        val eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            controller.events.collect { event ->
                val sessionId = when (event) {
                    is SessionEvent.Output -> event.sessionId
                    is SessionEvent.Exited -> event.sessionId
                }
                owners.putIfAbsent(sessionId, key)
                if (event is SessionEvent.Output && !event.replay) {
                    val stream = controlStreams.getOrPut(sessionId) { com.termux.terminal.KittyControlStream() }
                    val parts = stream.accept(event.bytes)
                    keyboardFlags[sessionId] = stream.keyboard().flags()
                    for (part in parts) {
                        if (part.bytes != null) {
                            val replies = terminalStates.mutex.withLock {
                                terminalStates.output(sessionId, part.bytes)
                            }
                            replies.forEach { reply ->
                                controller.write(sessionId, reply).onFailure {
                                    android.util.Log.w("TerminalState", "Terminal query reply failed for $sessionId", it)
                                }
                            }
                            mutableEvents.emit(SessionEvent.Output(sessionId, part.bytes))
                        }
                        else commands.trySend(sessionId to part.command) // Bounded queue; overload cannot stall PTY draining.
                    }
                } else {
                    if (event is SessionEvent.Exited) {
                        controlStreams.remove(sessionId)?.finish()?.takeIf { it.isNotEmpty() }?.let {
                            terminalStates.mutex.withLock { terminalStates.output(sessionId, it) }
                            mutableEvents.emit(SessionEvent.Output(sessionId, it))
                        }
                        keyboardFlags.remove(sessionId)
                        terminalStates.mutex.withLock { terminalStates.exited(sessionId, event.exitCode) }
                    }
                    mutableEvents.emit(event)
                }
            }
        }
        val stateJob = scope.launch {
            controller.connectionState.collect { state ->
                if (state == SessionConnectionState.DISCONNECTED) {
                    owners.entries.removeAll { it.value == key }
                }
            }
        }
        registrations[key] = Registration(controller, listOf(eventJob, stateJob))
    }

    fun isRegistered(runtime: SessionRuntime, storageId: String = "legacy"): Boolean =
        registrations.containsKey(StorageKey(runtime, storageId))

    /** Excludes creation in every runtime while shared workspace trees are replaced. */
    suspend fun migrateIfAllIdle(runtime: SessionRuntime, migrate: suspend () -> Unit) = mutationLocks.withAll {
        listMutex.withLock {
            check(registrations.values.all { it.controller.list().getOrThrow().isEmpty() }) {
                "가속 형식 이전 전에 앱의 모든 터미널 세션을 종료하세요. 작업 폴더를 사용하는 파일 작업도 마쳐야 합니다"
            }
            val key = StorageKey(runtime)
            registrations.remove(key)?.jobs?.forEach(Job::cancel)
            owners.entries.removeAll { it.value == key }
            lastKnownSessions.remove(key)
        }
        migrate()
    }

    suspend fun removeIfIdle(runtime: SessionRuntime, remove: suspend () -> Unit) = mutationLocks.withStorage(runtime) {
        listMutex.withLock {
            val key = StorageKey(runtime)
            val registration = registrations[key]
            check(registration?.controller?.list()?.getOrThrow().orEmpty().isEmpty()) {
                "이 배포판의 모든 세션을 종료한 뒤 제거 또는 환경 보완을 실행하세요."
            }
            registrations.remove(key)?.jobs?.forEach(Job::cancel)
            owners.entries.removeAll { it.value == key }
            lastKnownSessions.remove(key)
        }
        // Long filesystem/package work must not block listing other runtimes' sessions.
        remove()
    }

    /** Strict list failures block deletion; disconnected AVF is never assumed idle. */
    suspend fun deleteStorageIfIdle(runtime: SessionRuntime, storageId: String, remove: suspend (release: suspend () -> Unit) -> Unit) =
        mutationLocks.withStorage(runtime, storageId) {
            val key = StorageKey(runtime, storageId)
            val registration = registrations[key]
            check(registration?.controller?.list()?.getOrThrow().orEmpty().none { it.state != dev.terminal.protocol.SessionStatus.EXITED }) { "Storage has active sessions" }
            val release: suspend () -> Unit = {
                val removedIds = owners.filterValues { it == key }.keys.toList()
                terminalStates.mutex.withLock { removedIds.forEach(terminalStates::remove) }
                registrations.remove(key)?.let { entry ->
                    entry.jobs.forEach(Job::cancel)
                    entry.controller.close()
                }
                owners.entries.removeAll { it.value == key }
                lastKnownSessions.remove(key)
                Unit
            }
            remove(release)
            release()
        }

    suspend fun create(spec: SessionLaunchSpec, rows: UShort, cols: UShort,
        runtimeEnvironment: Map<String, String> = emptyMap()): Result<SessionDescriptor> =
        createResolved(StorageKey(spec.runtime, spec.storageId), spec.shell, spec.workingDirectory,
            dev.terminal.runtime.notifications.shellActivityEnvironment(spec.shell,
                spec.environment + runtimeEnvironment) + (SessionCommand.KEY to SessionCommand.encode(spec.arguments())),
            rows, cols, initialPalette(spec)).onSuccess { recordLaunch(it.id, spec) }

    /** Compatibility entry point: direct runtime requests still resolve a built-in profile. */
    suspend fun create(runtime: SessionRuntime, shell: String, cwd: String,
        env: Map<String, String> = emptyMap(), rows: UShort, cols: UShort,
        storageId: String = "legacy"): Result<SessionDescriptor> = runCatching {
        val requestedArgv = SessionCommand.decode(env)
        val argv = requestedArgv?.let {
            if (it.first().startsWith('/')) it else listOf(shell, "-c", "exec \"\$@\"", shell) + it
        }
        val profile = SessionProfile(SessionProfile.builtInId(runtime), runtime.wireName, runtime,
            argv?.first() ?: shell, argv?.drop(1) ?: if (runtime != SessionRuntime.AVF && env["DROID_LOGIN_SHELL"] != "false") listOf("-l") else emptyList(),
            cwd, env.filterKeys { !it.startsWith("DROID_") && !it.startsWith("TERMINAL_USB_") && it != "KITTY_WINDOW_ID" },
            storageId = storageId)
        create(profile.resolve(), rows, cols, env.filterKeys { it != SessionCommand.KEY }).getOrThrow()
    }

    private suspend fun createResolved(
        key: StorageKey,
        shell: String,
        cwd: String,
        env: Map<String, String> = emptyMap(),
        rows: UShort,
        cols: UShort,
        palette: java.util.Properties,
    ): Result<SessionDescriptor> = mutationLocks.withStorage(key.runtime, key.storageId) {
        runCatching { beforeCreate() }.exceptionOrNull()?.let { return@withStorage Result.failure(it) }
        controller(key).fold(
            onSuccess = { controller -> terminalStates.mutex.withLock { controller.create(shell, cwd, env, rows, cols).onSuccess { created ->
                terminalStates.create(created.id, cols.toInt(), rows.toInt())
                terminalStates.palette(created.id, palette)
                if (key.runtime == SessionRuntime.AVF) controller.attach(created.id).getOrThrow()
                owners[created.id] = key
                lastKnownSessions.compute(key) { _, sessions -> sessions.orEmpty().filterNot { it.id == created.id } + created }
            } } },
            onFailure = { Result.failure(it) },
        )
    }

    override suspend fun create(
        shell: String,
        cwd: String,
        env: Map<String, String>,
        rows: UShort,
        cols: UShort,
    ): Result<SessionDescriptor> = create(SessionRuntime.NATIVE, shell, cwd, env, rows, cols)

    fun sessionMemory(): Map<String, Long?> = measureSessionMemory(
        registrations.values.flatMap { (it.controller as? SessionMemorySource)?.memoryRoots().orEmpty().entries }
            .associate { it.key to it.value },
    )

    override suspend fun list(): Result<List<SessionDescriptor>> = runCatching {
        listMutex.withLock {
            // Preserve runtime order for equal creation timestamps, independent
            // of ConcurrentHashMap iteration order or backend registration timing.
            registrations.entries.sortedWith(compareBy({ it.key.runtime.ordinal }, { it.key.storageId }))
                .flatMap { (key, registration) ->
                registration.controller.list().fold(
                    onSuccess = { current ->
                        val currentIds = current.mapTo(mutableSetOf()) { it.id }
                        owners.entries.removeAll { (sessionId, owner) -> owner == key && sessionId !in currentIds }
                        current.forEach { owners[it.id] = key }
                        current.also { lastKnownSessions[key] = it }
                    },
                    // A transport/list failure is not evidence that the PTY owner
                    // destroyed its sessions. Keep the last authoritative view for
                    // this runtime until a successful list proves otherwise.
                    onFailure = { lastKnownSessions[key].orEmpty() },
                )
            }.sortedBy { it.createdAtMs }
        }
    }

    override suspend fun attach(sessionId: String): Result<SessionDescriptor> = route(sessionId) { it.attach(sessionId) }
    override suspend fun detach(sessionId: String): Result<Unit> = route(sessionId) { it.detach(sessionId) }
    override suspend fun resize(sessionId: String, rows: UShort, cols: UShort): Result<SessionDescriptor> =
        terminalStates.mutex.withLock {
            route(sessionId) { it.resize(sessionId, rows, cols) }.onSuccess {
                terminalStates.resize(sessionId, cols.toInt(), rows.toInt())
            }
        }

    suspend fun configureTerminal(sessionId: String, rows: Int, cols: Int, cellWidth: Int, cellHeight: Int,
        palette: java.util.Properties, sink: RuntimeTerminalState.Sink? = null) = terminalStates.mutex.withLock {
        require(rows in 1..65535 && cols in 1..65535 && cellWidth in 0..65535 && cellHeight in 0..65535)
        if (!terminalStates.sameDimensions(sessionId, cols, rows))
            route(sessionId) { it.resize(sessionId, rows.toUShort(), cols.toUShort()) }.getOrThrow()
        terminalStates.resize(sessionId, cols, rows, cellWidth, cellHeight)
        terminalStates.palette(sessionId, palette)
        if (sink != null) terminalStates.subscribe(sessionId, sink)
    }
    override suspend fun write(sessionId: String, bytes: ByteArray): Result<Int> = route(sessionId) { onInput(sessionId, bytes); it.write(sessionId, bytes) }
    override suspend fun terminate(sessionId: String, signal: Int): Result<Unit> {
        val key = ownerKey(sessionId) ?: return Result.failure(NoSuchElementException("Session not found"))
        return controller(key).fold(
            onSuccess = { owner -> owner.terminate(sessionId, signal) },
            onFailure = { Result.failure(it) },
        ).onSuccess {
            lastKnownSessions.computeIfPresent(key) { _, sessions -> sessions.filterNot { it.id == sessionId } }
            owners.remove(sessionId, key)
            controlStreams.remove(sessionId)
            keyboardFlags.remove(sessionId)
            terminalStates.mutex.withLock { terminalStates.remove(sessionId) }
        }
    }

    fun runtimeOf(sessionId: String): SessionRuntime? = owners[sessionId]?.runtime ?: SessionRuntime.fromSessionId(sessionId)
    fun storageOf(sessionId: String): String? = ownerKey(sessionId)?.storageId
    private fun ownerKey(sessionId: String): StorageKey? = owners[sessionId]
        ?: SessionRuntime.fromSessionId(sessionId)?.let { StorageKey(it) }

    override fun close() {
        registrations.values.forEach { registration ->
            registration.jobs.forEach(Job::cancel)
            registration.controller.close()
        }
        commands.close()
        controlStreams.clear()
        keyboardFlags.clear()
        terminalStates.clear()
        registrations.clear()
        owners.clear()
        lastKnownSessions.clear()
        mutableConnection.value = SessionConnectionState.DISCONNECTED
        scope.cancel()
    }

    private fun controller(key: StorageKey): Result<SessionController> = registrations[key]?.controller?.let(Result.Companion::success)
        ?: Result.failure(IllegalStateException("${key.runtime.wireName} storage is not ready"))

    private suspend fun <T> route(sessionId: String, operation: suspend (SessionController) -> Result<T>): Result<T> {
        val key = ownerKey(sessionId) ?: return Result.failure(NoSuchElementException("Session not found"))
        return controller(key).fold(onSuccess = { operation(it) }, onFailure = { Result.failure(it) })
    }
}
