package dev.terminal.runtime.lifecycle

import dev.terminal.emulator.TerminalEmulator
import dev.terminal.protocol.*
import dev.terminal.runtime.session.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class AvfOwnerLossTest {
    private class Controller(id: String) : SessionController {
        private val session = SessionDescriptor(id, id, "/bin/sh", "/", 1, 24u, 80u, SessionStatus.RUNNING)
        override val connectionState = MutableStateFlow(SessionConnectionState.CONNECTED)
        override val events = MutableSharedFlow<SessionEvent>()
        var closed = false
        var writes = 0
        override suspend fun list() = if (connectionState.value == SessionConnectionState.DISCONNECTED)
            Result.failure(IllegalStateException("link lost")) else Result.success(listOf(session))
        override suspend fun write(sessionId: String, bytes: ByteArray): Result<Int> { writes++; return Result.success(bytes.size) }
        override suspend fun attach(sessionId: String) = Result.success(session)
        override suspend fun create(shell: String, cwd: String, env: Map<String, String>, rows: UShort, cols: UShort): Result<SessionDescriptor> = error("Unexpected create")
        override suspend fun detach(sessionId: String): Result<Unit> = error("Unexpected detach")
        override suspend fun resize(sessionId: String, rows: UShort, cols: UShort): Result<SessionDescriptor> = error("Unexpected resize")
        override suspend fun terminate(sessionId: String, signal: Int): Result<Unit> = error("Unexpected termination")
        override fun close() { closed = true; connectionState.value = SessionConnectionState.DISCONNECTED }
    }

    @Test fun unprovenStopPreservesTheDisconnectedOwnerAndItsCheckpoint() = runBlocking {
        val environment = Environment.create(SessionRuntime.AVF, "unknown")
        val controller = Controller("avf-unknown")
        val registry = UnifiedSessionRegistry()
        try {
            registry.register(environment, controller); registry.list().getOrThrow()
            registry.terminalStates.create("avf-unknown", 80, 24)
            registry.terminalStates.output("avf-unknown", "KEEP_ME".toByteArray())
            controller.connectionState.value = SessionConnectionState.DISCONNECTED
            val error = runCatching { registry.forgetStoppedEnvironment(environment) { error("Platform cannot prove stop") } }.exceptionOrNull()
            assertNotNull(error)
            assertFalse(controller.closed)
            assertTrue(registry.isRegistered(SessionRuntime.AVF, environment.storage.id))
            assertEquals(listOf("avf-unknown"), registry.cachedSessions().map { it.id })
            assertTrue(checkpoint(registry, "avf-unknown").contains("KEEP_ME"))
        } finally { registry.close() }
    }

    @Test fun provenStopReleasesOnlyThatOwnerWithoutInventingGuestExitEvents() = runBlocking {
        val environment = Environment.create(SessionRuntime.AVF, "stopped")
        val controller = Controller("avf-lost")
        val registry = UnifiedSessionRegistry()
        val siblings = listOf(SessionRuntime.NATIVE to "native-live", SessionRuntime.ROOTLESS to "rootless-live", SessionRuntime.AVF to "avf-live")
            .associate { (runtime, id) -> Environment.create(runtime, id) to Controller(id) }
        var exits = 0
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { registry.events.collect { if (it is SessionEvent.Exited) exits++ } }
        try {
            registry.register(environment, controller)
            siblings.forEach { (owner, client) -> registry.register(owner, client) }
            registry.list().getOrThrow()
            for (id in registry.cachedSessions().map { it.id }) {
                registry.terminalStates.create(id, 80, 24)
                registry.terminalStates.output(id, "CHECKPOINT:$id".toByteArray())
            }
            controller.connectionState.value = SessionConnectionState.DISCONNECTED
            var verified = false
            val removed = registry.forgetStoppedEnvironment(environment) { verified = true }
            assertTrue(verified); assertEquals(listOf("avf-lost"), removed)
            assertTrue(controller.closed); assertFalse(registry.hasSessionOwner("avf-lost"))
            assertFalse(registry.isRegistered(SessionRuntime.AVF, environment.storage.id))
            assertTrue(registry.verifiedSessions(environment).getOrThrow().isEmpty())
            assertEquals(setOf("native-live", "rootless-live", "avf-live"), registry.list().getOrThrow().map { it.id }.toSet())
            for ((_, sibling) in siblings) assertFalse(sibling.closed)
            for (id in registry.cachedSessions().map { it.id }) {
                registry.write(id, byteArrayOf(1)).getOrThrow()
                assertTrue(checkpoint(registry, id).contains("CHECKPOINT:$id"))
            }
            assertEquals(0, exits)
            val replacement = Controller("avf-new")
            registry.register(environment, replacement)
            assertEquals(listOf("avf-new"), registry.refreshEnvironment(environment).getOrThrow().map { it.id })
            assertFalse(registry.cachedSessions().any { it.id == "avf-lost" })
        } finally { collector.cancelAndJoin(); registry.close() }
    }

    private suspend fun checkpoint(registry: UnifiedSessionRegistry, id: String): String {
        var text = ""
        val sink = object : RuntimeTerminalState.Sink {
            override fun checkpoint(emulator: TerminalEmulator, viewport: RuntimeTerminalState.Viewport?) { text = emulator.screen.transcriptText }
            override fun output(bytes: ByteArray) = Unit
            override fun resized(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) = Unit
            override fun exited(code: Int) = fail("A transport failure is not a guest exit")
        }
        registry.terminalStates.mutex.withLock {
            registry.terminalStates.subscribe(id, sink); registry.terminalStates.unsubscribe(id, sink)
        }
        return text
    }
}
