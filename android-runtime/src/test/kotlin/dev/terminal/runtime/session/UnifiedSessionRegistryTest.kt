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
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedSessionRegistryTest {
    @Test fun maintenanceBlocksSameRuntimeCreationButNotSiblingOperations() = runBlocking {
        val registry = UnifiedSessionRegistry()
        registry.register(SessionRuntime.ROOTLESS_UBUNTU, FakeController("ubuntu-live"))
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val maintenance = kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
            registry.removeIfIdle(SessionRuntime.ROOTLESS) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val creation = kotlinx.coroutines.CoroutineScope(coroutineContext).async {
            registry.create(SessionRuntime.ROOTLESS, "/bin/sh", "/", rows = 24u, cols = 80u)
        }
        try {
            kotlinx.coroutines.yield()
            assertFalse(creation.isCompleted)
            kotlinx.coroutines.withTimeout(1000) {
                assertEquals(listOf("ubuntu-live"), registry.list().getOrThrow().map { it.id })
                assertTrue(registry.create(SessionRuntime.ROOTLESS_UBUNTU, "/bin/sh", "/", rows = 24u, cols = 80u).isSuccess)
            }
        } finally {
            release.complete(Unit)
            maintenance.join()
            registry.close()
        }
        assertTrue(creation.await().isFailure)
    }

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

    @Test fun distributionSessionsRemainIndependentAcrossDetachAndTerminate() = runBlocking {
        val registry = UnifiedSessionRegistry()
        val runtimes = listOf(SessionRuntime.ROOTLESS, SessionRuntime.ROOTLESS_UBUNTU, SessionRuntime.ROOTLESS_ARCH)
        val controllers = runtimes.associateWith { FakeController(it.idPrefix + "test") }
        controllers.forEach { (runtime, controller) -> registry.register(runtime, controller) }
        val sessions = runtimes.map { registry.create(it, "/bin/bash", "/root", rows = 24u, cols = 80u).getOrThrow() }
        registry.detach(sessions[1].id).getOrThrow()
        registry.write(sessions[2].id, "pacman -V\n".encodeToByteArray()).getOrThrow()
        registry.terminate(sessions[1].id, 9).getOrThrow()
        assertEquals(setOf(sessions[0].id, sessions[2].id), registry.list().getOrThrow().map { it.id }.toSet())
        assertEquals(1, controllers.getValue(SessionRuntime.ROOTLESS_ARCH).writeCount)
        assertEquals(0, controllers.getValue(SessionRuntime.ROOTLESS).writeCount)
        assertTrue(controllers.values.none { it.closed })
        registry.close()
    }

    @Test fun removalRejectsLiveSessionsAndUnknownSessionState() = runBlocking {
        val registry = UnifiedSessionRegistry()
        val controller = FakeController("rootless-live")
        registry.register(SessionRuntime.ROOTLESS, controller)
        var removed = false
        assertTrue(runCatching { registry.removeIfIdle(SessionRuntime.ROOTLESS) { removed = true } }.isFailure)
        controller.failList = true
        assertTrue(runCatching { registry.removeIfIdle(SessionRuntime.ROOTLESS) { removed = true } }.isFailure)
        assertFalse(removed)
        assertTrue(registry.isRegistered(SessionRuntime.ROOTLESS))
        assertFalse(controller.closed)
        registry.close()
    }

    @Test fun removalUnregistersOnlyIdleDistributionAndAllowsReinstallation() = runBlocking {
        val registry = UnifiedSessionRegistry()
        val controller = FakeController("rootless-idle")
        val sibling = FakeController("ubuntu-live")
        registry.register(SessionRuntime.ROOTLESS, controller)
        registry.register(SessionRuntime.ROOTLESS_UBUNTU, sibling)
        registry.terminate("rootless-idle", 9).getOrThrow()
        registry.removeIfIdle(SessionRuntime.ROOTLESS) { controller.close() }
        assertFalse(registry.isRegistered(SessionRuntime.ROOTLESS))
        assertTrue(registry.create(SessionRuntime.ROOTLESS, "/bin/sh", "/", rows = 24u, cols = 80u).isFailure)
        assertEquals(listOf("ubuntu-live"), registry.list().getOrThrow().map { it.id })
        assertFalse(sibling.closed)
        registry.register(SessionRuntime.ROOTLESS, FakeController("rootless-new"))
        assertTrue(registry.create(SessionRuntime.ROOTLESS, "/bin/sh", "/", rows = 24u, cols = 80u).isSuccess)
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
