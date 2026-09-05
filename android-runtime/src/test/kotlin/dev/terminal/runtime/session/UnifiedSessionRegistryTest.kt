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

    @Test fun terminatingMiddleSessionLeavesSiblingsAndControllerAlive() = runBlocking {
        val native = FakeController("native-a", extraIds = listOf("native-b", "native-c"))
        val registry = UnifiedSessionRegistry()
        registry.register(SessionRuntime.NATIVE, native)
        assertEquals(listOf("native-a", "native-b", "native-c"), registry.list().getOrThrow().map { it.id })

        registry.terminate("native-b", 9).getOrThrow()

        assertEquals(listOf("native-a", "native-c"), registry.list().getOrThrow().map { it.id })
        assertEquals(listOf("native-b"), native.terminatedIds)
        assertFalse(native.closed)
        registry.close()
    }

    @Test fun terminatingOneRuntimeDoesNotHideOrRecreateAnotherRuntime() = runBlocking {
        val native = FakeController("native-one")
        val rootless = FakeController("rootless-one")
        val registry = UnifiedSessionRegistry()
        registry.register(SessionRuntime.NATIVE, native)
        registry.register(SessionRuntime.ROOTLESS, rootless)
        registry.list().getOrThrow()

        registry.terminate("rootless-one", 9).getOrThrow()

        assertEquals(listOf("native-one"), registry.list().getOrThrow().map { it.id })
        assertEquals(0, native.createCount)
        assertFalse(native.closed)
        assertFalse(rootless.closed)
        registry.close()
    }

    @Test fun listFailureRetainsLastKnownSessionsForThatRuntime() = runBlocking {
        val native = FakeController("native-one")
        val rootless = FakeController("rootless-one")
        val registry = UnifiedSessionRegistry()
        registry.register(SessionRuntime.NATIVE, native)
        registry.register(SessionRuntime.ROOTLESS, rootless)
        registry.list().getOrThrow()
        rootless.failList = true

        assertEquals(listOf("native-one", "rootless-one"), registry.list().getOrThrow().map { it.id })
        registry.close()
    }

    @Test fun duplicateTerminationCannotTouchSiblingOrCloseRegistry() = runBlocking {
        val native = FakeController("native-a", extraIds = listOf("native-b"))
        val registry = UnifiedSessionRegistry()
        registry.register(SessionRuntime.NATIVE, native)
        registry.list().getOrThrow()

        registry.terminate("native-a", 9).getOrThrow()
        assertTrue(registry.terminate("native-a", 9).isFailure)

        assertEquals(listOf("native-b"), registry.list().getOrThrow().map { it.id })
        assertEquals(listOf("native-a"), native.terminatedIds)
        assertFalse(native.closed)
        registry.close()
    }

    @Test fun failedTerminationKeepsOwnerAndAllSessions() = runBlocking {
        val native = FakeController("native-a", extraIds = listOf("native-b"))
        val registry = UnifiedSessionRegistry()
        registry.register(SessionRuntime.NATIVE, native)
        registry.list().getOrThrow()
        native.failTerminate = true

        assertTrue(registry.terminate("native-a", 9).isFailure)

        assertEquals(SessionRuntime.NATIVE, registry.runtimeOf("native-a"))
        assertEquals(listOf("native-a", "native-b"), registry.list().getOrThrow().map { it.id })
        assertFalse(native.closed)
        registry.close()
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
        var writeCount = 0
        var detachCount = 0
        var createCount = 0
        var closed = false
        var failList = failList
        var failTerminate = false
        val terminatedIds = mutableListOf<String>()
        private val descriptors = (listOf(id) + extraIds).associateWithTo(linkedMapOf(), ::descriptor)

        override suspend fun create(shell: String, cwd: String, env: Map<String, String>, rows: UShort, cols: UShort): Result<SessionDescriptor> {
            createCount++
            return Result.success(descriptors.getValue(id))
        }
        override suspend fun list() = if (failList) {
            Result.failure(IllegalStateException("runtime unavailable"))
        } else {
            Result.success(descriptors.values.toList())
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
