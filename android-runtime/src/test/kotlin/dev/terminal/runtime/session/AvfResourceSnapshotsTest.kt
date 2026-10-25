package dev.terminal.runtime.session

import dev.terminal.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class AvfResourceSnapshotsTest {
    private class Owner : SessionController {
        override val events = MutableSharedFlow<SessionEvent>()
        override val connectionState = MutableStateFlow(SessionConnectionState.CONNECTED)
        val metrics = CompletableDeferred<Result<GuestResourceUsage>>()
        var calls = 0
        override suspend fun resourceUsage(): Result<GuestResourceUsage> { calls++; return metrics.await() }
        override suspend fun list() = Result.success(emptyList<SessionDescriptor>())
        override suspend fun create(shell: String, cwd: String, env: Map<String, String>, rows: UShort, cols: UShort): Result<SessionDescriptor> = error("must not create a session")
        override suspend fun attach(sessionId: String): Result<SessionDescriptor> = error("must not attach")
        override suspend fun detach(sessionId: String) = Result.success(Unit)
        override suspend fun resize(sessionId: String, rows: UShort, cols: UShort): Result<SessionDescriptor> = error("must not resize")
        override suspend fun write(sessionId: String, bytes: ByteArray) = Result.success(bytes.size)
        override suspend fun terminate(sessionId: String, signal: Int) = Result.success(Unit)
        override fun close() { connectionState.value = SessionConnectionState.DISCONNECTED }
    }
    @Test fun observationsAreOnDemandNonblockingAndCoalescedWithoutCreatingSessions() = runBlocking {
        val registry = UnifiedSessionRegistry(scope = CoroutineScope(coroutineContext + SupervisorJob()))
        val environment = Environment.create(SessionRuntime.AVF, "Ubuntu")
        val owner = Owner()
        try { withTimeout(3000) {
            registry.register(environment, owner)
            assertEquals(0, owner.calls)
            assertFalse(registry.avfResourceSnapshots().getJSONObject(0).has("total"))
            yield()
            repeat(100) { registry.avfResourceSnapshots() }
            assertEquals(1, owner.calls)
            owner.metrics.complete(Result.success(GuestResourceUsage(2048, 512, 0, 0, 2, 25, -1)))
            while (!registry.avfResourceSnapshots().getJSONObject(0).has("total")) yield()
            val metrics = registry.avfResourceSnapshots().getJSONObject(0)
            assertEquals("Ubuntu", metrics.getString("name"))
            assertEquals(512, metrics.getInt("available")); assertEquals(1, owner.calls)
            owner.connectionState.value = SessionConnectionState.DISCONNECTED
            assertFalse(registry.avfResourceSnapshots().getJSONObject(0).has("total"))
        } } finally { registry.close() }
    }
    @Test fun legacyGuestsAreNotProbedRepeatedlyAndReplacementCanSupplyMetrics() = runBlocking {
        val registry = UnifiedSessionRegistry(scope = CoroutineScope(coroutineContext + SupervisorJob()))
        val environment = Environment.create(SessionRuntime.AVF, "Ubuntu")
        val old = Owner().apply { metrics.complete(Result.failure(GuestRequestException(
            ProtocolErrorPayload(ProtocolErrorCode.INVALID_ARGUMENT, "old guest")))) }
        try { withTimeout(3000) {
            registry.register(environment, old)
            while (registry.avfResourceSnapshots().getJSONObject(0).getBoolean("supported")) yield()
            repeat(100) { registry.avfResourceSnapshots(); yield() }
            assertEquals(1, old.calls)
            val fresh = Owner().apply { metrics.complete(Result.success(GuestResourceUsage(4096, 1024, 0, 0, 1, 10, 0))) }
            registry.register(environment, fresh)
            while (!registry.avfResourceSnapshots().getJSONObject(0).has("total")) yield()
            assertEquals(4096, registry.avfResourceSnapshots().getJSONObject(0).getInt("total"))
            assertEquals(1, fresh.calls)
        } } finally { registry.close() }
    }
}
