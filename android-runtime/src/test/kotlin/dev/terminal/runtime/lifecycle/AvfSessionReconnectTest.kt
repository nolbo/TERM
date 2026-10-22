package dev.terminal.runtime.lifecycle

import dev.terminal.emulator.TerminalEmulator
import dev.terminal.protocol.SessionConnectionState
import dev.terminal.protocol.SessionController
import dev.terminal.protocol.SessionDescriptor
import dev.terminal.protocol.SessionEvent
import dev.terminal.protocol.SessionStatus
import dev.terminal.runtime.session.Environment
import dev.terminal.runtime.session.RuntimeTerminalState
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.UnifiedSessionRegistry
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class AvfSessionReconnectTest {
    @Test fun nativePrimaryRecoversLegacyAvfSessionsAndKeepsSiblingOwners() = runBlocking {
        recoverDefaultUnderNative("legacy")
    }

    @Test fun nativePrimaryRecoversMigratedDefaultAvfStorage() = runBlocking {
        recoverDefaultUnderNative(UUID.randomUUID().toString())
    }

    private suspend fun recoverDefaultUnderNative(defaultStorageId: String) {
        val environment = avfEnvironment(defaultStorageId)
        val healthyEnvironment = avfEnvironment()
        val old = Controller("avf-first", "avf-second")
        val replacement = Controller("avf-first", "avf-second")
        val native = Controller("native-sibling")
        val rootless = Controller("rootless-sibling")
        val healthy = Controller("avf-healthy")
        val registry = UnifiedSessionRegistry()
        try {
            registry.register(environment, old)
            registry.register(healthyEnvironment, healthy)
            registry.register(SessionRuntime.NATIVE, native)
            registry.register(SessionRuntime.ROOTLESS, rootless)
            registry.list().getOrThrow()
            registry.terminalStates.create("avf-first", 80, 24)
            registry.terminalStates.output("avf-first", "BEFORE_DISCONNECT".toByteArray())
            old.disconnect()
            val attempts = mutableListOf<Environment>()
            var successes = 0
            reconnectAvfSessions(registry, defaultStorageId, primaryIsAvf = false,
                environmentForStorage = { assertEquals(defaultStorageId, it); environment },
                connect = { attempts += it; replacement }, onConnected = { successes++ })

            assertEquals(listOf(environment), attempts)
            assertEquals(1, successes)
            assertTrue(old.closed)
            assertEquals(listOf("avf-first", "avf-second"), replacement.attached)
            assertTrue(registry.disconnectedStorages(SessionRuntime.AVF).isEmpty())
            assertEquals(environment.id, registry.environmentOf("avf-first"))
            assertEquals(setOf("avf-first", "avf-second", "avf-healthy", "native-sibling", "rootless-sibling"),
                registry.list().getOrThrow().map { it.id }.toSet())
            for ((id, owner) in listOf("native-sibling" to native, "rootless-sibling" to rootless, "avf-healthy" to healthy)) {
                registry.write(id, byteArrayOf(1)).getOrThrow()
                assertEquals(1, owner.writes)
                assertFalse(owner.closed)
                assertTrue(owner.attached.isEmpty())
            }

            replacement.events.emit(SessionEvent.Output("avf-first", "AFTER_RECONNECT".toByteArray()))
            var transcript = ""
            val sink = object : RuntimeTerminalState.Sink {
                override fun checkpoint(emulator: TerminalEmulator, viewport: RuntimeTerminalState.Viewport?) {
                    transcript = emulator.screen.transcriptText
                }
                override fun output(bytes: ByteArray) = Unit
                override fun resized(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) = Unit
                override fun exited(code: Int) = fail("Reconnection must not fabricate session exit")
            }
            withTimeout(2_000) {
                while (!transcript.contains("AFTER_RECONNECT")) {
                    registry.terminalStates.mutex.withLock {
                        registry.terminalStates.subscribe("avf-first", sink)
                        registry.terminalStates.unsubscribe("avf-first", sink)
                    }
                    yield()
                }
            }
            assertTrue(transcript.contains("BEFORE_DISCONNECT"))
        } finally { registry.close() }
    }

    @Test fun avfPrimaryDoesNotRetryItsDefaultTwiceButRecoversIndependentOwner() = runBlocking {
        val primary = avfEnvironment("legacy")
        val independent = avfEnvironment()
        val oldPrimary = Controller("avf-primary")
        val oldIndependent = Controller("avf-independent")
        val replacement = Controller("avf-independent")
        val registry = UnifiedSessionRegistry()
        try {
            registry.register(primary, oldPrimary)
            registry.register(independent, oldIndependent)
            registry.list().getOrThrow()
            oldPrimary.disconnect(); oldIndependent.disconnect()
            val attempts = mutableListOf<Environment>()
            reconnectAvfSessions(registry, "legacy", primaryIsAvf = true,
                environmentForStorage = { assertEquals(independent.storage.id, it); independent },
                connect = { attempts += it; replacement }, onConnected = {})
            assertEquals(listOf(independent), attempts)
            assertEquals(listOf("avf-independent"), replacement.attached)
            assertEquals(listOf("legacy"), registry.disconnectedStorages(SessionRuntime.AVF))
            assertFalse(oldPrimary.closed)
        } finally { registry.close() }
    }

    @Test fun failedConnectionRetainsSessionsAndCanRecoverOnNextPass() = runBlocking {
        val environment = avfEnvironment("legacy")
        val old = Controller("avf-kept")
        val registry = UnifiedSessionRegistry()
        try {
            registry.register(environment, old)
            registry.list().getOrThrow()
            old.disconnect()
            var successes = 0
            suspend fun retry(controller: SessionController?) = reconnectAvfSessions(registry, "legacy",
                primaryIsAvf = false, environmentForStorage = { environment }, connect = { controller },
                onConnected = { successes++ })
            retry(null)
            assertEquals(0, successes)
            assertFalse(old.closed)
            assertEquals(listOf("legacy"), registry.disconnectedStorages(SessionRuntime.AVF))
            assertEquals(listOf("avf-kept"), registry.list().getOrThrow().map { it.id })
            val replacement = Controller("avf-kept")
            retry(replacement)
            assertEquals(1, successes)
            assertEquals(listOf("avf-kept"), replacement.attached)
            assertTrue(registry.disconnectedStorages(SessionRuntime.AVF).isEmpty())
        } finally { registry.close() }
    }

    private fun avfEnvironment(storage: String = UUID.randomUUID().toString()) = Environment.create(
        SessionRuntime.AVF, "AVF Ubuntu", storageId = storage,
        vmInstanceId = if (storage == "legacy") "default" else storage,
        avfDistribution = dev.terminal.runtime.session.EnvironmentDistribution.UBUNTU)

    private class Controller(vararg ids: String) : SessionController {
        private val sessions = ids.map { SessionDescriptor(it, it, "/bin/bash", "/home/user", 1, 24u, 80u, SessionStatus.RUNNING) }
        override val connectionState = MutableStateFlow(SessionConnectionState.CONNECTED)
        override val events = MutableSharedFlow<SessionEvent>()
        val attached = mutableListOf<String>()
        var closed = false
        var writes = 0
        fun disconnect() { connectionState.value = SessionConnectionState.DISCONNECTED }
        override suspend fun list() = if (connectionState.value == SessionConnectionState.DISCONNECTED)
            Result.failure(IllegalStateException("Session transport disconnected")) else Result.success(sessions)
        override suspend fun attach(sessionId: String) = runCatching {
            sessions.single { it.id == sessionId }.also { attached += sessionId }
        }
        override suspend fun write(sessionId: String, bytes: ByteArray): Result<Int> {
            writes++
            return Result.success(bytes.size)
        }
        override suspend fun create(shell: String, cwd: String, env: Map<String, String>, rows: UShort, cols: UShort): Result<SessionDescriptor> = error("Unexpected create")
        override suspend fun detach(sessionId: String): Result<Unit> = error("Unexpected detach")
        override suspend fun resize(sessionId: String, rows: UShort, cols: UShort): Result<SessionDescriptor> = error("Unexpected resize")
        override suspend fun terminate(sessionId: String, signal: Int): Result<Unit> = error("Unexpected termination")
        override fun close() { closed = true; disconnect() }
    }
}
