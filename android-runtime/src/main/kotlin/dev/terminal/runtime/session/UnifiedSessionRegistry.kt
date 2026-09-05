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
) : SessionController {
    private data class Registration(val controller: SessionController, val jobs: List<Job>)

    private val registrations = ConcurrentHashMap<SessionRuntime, Registration>()
    private val owners = ConcurrentHashMap<String, SessionRuntime>()
    private val lastKnownSessions = ConcurrentHashMap<SessionRuntime, List<SessionDescriptor>>()
    private val listMutex = Mutex()
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
                mutableEvents.emit(event)
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

    suspend fun create(
        runtime: SessionRuntime,
        shell: String,
        cwd: String,
        env: Map<String, String> = emptyMap(),
        rows: UShort,
        cols: UShort,
    ): Result<SessionDescriptor> = controller(runtime).fold(
        onSuccess = { controller -> controller.create(shell, cwd, env, rows, cols).onSuccess { created ->
            owners[created.id] = runtime
            lastKnownSessions.compute(runtime) { _, sessions -> sessions.orEmpty().filterNot { it.id == created.id } + created }
        } },
        onFailure = { Result.failure(it) },
    )

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
        }
    }

    fun runtimeOf(sessionId: String): SessionRuntime? = owners[sessionId] ?: SessionRuntime.fromSessionId(sessionId)

    override fun close() {
        registrations.values.forEach { registration ->
            registration.jobs.forEach(Job::cancel)
            registration.controller.close()
        }
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
