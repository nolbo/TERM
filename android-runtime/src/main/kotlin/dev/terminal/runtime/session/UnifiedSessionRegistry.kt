package dev.terminal.runtime.session

import dev.terminal.protocol.SessionConnectionState
import dev.terminal.protocol.SessionController
import dev.terminal.protocol.SessionDescriptor
import dev.terminal.protocol.SessionEvent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
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
    private val remoteCommand: (suspend (String, String) -> ByteArray?)? = null,
) : SessionController {
    private data class Registration(val controller: SessionController, val jobs: List<Job>)

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

    private val registrations = ConcurrentHashMap<SessionRuntime, Registration>()
    private val owners = ConcurrentHashMap<String, SessionRuntime>()
    private val lastKnownSessions = ConcurrentHashMap<SessionRuntime, List<SessionDescriptor>>()
    private val listMutex = Mutex()
    private val mutationMutexes = SessionRuntime.entries.associateWith { Mutex() }
    private val mutableEvents = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 128)
    private val mutableConnection = MutableStateFlow(SessionConnectionState.CONNECTED)

    override val events: SharedFlow<SessionEvent> = mutableEvents.asSharedFlow()
    override val connectionState: StateFlow<SessionConnectionState> = mutableConnection.asStateFlow()

    fun register(runtime: SessionRuntime, controller: SessionController) {
        registrations.remove(runtime)?.jobs?.forEach(Job::cancel)
        val eventJob = scope.launch {
            controller.events.collect { event ->
                val sessionId = when (event) {
                    is SessionEvent.Output -> event.sessionId
                    is SessionEvent.Exited -> event.sessionId
                }
                owners.putIfAbsent(sessionId, runtime)
                if (event is SessionEvent.Output && !event.replay) {
                    val stream = controlStreams.getOrPut(sessionId) { com.termux.terminal.KittyControlStream() }
                    val parts = stream.accept(event.bytes)
                    keyboardFlags[sessionId] = stream.keyboard().flags()
                    for (part in parts) {
                        if (part.bytes != null) mutableEvents.emit(SessionEvent.Output(sessionId, part.bytes))
                        else commands.trySend(sessionId to part.command) // Bounded queue; overload cannot stall PTY draining.
                    }
                } else {
                    if (event is SessionEvent.Exited) {
                        controlStreams.remove(sessionId)?.finish()?.takeIf { it.isNotEmpty() }?.let {
                            mutableEvents.emit(SessionEvent.Output(sessionId, it))
                        }
                        keyboardFlags.remove(sessionId)
                    }
                    mutableEvents.emit(event)
                }
            }
        }
        val stateJob = scope.launch {
            controller.connectionState.collect { state ->
                if (state == SessionConnectionState.DISCONNECTED) {
                    owners.entries.removeAll { it.value == runtime }
                }
            }
        }
        registrations[runtime] = Registration(controller, listOf(eventJob, stateJob))
    }

    fun isRegistered(runtime: SessionRuntime): Boolean = registrations.containsKey(runtime)

    /** Excludes creation in every runtime while shared workspace trees are replaced. */
    suspend fun migrateIfAllIdle(runtime: SessionRuntime, migrate: suspend () -> Unit) {
        val held = mutableListOf<Mutex>()
        try {
            for (mutex in mutationMutexes.values) { mutex.lock(); held.add(mutex) }
            listMutex.withLock {
                check(registrations.values.all { it.controller.list().getOrThrow().isEmpty() }) {
                    "가속 형식 이전 전에 앱의 모든 터미널 세션을 종료하세요. 작업 폴더를 사용하는 파일 작업도 마쳐야 합니다"
                }
                registrations.remove(runtime)?.jobs?.forEach(Job::cancel)
                owners.entries.removeAll { it.value == runtime }
                lastKnownSessions.remove(runtime)
            }
            migrate()
        } finally { held.asReversed().forEach { it.unlock() } }
    }

    suspend fun removeIfIdle(runtime: SessionRuntime, remove: suspend () -> Unit) = mutationMutexes.getValue(runtime).withLock {
        listMutex.withLock {
            val registration = registrations[runtime]
            check(registration?.controller?.list()?.getOrThrow().orEmpty().isEmpty()) {
                "이 배포판의 모든 세션을 종료한 뒤 제거 또는 환경 보완을 실행하세요."
            }
            registrations.remove(runtime)?.jobs?.forEach(Job::cancel)
            owners.entries.removeAll { it.value == runtime }
            lastKnownSessions.remove(runtime)
        }
        // Long filesystem/package work must not block listing other runtimes' sessions.
        remove()
    }

    suspend fun create(
        runtime: SessionRuntime,
        shell: String,
        cwd: String,
        env: Map<String, String> = emptyMap(),
        rows: UShort,
        cols: UShort,
    ): Result<SessionDescriptor> = mutationMutexes.getValue(runtime).withLock {
        runCatching { beforeCreate() }.exceptionOrNull()?.let { return@withLock Result.failure(it) }
        controller(runtime).fold(
            onSuccess = { controller -> controller.create(shell, cwd, env, rows, cols).onSuccess { created ->
                owners[created.id] = runtime
                lastKnownSessions.compute(runtime) { _, sessions -> sessions.orEmpty().filterNot { it.id == created.id } + created }
            } },
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

    override suspend fun list(): Result<List<SessionDescriptor>> = runCatching {
        listMutex.withLock {
            SessionRuntime.entries.flatMap { runtime ->
                val registration = registrations[runtime] ?: return@flatMap emptyList()
                registration.controller.list().fold(
                    onSuccess = { current ->
                        val currentIds = current.mapTo(mutableSetOf()) { it.id }
                        owners.entries.removeAll { (sessionId, owner) -> owner == runtime && sessionId !in currentIds }
                        current.forEach { owners[it.id] = runtime }
                        current.also { lastKnownSessions[runtime] = it }
                    },
                    // A transport/list failure is not evidence that the PTY owner
                    // destroyed its sessions. Keep the last authoritative view for
                    // this runtime until a successful list proves otherwise.
                    onFailure = { lastKnownSessions[runtime].orEmpty() },
                )
            }.sortedBy { it.createdAtMs }
        }
    }

    override suspend fun attach(sessionId: String): Result<SessionDescriptor> = route(sessionId) { it.attach(sessionId) }
    override suspend fun detach(sessionId: String): Result<Unit> = route(sessionId) { it.detach(sessionId) }
    override suspend fun resize(sessionId: String, rows: UShort, cols: UShort): Result<SessionDescriptor> =
        route(sessionId) { it.resize(sessionId, rows, cols) }
    override suspend fun write(sessionId: String, bytes: ByteArray): Result<Int> = route(sessionId) { it.write(sessionId, bytes) }
    override suspend fun terminate(sessionId: String, signal: Int): Result<Unit> {
        val runtime = runtimeOf(sessionId) ?: return Result.failure(NoSuchElementException("Session not found"))
        return controller(runtime).fold(
            onSuccess = { owner -> owner.terminate(sessionId, signal) },
            onFailure = { Result.failure(it) },
        ).onSuccess {
            lastKnownSessions.computeIfPresent(runtime) { _, sessions -> sessions.filterNot { it.id == sessionId } }
            owners.remove(sessionId, runtime)
            controlStreams.remove(sessionId)
            keyboardFlags.remove(sessionId)
        }
    }

    fun runtimeOf(sessionId: String): SessionRuntime? = owners[sessionId] ?: SessionRuntime.fromSessionId(sessionId)

    override fun close() {
        registrations.values.forEach { registration ->
            registration.jobs.forEach(Job::cancel)
            registration.controller.close()
        }
        commands.close()
        controlStreams.clear()
        keyboardFlags.clear()
        registrations.clear()
        owners.clear()
        lastKnownSessions.clear()
        mutableConnection.value = SessionConnectionState.DISCONNECTED
        scope.cancel()
    }

    private fun controller(runtime: SessionRuntime): Result<SessionController> = registrations[runtime]?.controller?.let(Result.Companion::success)
        ?: Result.failure(IllegalStateException("${runtime.wireName} runtime is not ready"))

    private suspend fun <T> route(sessionId: String, operation: suspend (SessionController) -> Result<T>): Result<T> {
        val runtime = runtimeOf(sessionId) ?: return Result.failure(NoSuchElementException("Session not found"))
        return controller(runtime).fold(onSuccess = { operation(it) }, onFailure = { Result.failure(it) })
    }
}
