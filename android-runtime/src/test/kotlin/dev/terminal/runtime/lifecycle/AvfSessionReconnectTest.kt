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
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class AvfSessionReconnectTest {
    @Test fun legacyAvfEnvironmentRecoversAlongsideHealthyOwners() = runBlocking {
        recoverEnvironment("legacy")
    }

    @Test fun adoptedLegacyAvfEnvironmentRecovers() = runBlocking {
        recoverEnvironment(UUID.randomUUID().toString())
    }

    private suspend fun recoverEnvironment(storageId: String) {
        val environment = avfEnvironment(storageId)
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
            reconnectAvfSessions(registry,
                environmentForStorage = { assertEquals(storageId, it); environment },
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

    @Test fun legacyAndUuidEnvironmentsBothRecoverWithoutAPrimaryOwner() = runBlocking {
        val legacy = avfEnvironment("legacy")
        val other = avfEnvironment()
        val oldLegacy = Controller("avf-legacy")
        val oldOther = Controller("avf-other")
        val legacyReplacement = Controller("avf-legacy")
        val otherReplacement = Controller("avf-other")
        val environments = listOf(legacy, other).associateBy { it.storage.id }
        val replacements = mapOf(legacy.id to legacyReplacement, other.id to otherReplacement)
        val registry = UnifiedSessionRegistry()
        try {
            registry.register(legacy, oldLegacy); registry.register(other, oldOther)
            registry.list().getOrThrow()
            oldLegacy.disconnect(); oldOther.disconnect()
            val attempts = mutableSetOf<Environment>()
            reconnectAvfSessions(registry,
                environmentForStorage = { environments.getValue(it) },
                connect = { attempts += it; replacements.getValue(it.id) }, onConnected = {})
            assertEquals(environments.values.toSet(), attempts)
            assertEquals(listOf("avf-legacy"), legacyReplacement.attached)
            assertEquals(listOf("avf-other"), otherReplacement.attached)
            assertTrue(registry.disconnectedStorages(SessionRuntime.AVF).isEmpty())
            assertTrue(oldLegacy.closed); assertTrue(oldOther.closed)
        } finally { registry.close() }
    }

    @Test fun environmentWithoutWorkDoesNotReconnectBecauseASiblingIsRunning() = runBlocking {
        val environment = avfEnvironment()
        val old = Controller("avf-idle")
        val registry = UnifiedSessionRegistry()
        try {
            registry.register(environment, old)
            registry.list().getOrThrow(); old.disconnect()
            reconnectAvfSessions(registry, environmentForStorage = { environment },
                connect = { fail("No work for this Environment"); null }, onConnected = {}, canReconnect = { false })
            assertFalse(old.closed)
            assertEquals(listOf(environment.storage.id), registry.disconnectedStorages(SessionRuntime.AVF))
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
            suspend fun retry(controller: SessionController?) = reconnectAvfSessions(registry, environmentForStorage = { environment }, connect = { controller },
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

    @Test fun aConnectedSocketWithAFailedListIsNotReportedAsRecovered() = runBlocking {
        val environment = avfEnvironment()
        val old = Controller("avf-cached")
        val bad = Controller("avf-cached")
        val replacement = object : SessionController by bad {
            override suspend fun list(): Result<List<SessionDescriptor>> = Result.failure(IllegalStateException("RPC stalled"))
        }
        val registry = UnifiedSessionRegistry()
        try {
            registry.register(environment, old); registry.list().getOrThrow(); old.disconnect()
            reconnectAvfSessions(registry, { environment }, { replacement },
                onConnected = { fail("Recovery requires a successful owner RPC") })
            assertTrue(bad.closed)
            assertEquals(listOf("avf-cached"), registry.cachedSessions().map { it.id })
            assertEquals(listOf(environment.storage.id), registry.disconnectedStorages(SessionRuntime.AVF))
        } finally { registry.close() }
    }

    @Test fun reconnectAndProvenOwnerLossCannotReplaceEachOtherOutsideTheEnvironmentLease() = runBlocking {
        val environment = avfEnvironment()
        val old = Controller("avf-old")
        val replacement = Controller("avf-old")
        val registry = UnifiedSessionRegistry()
        val locks = StoragePreparationLocks()
        val entered = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
        var verified = false
        try {
            registry.register(environment, old); registry.list().getOrThrow(); old.disconnect()
            val reconnect = async { reconnectAvfSessions(registry, { environment },
                connect = { entered.complete(Unit); proceed.await(); replacement }, onConnected = {},
                withEnvironmentLease = { owner, work -> locks.withStorage(owner.sessionRuntime, owner.storage.id) { work() } }) }
            entered.await()
            val loss = async { locks.withStorage(environment.sessionRuntime, environment.storage.id) {
                registry.forgetStoppedEnvironment(environment) { verified = true }
            } }
            yield(); assertFalse(verified)
            proceed.complete(Unit)
            withTimeout(2_000) { reconnect.await(); loss.await() }
            assertTrue(verified); assertTrue(replacement.closed)
            assertFalse(registry.isRegistered(SessionRuntime.AVF, environment.storage.id))
        } finally { proceed.complete(Unit); registry.close() }
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
