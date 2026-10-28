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
    @Test fun archiveFailureKeepsTheInMemorySessionAndCanBeRetried() = runBlocking {
        val root = java.io.File.createTempFile("retained-session", ".blocked")
        val failures = mutableListOf<Throwable>()
        val archive = RetainedSessionArchive(root, failures::add)
        val environment = Environment.create(SessionRuntime.AVF, "history")
        var registry = UnifiedSessionRegistry(archive = archive)
        try {
            registry.register(environment, Controller("avf-history")); registry.list().getOrThrow()
            registry.terminalStates.create("avf-history", 80, 24)
            registry.terminalStates.output("avf-history", "KEEP_ON_DISK_ERROR".toByteArray())
            registry.retainStoppedEnvironment(environment) {}
            assertEquals(1, failures.size)
            assertEquals("avf-history", registry.cachedSessions().single().id)
            assertTrue(checkpoint(registry, "avf-history").contains("KEEP_ON_DISK_ERROR"))
            assertTrue(root.delete()); assertTrue(root.mkdir())
            registry.list().getOrThrow()
            registry.close()
            registry = UnifiedSessionRegistry(archive = archive)
            assertTrue(checkpoint(registry, "avf-history").contains("KEEP_ON_DISK_ERROR"))
        } finally { registry.close(); root.deleteRecursively() }
    }

    @Test fun stoppedHistorySurvivesRegistryRecreationAndOnlyExplicitCloseDeletesIt() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("retained-sessions").toFile()
        val environment = Environment.create(SessionRuntime.AVF, "stopped")
        val failures = mutableListOf<Throwable>()
        val archive = RetainedSessionArchive(root, failures::add)
        var registry = UnifiedSessionRegistry(archive = archive)
        try {
            registry.register(environment, Controller("avf-retained"))
            registry.list().getOrThrow()
            registry.terminalStates.create("avf-retained", 80, 24)
            registry.terminalStates.output("avf-retained", "LAST_KNOWN_OUTPUT".toByteArray())
            registry.retainStoppedEnvironment(environment) {}
            assertTrue(failures.toString(), failures.isEmpty())
            assertEquals(setOf("avf-retained"), registry.verifiedWorkspaceSessions(setOf("avf-retained")))
            registry.renameRetained("avf-retained", "보존된 빌드")
            registry.close()
            registry = UnifiedSessionRegistry(archive = archive)
            val session = registry.list().getOrThrow().single()
            assertEquals(SessionStatus.INTERRUPTED, session.state)
            assertEquals("보존된 빌드", registry.retainedTitle(session.id))
            assertFalse(session.state.isLive)
            assertEquals(environment.id, registry.environmentOf(session.id))
            assertEquals(environment.storage.id, registry.storageOf(session.id))
            assertTrue(checkpoint(registry, session.id).contains("LAST_KNOWN_OUTPUT"))
            registry.configureTerminal(session.id, 30, 90, 0, 0, java.util.Properties())
            assertTrue(checkpoint(registry, session.id).contains("LAST_KNOWN_OUTPUT"))
            assertTrue(registry.disconnectedStorages(SessionRuntime.AVF).isEmpty())
            registry.terminate(session.id, 9).getOrThrow()
            registry.close()
            registry = UnifiedSessionRegistry(archive = archive)
            assertTrue(registry.list().getOrThrow().isEmpty())
        } finally { registry.close(); root.deleteRecursively() }
    }

    @Test fun successfulOwnerListThatLostASessionRetainsItsCheckpoint() = runBlocking {
        val environment = Environment.create(SessionRuntime.AVF, "rebooted")
        val controller = Controller("avf-missing")
        val registry = UnifiedSessionRegistry()
        try {
            registry.register(environment, controller); registry.list().getOrThrow()
            registry.terminalStates.create("avf-missing", 80, 24)
            registry.terminalStates.output("avf-missing", "BEFORE_DAEMON_RESTART".toByteArray())
            controller.empty = true
            assertTrue(registry.refreshEnvironment(environment).getOrThrow().isEmpty())
            assertEquals(SessionStatus.INTERRUPTED, registry.list().getOrThrow().single().state)
            assertTrue(checkpoint(registry, "avf-missing").contains("BEFORE_DAEMON_RESTART"))
            assertTrue(registry.write("avf-missing", byteArrayOf(1)).isFailure)
            assertEquals(0, controller.writes)
        } finally { registry.close() }
    }

    @Test fun explicitIdleStopPreservesCompletedSessionsAndStillRefusesLiveWork() = runBlocking {
        val environment = Environment.create(SessionRuntime.NATIVE, "idle")
        val registry = UnifiedSessionRegistry()
        try {
            registry.register(environment, Controller("native-live")); registry.list().getOrThrow()
            assertTrue(runCatching { registry.stopEnvironmentIfIdle(environment) { fail("Must not stop live work") } }.isFailure)
            registry.register(environment, Controller("native-completed", SessionStatus.EXITED))
            registry.list().getOrThrow()
            var stopped = false
            registry.stopEnvironmentIfIdle(environment) { stopped = true }
            assertTrue(stopped)
            assertFalse(registry.isRegistered(SessionRuntime.NATIVE, environment.storage.id))
            assertEquals(SessionStatus.EXITED, registry.cachedSessions().single { it.id == "native-completed" }.state)
            registry.terminate("native-completed", 9).getOrThrow()
        } finally { registry.close() }
    }

    private class Controller(id: String, status: SessionStatus = SessionStatus.RUNNING) : SessionController {
        private val session = SessionDescriptor(id, id, "/bin/sh", "/", 1, 24u, 80u, status)
        override val connectionState = MutableStateFlow(SessionConnectionState.CONNECTED)
        override val events = MutableSharedFlow<SessionEvent>()
        var closed = false
        var writes = 0
        var empty = false
        override suspend fun list() = if (connectionState.value == SessionConnectionState.DISCONNECTED)
            Result.failure(IllegalStateException("link lost")) else Result.success(if (empty) emptyList() else listOf(session))
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
            val error = runCatching { registry.retainStoppedEnvironment(environment) { error("Platform cannot prove stop") } }.exceptionOrNull()
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
            val removed = registry.retainStoppedEnvironment(environment) { verified = true }
            assertTrue(verified); assertEquals(listOf("avf-lost"), removed)
            assertTrue(controller.closed); assertFalse(registry.hasSessionOwner("avf-lost"))
            assertFalse(registry.isRegistered(SessionRuntime.AVF, environment.storage.id))
            assertTrue(registry.verifiedSessions(environment).getOrThrow().isEmpty())
            assertEquals(setOf("avf-lost", "native-live", "rootless-live", "avf-live"), registry.list().getOrThrow().map { it.id }.toSet())
            for ((_, sibling) in siblings) assertFalse(sibling.closed)
            for (id in registry.cachedSessions().filter { it.state.isLive }.map { it.id }) {
                registry.write(id, byteArrayOf(1)).getOrThrow()
                assertTrue(checkpoint(registry, id).contains("CHECKPOINT:$id"))
            }
            assertEquals(0, exits)
            val replacement = Controller("avf-new")
            registry.register(environment, replacement)
            assertEquals(listOf("avf-new"), registry.refreshEnvironment(environment).getOrThrow().map { it.id })
            assertEquals(SessionStatus.INTERRUPTED, registry.cachedSessions().single { it.id == "avf-lost" }.state)
            assertTrue(checkpoint(registry, "avf-lost").contains("CHECKPOINT:avf-lost"))
            assertTrue(registry.write("avf-lost", byteArrayOf(1)).isFailure)
            assertNull(registry.inputTarget("avf-lost"))
            assertEquals(0, replacement.writes)
            registry.terminate("avf-lost", 9).getOrThrow()
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
        registry.terminalStates.withSession(id) {
            registry.terminalStates.subscribe(id, sink); registry.terminalStates.unsubscribe(id, sink)
        }
        return text
    }
}
