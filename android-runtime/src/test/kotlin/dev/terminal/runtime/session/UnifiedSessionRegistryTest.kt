package dev.terminal.runtime.session

import dev.terminal.protocol.SessionConnectionState
import dev.terminal.protocol.SessionController
import dev.terminal.protocol.SessionDescriptor
import dev.terminal.protocol.SessionEvent
import dev.terminal.protocol.SessionStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedSessionRegistryTest {
    @Test fun routesOperationsToOwningRuntimeAndDetachDoesNotCloseController() = runBlocking {
        val native = FakeController("native-one")
        val rootless = FakeController("rootless-one")
        val registry = UnifiedSessionRegistry()
        registry.register(SessionRuntime.NATIVE, native)
        registry.register(SessionRuntime.ROOTLESS, rootless)

        val created = registry.create(SessionRuntime.ROOTLESS, "/bin/sh", "/root", rows = 24u, cols = 80u).getOrThrow()
        assertEquals("rootless-one", created.id)
        assertEquals(SessionRuntime.ROOTLESS, registry.runtimeOf(created.id))

        registry.write(created.id, "apt update\n".encodeToByteArray()).getOrThrow()
        registry.detach(created.id).getOrThrow()

        assertEquals(0, native.writeCount)
        assertEquals(1, rootless.writeCount)
        assertEquals(1, rootless.detachCount)
        assertFalse(rootless.closed)
        registry.close()
        assertTrue(rootless.closed)
    }

    @Test fun explicitRuntimeSelectionNeverFallsBack() = runBlocking {
        val registry = UnifiedSessionRegistry()
        registry.register(SessionRuntime.NATIVE, FakeController("native-one"))

        val result = registry.create(SessionRuntime.ROOTLESS, "/bin/sh", "/root", rows = 24u, cols = 80u)

        assertTrue(result.isFailure)
        registry.close()
    }

    @Test fun unavailableOptionalRuntimeDoesNotHideHealthySessions() = runBlocking {
        val registry = UnifiedSessionRegistry()
        registry.register(SessionRuntime.NATIVE, FakeController("native-one"))
        registry.register(SessionRuntime.ROOTLESS, FakeController("rootless-one", failList = true))

        val sessions = registry.list().getOrThrow()

        assertEquals(listOf("native-one"), sessions.map { it.id })
        registry.close()
    }

    private class FakeController(
        private val id: String,
        private val failList: Boolean = false,
    ) : SessionController {
        private val mutableEvents = MutableSharedFlow<SessionEvent>()
        private val mutableState = MutableStateFlow(SessionConnectionState.CONNECTED)
        override val events: SharedFlow<SessionEvent> = mutableEvents
        override val connectionState: StateFlow<SessionConnectionState> = mutableState
        var writeCount = 0
        var detachCount = 0
        var closed = false
        private val descriptor get() = SessionDescriptor(id, id, "/bin/sh", "/", 1, 24u, 80u, SessionStatus.RUNNING)

        override suspend fun create(shell: String, cwd: String, env: Map<String, String>, rows: UShort, cols: UShort) = Result.success(descriptor)
        override suspend fun list() = if (failList) {
            Result.failure(IllegalStateException("runtime unavailable"))
        } else {
            Result.success(listOf(descriptor))
        }
        override suspend fun attach(sessionId: String) = Result.success(descriptor)
        override suspend fun detach(sessionId: String): Result<Unit> { detachCount++; return Result.success(Unit) }
        override suspend fun resize(sessionId: String, rows: UShort, cols: UShort) = Result.success(descriptor.copy(rows = rows, cols = cols))
        override suspend fun write(sessionId: String, bytes: ByteArray): Result<Int> { writeCount++; return Result.success(bytes.size) }
        override suspend fun terminate(sessionId: String, signal: Int) = Result.success(Unit)
        override fun close() { closed = true; mutableState.value = SessionConnectionState.DISCONNECTED }
    }
}
