package dev.terminal.runtime.session

import dev.terminal.protocol.SessionConnectionState
import dev.terminal.protocol.SessionController
import dev.terminal.protocol.SessionDescriptor
import dev.terminal.protocol.SessionEvent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Routes session operations without exposing backend details to Binder or the renderer.
 * Controllers own PTYs; this registry owns only routing and merged event subscriptions.
 */
class UnifiedSessionRegistry(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val beforeCreate: () -> Unit = {},
    private val onDisconnected: (SessionRuntime, String) -> Unit = { _, _ -> },
    private val onInput: (String, ByteArray) -> Unit = { _, _ -> },
    private val onOutputActivity: (String, List<String>) -> Unit = { _, _ -> },
    private val recordLaunch: (String, SessionLaunchSpec) -> Unit = { _, _ -> },
    private val restoreLaunch: (String) -> SessionLaunchSpec? = { null },
    private val onRemoved: (List<String>) -> Unit = {},
    private val remoteCommand: (suspend (String, String) -> ByteArray?)? = null,
    val terminalStates: RuntimeTerminalState = RuntimeTerminalState(),
    private val initialPalette: (SessionLaunchSpec) -> java.util.Properties = { spec ->
        spec.palette?.let(TerminalPalette::asProperties) ?: java.util.Properties()
    },
) : SessionController {
    private data class Registration(val controller: SessionController, val jobs: List<Job>, val inputOwner: Any,
        val terminalReplies: dev.terminal.runtime.lifecycle.OrderedSessionInput, val lifetime: Job,
        val creation: Mutex = Mutex(),
        val removed: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        var version: Long = 0, var query: Long = 0,
        var resourceJob: Job? = null, var resourceCheckedAt: Long = 0,
        var resourceUnsupported: Boolean = false, var resources: dev.terminal.protocol.GuestResourceUsage? = null)
    private data class StorageKey(val runtime: SessionRuntime, val storageId: String = "legacy")
    private class PendingResize(val key: StorageKey, val registration: Registration, val session: Any?,
        val state: Any?, val rows: Int, val cols: Int, val cellWidth: Int?, val cellHeight: Int?) {
        val applied = CompletableDeferred<Unit>()
    }

    private val controlStreams = ConcurrentHashMap<String, dev.terminal.emulator.KittyControlStream>()
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
    private val inputSessions = ConcurrentHashMap<String, Any>()
    private val environments = ConcurrentHashMap<StorageKey, Environment>()
    private val launches = ConcurrentHashMap<String, SessionLaunchSpec>()
    private val lastKnownSessions = ConcurrentHashMap<StorageKey, List<SessionDescriptor>>()
    private val listMutex = Mutex()
    private val mutationLocks = dev.terminal.runtime.lifecycle.StoragePreparationLocks()
    private val geometryLocks = SessionOperationLocks()
    private val pendingResizes = ConcurrentHashMap<String, PendingResize>()
    private val mutableEvents = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 128)
    private val mutableConnection = MutableStateFlow(SessionConnectionState.CONNECTED)

    override val events: SharedFlow<SessionEvent> = mutableEvents.asSharedFlow()
    override val connectionState: StateFlow<SessionConnectionState> = mutableConnection.asStateFlow()

    fun register(environment: Environment, controller: SessionController) {
        environments[StorageKey(environment.sessionRuntime, environment.storage.id)] = environment
        register(environment.sessionRuntime, controller, environment.storage.id)
    }

    fun register(runtime: SessionRuntime, controller: SessionController, storageId: String = "legacy") {
        val key = StorageKey(runtime, storageId)
        val previous = registrations.remove(key)
        failResizes(key)
        previous?.let { old ->
            old.resourceJob?.cancel()
            old.jobs.forEach(Job::cancel)
            if (old.controller !== controller) old.controller.close()
        }
        val jobs = mutableListOf<Job>()
        val inputOwner = if (previous?.controller === controller) previous.inputOwner else Any()
        val replyJob = SupervisorJob(scope.coroutineContext[Job])
        val terminalReplies = dev.terminal.runtime.lifecycle.OrderedSessionInput(
            CoroutineScope(scope.coroutineContext + replyJob), onFailure = { _, failure ->
                dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("terminal.reply.failed", error = failure)
            })
        replyJob.invokeOnCompletion { terminalReplies.close() }
        jobs += replyJob
        val registration = Registration(controller, jobs, inputOwner, terminalReplies, replyJob)
        registrations[key] = registration
        jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            controller.events.collect { event ->
                if (registrations[key] !== registration) return@collect
                val sessionId = when (event) {
                    is SessionEvent.Output -> event.sessionId
                    is SessionEvent.Exited -> event.sessionId
                    is SessionEvent.Resized -> event.sessionId
                }
                if (sessionId in registration.removed) return@collect
                if (event is SessionEvent.Resized) {
                    pendingResizes[sessionId]?.takeIf { it === event.barrier }?.let { pending ->
                        applyResize(sessionId, pending, event.rows.toInt(), event.cols.toInt())
                    }
                    return@collect
                }
                owners.putIfAbsent(sessionId, key)
                if (sessionId in registration.removed) {
                    owners.remove(sessionId, key)
                    return@collect
                }
                if (event is SessionEvent.Output && !event.replay) {
                    // CREATE may deliver its first output before the caller resumes. Only
                    // that new session waits for initialization; existing output keeps flowing.
                    if (terminalStates.identity(sessionId) == null) registration.creation.withLock { }
                    if (registrations[key] !== registration || sessionId in registration.removed) return@collect
                    val stream = controlStreams.getOrPut(sessionId) { dev.terminal.emulator.KittyControlStream() }
                    val parts = stream.accept(event.bytes)
                    keyboardFlags[sessionId] = stream.keyboard().flags()
                    for (part in parts) {
                        if (part.bytes != null) {
                            val (state, replies, activity) = terminalStates.withSession(sessionId) {
                                if (sessionId in registration.removed) return@withSession Triple(null, emptyList<ByteArray>(), emptyList<String>())
                                val replies = terminalStates.output(sessionId, part.bytes)
                                Triple(terminalStates.identity(sessionId), replies, terminalStates.takeActivity(sessionId))
                            }
                            if (state != null) onOutputActivity(sessionId, activity)
                            if (key.runtime == SessionRuntime.AVF) {
                                enqueueTerminalReplies(key, registration, sessionId, state, replies)
                            } else replies.forEach { reply ->
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
                        listMutex.withLock {
                            if (sessionId in registration.removed) return@collect
                            if (registrations[key] === registration) {
                                registration.version++
                                lastKnownSessions.computeIfPresent(key) { _, sessions -> sessions.map {
                                    if (it.id == sessionId) it.copy(state = dev.terminal.protocol.SessionStatus.EXITED, exitCode = event.exitCode) else it
                                } }
                            }
                        }
                        inputSessions.remove(sessionId)
                        failResize(sessionId)
                        controlStreams.remove(sessionId)?.finish()?.takeIf { it.isNotEmpty() }?.let {
                            val activity = terminalStates.withSession(sessionId) {
                                terminalStates.output(sessionId, it)
                                terminalStates.takeActivity(sessionId)
                            }
                            onOutputActivity(sessionId, activity)
                            mutableEvents.emit(SessionEvent.Output(sessionId, it))
                        }
                        keyboardFlags.remove(sessionId)
                        terminalStates.withSession(sessionId) { terminalStates.exited(sessionId, event.exitCode) }
                    }
                    mutableEvents.emit(event)
                }
            }
        }
        jobs += scope.launch {
            controller.connectionState.collect { state ->
                if (state == SessionConnectionState.DISCONNECTED) {
                    failResizes(key)
                    // Transport loss is not session loss; preserve sibling owners and checkpoints.
                    onDisconnected(key.runtime, key.storageId)
                }
            }
        }
    }

    private fun enqueueTerminalReplies(key: StorageKey, registration: Registration, sessionId: String,
        state: Any?, replies: List<ByteArray>) {
        if (replies.isEmpty() || state == null) return
        val session = inputSessions[sessionId] ?: return
        val current = { registrations[key] === registration && owners[sessionId] == key &&
                inputSessions[sessionId] === session && terminalStates.identity(sessionId) === state &&
                registration.controller.connectionState.value != SessionConnectionState.DISCONNECTED }
        val target = SessionInputTarget(registration.inputOwner, sessionId, current = current,
            // Terminal-generated replies are not user input or a shell-activity signal.
            deliver = reply@{ bytes ->
                // Already buffered output can contain a query while raw recovery
                // runs. Keep its unsent reply in this bounded lane, not on the
                // receive collector, so a TUI is not left waiting forever.
                while (current()) {
                    registration.controller.connectionState.first { it != SessionConnectionState.RECOVERING }
                    if (!current()) break
                    val result = registration.controller.write(sessionId, bytes)
                    if (result.exceptionOrNull() !is dev.terminal.protocol.ProtocolRecoveringException) return@reply result
                    // Recovery can begin between the state check and admission.
                    // This specific rejection guarantees no bytes were sent.
                    kotlinx.coroutines.yield()
                }
                Result.failure(IllegalStateException("Terminal reply destination changed"))
            })
        val bytes = java.io.ByteArrayOutputStream().apply { replies.forEach { write(it) } }.toByteArray()
        // Never await a WRITE RPC from the output collector: its acknowledgement
        // shares the reader that backpressures on this collector's event buffer.
        val admission = registration.terminalReplies.offerResult(target, bytes)
        if (admission == dev.terminal.runtime.lifecycle.OrderedSessionInput.Admission.FULL) {
            dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("terminal.reply.overload")
            // Fail only this transport so normal owner recovery can run. Do not
            // leave a TUI silently waiting for a reply or stop its VM/PTY.
            registration.controller.close()
        }
    }

    fun isRegistered(runtime: SessionRuntime, storageId: String = "legacy"): Boolean =
        registrations.containsKey(StorageKey(runtime, storageId))

    fun disconnectedStorages(runtime: SessionRuntime): List<String> = registrations.entries
        .filter { it.key.runtime == runtime && it.value.controller.connectionState.value == SessionConnectionState.DISCONNECTED }
        .map { it.key.storageId }

    fun hasSessionOwner(sessionId: String): Boolean = owners[sessionId]?.let(registrations::containsKey) == true

    /** Read-only UI projection. A disconnected owner is not evidence of session death. */
    fun cachedSessions(): List<SessionDescriptor> = lastKnownSessions.entries
        .sortedWith(compareBy({ it.key.runtime.ordinal }, { it.key.storageId }))
        .flatMap { it.value }.sortedBy { it.createdAtMs }

    /** Unlike aggregate list(), exposes this owner's failure instead of its cached sessions. */
    suspend fun refreshEnvironment(environment: Environment): Result<List<SessionDescriptor>> {
        val key = StorageKey(environment.sessionRuntime, environment.storage.id)
        val registration = registrations[key] ?: return Result.failure(IllegalStateException("Environment is not connected"))
        return refreshRegistration(key, registration)
    }

    private suspend fun refreshRegistration(key: StorageKey, registration: Registration): Result<List<SessionDescriptor>> =
        registration.creation.withLock {
            val ticket = listMutex.withLock { registration.version to ++registration.query }
            val current = registration.controller.list().getOrElse { return Result.failure(it) }
            var removed = emptyList<String>()
            val result = listMutex.withLock {
                if (registrations[key] !== registration) Result.failure(IllegalStateException("Environment owner changed"))
                else if (registration.version != ticket.first || registration.query != ticket.second)
                    Result.success(lastKnownSessions[key].orEmpty())
                else {
                    val ids = current.mapTo(mutableSetOf()) { it.id }
                    removed = lastKnownSessions[key].orEmpty().map { it.id }.filter { it !in ids }
                    registration.removed.addAll(removed)
                    Result.success(rememberSessions(key, current))
                }
            }
            // RPCs and per-session cleanup never hold the global publication lock.
            discardSessions(removed)
            result.getOrNull().orEmpty().forEach { session -> terminalStates.withSession(session.id) {
                if (registrations[key] === registration && owners[session.id] == key && session.id !in registration.removed) {
                    terminalStates.create(session.id, session.cols.toInt(), session.rows.toInt())
                    if (session.state == dev.terminal.protocol.SessionStatus.EXITED) terminalStates.exited(session.id, session.exitCode)
                }
            } }
            result
        }

    private suspend fun discardSessions(ids: List<String>) {
        val discarded = mutableListOf<String>()
        ids.forEach { id -> terminalStates.withSession(id) {
            // A replacement registration may already have reclaimed this identity.
            if (owners.containsKey(id)) return@withSession
            terminalStates.remove(id)
            launches.remove(id); controlStreams.remove(id); keyboardFlags.remove(id)
            discarded.add(id)
        } }
        if (discarded.isNotEmpty()) onRemoved(discarded)
    }

    /** Caller also holds its Environment preparation lease; unknown VM state preserves everything. */
    suspend fun forgetStoppedEnvironment(environment: Environment, verifyStopped: suspend () -> Unit): List<String> =
        mutationLocks.withStorage(environment.sessionRuntime, environment.storage.id) {
            verifyStopped()
            val key = StorageKey(environment.sessionRuntime, environment.storage.id)
            val registration = registrations[key]
            registration?.let {
                it.resourceJob?.cancel()
                it.jobs.forEach(Job::cancel)
                runCatching { it.controller.close() }
                it.jobs.joinAll()
            }
            val lost = listMutex.withLock {
                check(registrations[key] === registration) { "Environment owner changed" }
                val lost = (owners.filterValues { it == key }.keys + lastKnownSessions[key].orEmpty().map { it.id }).toList()
                registrations.remove(key)
                forgetOwners(key)
                environments.remove(key)
                lastKnownSessions.remove(key)
                lost
            }
            discardSessions(lost)
            lost
        }

    /** An idle stop and a proven abnormal stop share the same session teardown. */
    suspend fun stopEnvironmentIfIdle(environment: Environment, stop: suspend () -> Unit): List<String> =
        forgetStoppedEnvironment(environment) {
            check(verifiedSessions(environment).getOrThrow().none { it.state != dev.terminal.protocol.SessionStatus.EXITED }) {
                "Storage has active sessions"
            }
            stop()
        }

    private fun rememberSessions(key: StorageKey, current: List<SessionDescriptor>): List<SessionDescriptor> {
        val currentIds = current.mapTo(mutableSetOf()) { it.id }
        forgetOwners(key, currentIds)
        current.forEach {
            val previous = owners.put(it.id, key)
            if (it.state == dev.terminal.protocol.SessionStatus.EXITED) { inputSessions.remove(it.id); failResize(it.id) }
            else if (previous != key) inputSessions[it.id] = Any()
            else inputSessions.computeIfAbsent(it.id) { Any() }
            if (!launches.containsKey(it.id)) restoreLaunch(it.id)?.let { spec -> launches[it.id] = spec }
        }
        lastKnownSessions[key] = current
        return current
    }

    private fun forgetOwners(key: StorageKey, retained: Set<String> = emptySet()) {
        owners.entries.removeAll { (id, owner) ->
            if (owner == key && id !in retained) { inputSessions.remove(id); failResize(id); true } else false
        }
    }

    /** Proves idleness from the owner, never a cached list, and excludes creation during commit.
     * Existing controllers retain their paths and AVF instance; only their idle registration changes.
     */
    suspend fun adoptLegacyStorageIfIdle(runtime: SessionRuntime, adopt: () -> String): Boolean = mutationLocks.withAll {
        val old = StorageKey(runtime)
        val registration = registrations[old]
        val sessions = registration?.controller?.list()?.getOrThrow().orEmpty()
        if (sessions.any { it.state != dev.terminal.protocol.SessionStatus.EXITED }) return@withAll false
        listMutex.withLock {
            check(registrations[old] === registration) { "Environment owner changed" }
            val next = StorageKey(runtime, adopt())
            check(next != old && !registrations.containsKey(next)) { "Invalid migration target" }
            if (registration != null) {
                environments.remove(old)?.let { environments[next] = it.copy(storage = EnvironmentStorage(next.storageId)) }
                registrations.remove(old)
                registration.jobs.forEach(Job::cancel)
                register(runtime, registration.controller, next.storageId)
                owners.entries.filter { it.value == old }.forEach { owners[it.key] = next }
                lastKnownSessions.remove(old)?.let { lastKnownSessions[next] = it }
            }
            true
        }
    }

    /** Excludes creation in every runtime while shared workspace trees are replaced. */
    suspend fun migrateIfAllIdle(runtime: SessionRuntime, storageId: String = "legacy", migrate: suspend () -> Unit) = mutationLocks.withAll {
        val observed = registrations.toMap()
        check(coroutineScope { observed.values.map { async { it.controller.list().getOrThrow().isEmpty() } }.awaitAll().all { it } }) {
            "가속 형식 이전 전에 앱의 모든 터미널 세션을 종료하세요. 작업 폴더를 사용하는 파일 작업도 마쳐야 합니다"
        }
        listMutex.withLock {
            check(observed.size == registrations.size && observed.all { (key, value) -> registrations[key] === value }) { "Environment owner changed" }
            val key = StorageKey(runtime, storageId)
            registrations.remove(key)?.jobs?.forEach(Job::cancel)
            environments.remove(key)
            forgetOwners(key)
            lastKnownSessions.remove(key)
        }
        migrate()
    }

    suspend fun removeIfIdle(runtime: SessionRuntime, storageId: String = "legacy", remove: suspend () -> Unit) = mutationLocks.withStorage(runtime, storageId) {
        val key = StorageKey(runtime, storageId)
        val registration = registrations[key]
        check(registration?.controller?.list()?.getOrThrow().orEmpty().isEmpty()) {
            "이 배포판의 모든 세션을 종료한 뒤 제거 또는 환경 보완을 실행하세요."
        }
        listMutex.withLock {
            check(registrations[key] === registration) { "Environment owner changed" }
            registrations.remove(key)?.jobs?.forEach(Job::cancel)
            environments.remove(key)
            forgetOwners(key)
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
            check(registration == null || registration.controller.connectionState.value == SessionConnectionState.CONNECTED) { "Cannot verify environment sessions" }
            check(registration?.controller?.list()?.getOrThrow().orEmpty().none { it.state != dev.terminal.protocol.SessionStatus.EXITED }) { "Storage has active sessions" }
            val release: suspend () -> Unit = {
                val removedIds = owners.filterValues { it == key }.keys.toList()
                removedIds.forEach { id -> terminalStates.withSession(id) { terminalStates.remove(id) } }
                registrations.remove(key)?.let { entry ->
                    entry.jobs.forEach(Job::cancel)
                    entry.controller.close()
                }
                forgetOwners(key)
                removedIds.forEach(launches::remove)
                environments.remove(key)
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
            rows, cols, initialPalette(spec)).onSuccess { launches[it.id] = spec; recordLaunch(it.id, spec) }

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
        val executionEnvironment = environments[StorageKey(runtime, storageId)]
        create(if (executionEnvironment == null) profile.resolve() else profile.resolve(executionEnvironment), rows, cols,
            env.filterKeys { it != SessionCommand.KEY }).getOrThrow()
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
        val registration = registrations[key]
            ?: return@withStorage Result.failure(IllegalStateException("${key.runtime.wireName} storage is not ready"))
        runCatching {
            val created = registration.creation.withLock {
                val created = registration.controller.create(shell, cwd, env, rows, cols).getOrThrow()
                terminalStates.withSession(created.id) {
                    check(registrations[key] === registration) { "Environment owner changed" }
                    failResize(created.id)
                    terminalStates.create(created.id, cols.toInt(), rows.toInt())
                    terminalStates.palette(created.id, palette)
                }
                listMutex.withLock {
                    check(registrations[key] === registration) { "Environment owner changed" }
                    registration.version++
                    owners[created.id] = key
                    inputSessions[created.id] = Any()
                    lastKnownSessions.compute(key) { _, sessions -> sessions.orEmpty().filterNot { it.id == created.id } + created }
                }
                created
            }
            if (key.runtime == SessionRuntime.AVF) registration.controller.attach(created.id).getOrThrow()
            created
        }
    }

    override suspend fun create(
        shell: String,
        cwd: String,
        env: Map<String, String>,
        rows: UShort,
        cols: UShort,
    ): Result<SessionDescriptor> = create(SessionRuntime.NATIVE, shell, cwd, env, rows, cols)

    /** On-demand only. Never waits for a guest RPC from Binder or the UI. */
    fun avfResourceSnapshots(): org.json.JSONArray = org.json.JSONArray().apply {
        registrations.entries.filter { it.key.runtime == SessionRuntime.AVF }.forEach { (key, registration) ->
            synchronized(registration) {
                val now = System.nanoTime()
                if (!registration.resourceUnsupported && registration.resourceJob?.isActive != true &&
                    registration.controller.connectionState.value == SessionConnectionState.CONNECTED &&
                    (registration.resourceCheckedAt == 0L || now - registration.resourceCheckedAt >= 15_000_000_000L)) {
                    registration.resourceJob = CoroutineScope(scope.coroutineContext + registration.lifetime).launch(start = CoroutineStart.LAZY) {
                        val result = try { registration.controller.resourceUsage() }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (failure: Exception) { Result.failure(failure) }
                        synchronized(registration) {
                            registration.resources = result.getOrNull()
                            registration.resourceCheckedAt = System.nanoTime()
                            val error = result.exceptionOrNull()
                            registration.resourceUnsupported = error is UnsupportedOperationException ||
                                error is dev.terminal.protocol.GuestRequestException && error.error.code in setOf(
                                    dev.terminal.protocol.ProtocolErrorCode.UNSUPPORTED, dev.terminal.protocol.ProtocolErrorCode.INVALID_ARGUMENT)
                        }
                    }.also { it.start() }
                }
                val environment = environments[key]
                val json = org.json.JSONObject().put("id", environment?.id?.value ?: key.storageId)
                    .put("name", environment?.name ?: key.storageId).put("supported", !registration.resourceUnsupported)
                registration.resources?.takeIf {
                    registration.controller.connectionState.value == SessionConnectionState.CONNECTED &&
                        now - registration.resourceCheckedAt <= 60_000_000_000L
                }?.let { usage ->
                    json.put("total", usage.totalBytes).put("available", usage.availableBytes)
                        .put("swapTotal", usage.swapTotalBytes).put("swapFree", usage.swapFreeBytes)
                        .put("cpus", usage.cpuCount).put("loadMilli", usage.loadMilli)
                        .put("pressureMilli", usage.memoryPressureMilli)
                }
                put(json)
            }
        }
    }

    fun sessionMemory(sessionId: String): Map<String, Long?> = measureSessionMemory(
        registrations.values.flatMap { (it.controller as? SessionMemorySource)?.memoryRoots().orEmpty().entries }
            .filter { it.key == sessionId }.associate { it.key to it.value },
    )

    override suspend fun list(): Result<List<SessionDescriptor>> = runCatching {
        coroutineScope {
            registrations.entries.map { (key, registration) -> async { refreshRegistration(key, registration) } }.awaitAll()
        }
        // Failed owners retain their last authoritative view. Each owner's RPC
        // runs outside the publication lock and commits independently.
        cachedSessions()
    }

    override suspend fun attach(sessionId: String): Result<SessionDescriptor> = route(sessionId) { it.attach(sessionId) }
    override suspend fun detach(sessionId: String): Result<Unit> = route(sessionId) { it.detach(sessionId) }
    override suspend fun resize(sessionId: String, rows: UShort, cols: UShort): Result<SessionDescriptor> =
        runCatching { geometryLocks.withSession(sessionId) {
            resizeTerminal(sessionId, rows.toInt(), cols.toInt())
        } }

    private fun resizeIsCurrent(sessionId: String, pending: PendingResize): Boolean =
        registrations[pending.key] === pending.registration && ownerKey(sessionId) == pending.key &&
            inputSessions[sessionId] === pending.session && terminalStates.identity(sessionId) === pending.state &&
            pending.registration.controller.connectionState.value != SessionConnectionState.DISCONNECTED

    private suspend fun applyResize(sessionId: String, pending: PendingResize, rows: Int, cols: Int) {
        terminalStates.withSession(sessionId) {
            if (pendingResizes[sessionId] !== pending) return@withSession
            runCatching {
                check(resizeIsCurrent(sessionId, pending) && rows == pending.rows && cols == pending.cols) {
                    "Resize destination changed"
                }
                terminalStates.resize(sessionId, cols, rows, pending.cellWidth, pending.cellHeight)
            }.fold(onSuccess = { pending.applied.complete(Unit) }, onFailure = { pending.applied.completeExceptionally(it) })
        }
    }

    private suspend fun resizeTerminal(sessionId: String, rows: Int, cols: Int,
        cellWidth: Int? = null, cellHeight: Int? = null): SessionDescriptor {
        require(rows in 1..65535 && cols in 1..65535)
        val key = ownerKey(sessionId) ?: error("Session not found")
        val registration = registrations[key] ?: error("Environment is not connected")
        val pending = terminalStates.withSession(sessionId) {
            PendingResize(key, registration, inputSessions[sessionId], terminalStates.identity(sessionId),
                rows, cols, cellWidth, cellHeight).also { pendingResizes[sessionId] = it }
        }
        try {
            val resized = registration.controller.resizeOrdered(sessionId, rows.toUShort(), cols.toUShort(), pending).getOrThrow()
            check(resized.id == sessionId && resized.rows.toInt() == rows && resized.cols.toInt() == cols) { "Invalid resize response" }
            if (!registration.controller.supportsOrderedResize) applyResize(sessionId, pending, rows, cols)
            pending.applied.await()
            check(resizeIsCurrent(sessionId, pending)) { "Resize destination changed" }
            listMutex.withLock {
                check(registrations[key] === registration) { "Environment owner changed" }
                registration.version++
                lastKnownSessions.computeIfPresent(key) { _, sessions -> sessions.map { if (it.id == sessionId) resized else it } }
            }
            return resized
        } finally {
            pendingResizes.remove(sessionId, pending)
            pending.applied.cancel()
        }
    }

    private fun failResize(sessionId: String) {
        pendingResizes.remove(sessionId)?.applied?.completeExceptionally(IllegalStateException("Resize destination changed"))
    }
    private fun failResizes(key: StorageKey) {
        pendingResizes.entries.filter { it.value.key == key }.forEach { (id, pending) ->
            if (pendingResizes.remove(id, pending))
                pending.applied.completeExceptionally(IllegalStateException("Resize destination changed"))
        }
    }

    suspend fun configureTerminal(sessionId: String, rows: Int, cols: Int, cellWidth: Int, cellHeight: Int,
        palette: java.util.Properties, sink: RuntimeTerminalState.Sink? = null) = geometryLocks.withSession(sessionId) {
        require(rows in 1..65535 && cols in 1..65535 && cellWidth in 0..65535 && cellHeight in 0..65535)
        val identity = terminalStates.withSession(sessionId) { terminalStates.identity(sessionId) }
        if (!terminalStates.withSession(sessionId) { terminalStates.sameDimensions(sessionId, cols, rows) })
            resizeTerminal(sessionId, rows, cols, cellWidth, cellHeight)
        terminalStates.withSession(sessionId) {
            check(terminalStates.identity(sessionId) === identity) { "Terminal state changed" }
            terminalStates.resize(sessionId, cols, rows, cellWidth, cellHeight)
            terminalStates.palette(sessionId, palette)
            if (sink != null) terminalStates.subscribe(sessionId, sink)
        }
    }
    override suspend fun write(sessionId: String, bytes: ByteArray): Result<Int> = route(sessionId) { onInput(sessionId, bytes); it.write(sessionId, bytes) }

    /** Captures the actual registration, never a runtime-prefix fallback or a future replacement. */
    internal fun inputTarget(sessionId: String): SessionInputTarget? {
        val key = owners[sessionId] ?: return null
        val registration = registrations[key] ?: return null
        val session = inputSessions[sessionId] ?: return null
        val target = SessionInputTarget(registration.inputOwner, sessionId,
            current = {
                owners[sessionId] == key && registrations[key]?.inputOwner === registration.inputOwner &&
                    inputSessions[sessionId] === session &&
                    registration.controller.connectionState.value == SessionConnectionState.CONNECTED
            },
            deliver = { bytes -> onInput(sessionId, bytes); registration.controller.write(sessionId, bytes) })
        return target.takeIf { it.isCurrent() }
    }
    override suspend fun terminate(sessionId: String, signal: Int): Result<Unit> {
        val key = ownerKey(sessionId) ?: return Result.failure(NoSuchElementException("Session not found"))
        val registration = registrations[key]
        return controller(key).fold(
            onSuccess = { owner -> owner.terminate(sessionId, signal) },
            onFailure = { Result.failure(it) },
        ).onSuccess {
            listMutex.withLock {
                if (registrations[key] !== registration) return@onSuccess
                registration?.let { it.version++ }
                inputSessions.remove(sessionId)
                failResize(sessionId)
                lastKnownSessions.computeIfPresent(key) { _, sessions -> sessions.filterNot { it.id == sessionId } }
                owners.remove(sessionId, key)
                launches.remove(sessionId)
                controlStreams.remove(sessionId)
                keyboardFlags.remove(sessionId)
            }
            terminalStates.withSession(sessionId) { terminalStates.remove(sessionId) }
        }
    }

    fun runtimeOf(sessionId: String): SessionRuntime? = owners[sessionId]?.runtime ?: SessionRuntime.fromSessionId(sessionId)
    fun storageOf(sessionId: String): String? = ownerKey(sessionId)?.storageId
    fun environmentOf(sessionId: String): EnvironmentId? = launches[sessionId]?.environmentId ?: ownerKey(sessionId)?.let { environments[it]?.id }
    fun launchOf(sessionId: String): SessionLaunchSpec? = launches[sessionId]
    /** Collection of UI metadata requires a complete live-owner proof, including disconnected VMs. */
    suspend fun verifiedWorkspaceSessions(rememberedIds: Set<String>): Set<String>? {
        if (rememberedIds.any { SessionRuntime.fromSessionId(it) == SessionRuntime.AVF && !owners.containsKey(it) }) return null
        val observed = registrations.toMap()
        val sessions = runCatching { coroutineScope {
            observed.values.map { registration -> async {
                check(registration.controller.connectionState.value == SessionConnectionState.CONNECTED)
                registration.controller.list().getOrThrow()
            } }.awaitAll().flatten()
        } }.getOrNull() ?: return null
        return listMutex.withLock {
            if (observed.size != registrations.size || observed.any { (key, value) -> registrations[key] !== value }) null
            else (sessions + cachedSessions()).filter { it.state != dev.terminal.protocol.SessionStatus.EXITED }.mapTo(mutableSetOf()) { it.id }
        }
    }
    suspend fun verifiedSessions(environment: Environment): Result<List<SessionDescriptor>> {
        val registration = registrations[StorageKey(environment.sessionRuntime, environment.storage.id)] ?: return Result.success(emptyList())
        if (registration.controller.connectionState.value != SessionConnectionState.CONNECTED)
            return Result.failure(IllegalStateException("Cannot verify environment sessions"))
        return registration.controller.list()
    }
    private fun ownerKey(sessionId: String): StorageKey? = owners[sessionId]
        ?: SessionRuntime.fromSessionId(sessionId)?.let { StorageKey(it) }

    override fun close() {
        pendingResizes.keys.toList().forEach(::failResize)
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
        inputSessions.clear()
        environments.clear()
        launches.clear()
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
