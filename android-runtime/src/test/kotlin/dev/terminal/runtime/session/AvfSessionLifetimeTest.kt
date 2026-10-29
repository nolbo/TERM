package dev.terminal.runtime.session
import dev.terminal.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
class AvfSessionLifetimeTest {
    @Test fun lateOutputCannotRestoreTerminatedOwnershipOrParser() = runBlocking {
        val environment = Environment.create(SessionRuntime.AVF, "Audit")
        val controller = FakeController("avf-audit", extraIds = listOf("avf-live"))
        val registry = UnifiedSessionRegistry()
        val received = Channel<SessionEvent>(Channel.UNLIMITED)
        val events = launch(start = CoroutineStart.UNDISPATCHED) { registry.events.collect { received.send(it) } }
        try {
            registry.register(environment, controller)
            registry.list().getOrThrow()
            assertEquals(environment.id, registry.environmentOf("avf-audit"))
            registry.terminate("avf-audit", 9).getOrThrow()
            assertNull(registry.environmentOf("avf-audit"))
            controller.emit(SessionEvent.Output("avf-audit", "STALE".toByteArray()))
            controller.emit(SessionEvent.Output("avf-live", "LIVE".toByteArray()))
            assertEquals("avf-live", (withTimeout(1000) { received.receive() } as SessionEvent.Output).sessionId)
            assertNull(registry.environmentOf("avf-audit"))
            registry.refreshEnvironment(environment).getOrThrow()
            assertNull(registry.environmentOf("avf-audit"))
            assertEquals(listOf("avf-live"), registry.cachedSessions().map { it.id })
            val field = UnifiedSessionRegistry::class.java.getDeclaredField("controlStreams").apply { isAccessible = true }
            assertFalse((field.get(registry) as Map<*, *>).containsKey("avf-audit"))
        } finally { events.cancelAndJoin(); registry.close() }
    }
    @Test fun cancelledReconnectClosesTheUnverifiedControllerForRetry() = runBlocking {
        val environment = Environment.create(SessionRuntime.AVF, "Audit reconnect")
        val previous = FakeController("avf-audit")
        val replacement = FakeController("avf-audit")
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        replacement.listGate = started to release
        val registry = UnifiedSessionRegistry()
        try {
            registry.register(environment, previous); registry.list().getOrThrow(); previous.disconnect()
            val job = launch { dev.terminal.runtime.lifecycle.reconnectAvfSessions(registry,
                environmentForStorage = { environment }, connect = { replacement }, onConnected = {}) }
            withTimeout(1000) { started.await() }
            job.cancelAndJoin()
            assertTrue(replacement.closed)
            assertEquals(listOf(environment.storage.id), registry.disconnectedStorages(SessionRuntime.AVF))
        } finally { release.complete(Unit); registry.close() }
    }

    private class FakeController(
        private val id: String,
        failList: Boolean = false,
        extraIds: List<String> = emptyList(),
    ) : SessionController {
        private val mutableEvents = MutableSharedFlow<SessionEvent>()
        private val mutableState = MutableStateFlow(SessionConnectionState.CONNECTED)
        override val events: SharedFlow<SessionEvent> = mutableEvents
        override val connectionState: StateFlow<SessionConnectionState> = mutableState
        suspend fun emit(event: SessionEvent) { mutableEvents.emit(event) }
        fun disconnect() { mutableState.value = SessionConnectionState.DISCONNECTED }
        suspend fun exit(sessionId: String) {
            descriptors[sessionId] = descriptors.getValue(sessionId).copy(state = SessionStatus.EXITED, exitCode = 0)
            mutableEvents.emit(SessionEvent.Exited(sessionId, 0))
        }
        var writeCount = 0
        var detachCount = 0
        var createCount = 0
        var closed = false
        var failList = failList
        var failTerminate = false
        var listGate: Pair<kotlinx.coroutines.CompletableDeferred<Unit>, kotlinx.coroutines.CompletableDeferred<Unit>>? = null
        val terminatedIds = mutableListOf<String>()
        private val descriptors = (listOf(id) + extraIds).associateWithTo(linkedMapOf(), ::descriptor)

        override suspend fun create(shell: String, cwd: String, env: Map<String, String>, rows: UShort, cols: UShort): Result<SessionDescriptor> {
            createCount++
            return Result.success(descriptors.getValue(id))
        }
        override suspend fun list(): Result<List<SessionDescriptor>> {
            val snapshot = descriptors.values.toList()
            listGate?.let { (started, release) -> started.complete(Unit); release.await() }
            return if (failList) Result.failure(IllegalStateException("runtime unavailable")) else Result.success(snapshot)
        }
        override suspend fun attach(sessionId: String) = runCatching { descriptors.getValue(sessionId) }
        override suspend fun detach(sessionId: String): Result<Unit> { detachCount++; return Result.success(Unit) }
        override suspend fun resize(sessionId: String, rows: UShort, cols: UShort) =
            runCatching { descriptors.getValue(sessionId).copy(rows = rows, cols = cols) }
        override suspend fun write(sessionId: String, bytes: ByteArray): Result<Int> { writeCount++; return Result.success(bytes.size) }
        override suspend fun terminate(sessionId: String, signal: Int): Result<Unit> =
            if (failTerminate) {
                Result.failure(IllegalStateException("terminate failed"))
            } else if (descriptors.remove(sessionId) != null) {
                terminatedIds += sessionId
                Result.success(Unit)
            } else {
                Result.failure(NoSuchElementException("Session not found"))
            }
        override fun close() { closed = true; mutableState.value = SessionConnectionState.DISCONNECTED }

        private fun descriptor(sessionId: String) =
            SessionDescriptor(sessionId, sessionId, "/bin/sh", "/", sessionId.last().code.toLong(), 24u, 80u, SessionStatus.RUNNING)
    }
}
