package dev.terminal.runtime.session

import dev.terminal.emulator.TerminalEmulator
import dev.terminal.protocol.*
import java.util.Properties
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class OrderedTerminalResizeTest {
    private class Owner : SessionController {
        override val events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 128)
        override val connectionState = MutableStateFlow(SessionConnectionState.CONNECTED)
        override val supportsOrderedResize = true
        data class Resize(val id: String, val rows: UShort, val cols: UShort, val barrier: Any,
            val result: CompletableDeferred<Result<SessionDescriptor>> = CompletableDeferred())
        val requests = Channel<Resize>(Channel.UNLIMITED)
        var creation: CompletableDeferred<Unit>? = null
        var initialOutput = false
        var attachment: CompletableDeferred<Unit>? = null
        private fun descriptor(id: String, rows: UShort = 24u, cols: UShort = 80u) =
            SessionDescriptor(id, id, "/bin/sh", "/", 1, rows, cols, SessionStatus.RUNNING)
        override suspend fun create(shell: String, cwd: String, env: Map<String, String>, rows: UShort, cols: UShort): Result<SessionDescriptor> {
            creation?.await()
            if (initialOutput) events.emit(SessionEvent.Output("native-new", "FIRST".toByteArray()))
            return Result.success(descriptor("native-new", rows, cols))
        }
        override suspend fun list() = Result.success(listOf(descriptor("native-a"), descriptor("native-b")))
        override suspend fun attach(sessionId: String): Result<SessionDescriptor> {
            attachment?.await(); return Result.success(descriptor(sessionId))
        }
        override suspend fun detach(sessionId: String) = Result.success(Unit)
        override suspend fun resize(sessionId: String, rows: UShort, cols: UShort): Result<SessionDescriptor> = error("Unordered resize")
        override suspend fun resizeOrdered(sessionId: String, rows: UShort, cols: UShort, barrier: Any): Result<SessionDescriptor> {
            val request = Resize(sessionId, rows, cols, barrier); requests.send(request)
            return request.result.await()
        }
        suspend fun complete(request: Resize, after: String? = null) {
            events.emit(SessionEvent.Resized(request.id, request.rows, request.cols, request.barrier))
            if (after != null) events.emit(SessionEvent.Output(request.id, after.toByteArray()))
            request.result.complete(Result.success(descriptor(request.id, request.rows, request.cols)))
        }
        override suspend fun write(sessionId: String, bytes: ByteArray) = Result.success(bytes.size)
        override suspend fun terminate(sessionId: String, signal: Int) = Result.success(Unit)
        override fun close() { connectionState.value = SessionConnectionState.DISCONNECTED }
    }

    private class Snapshot : RuntimeTerminalState.Sink {
        val order = mutableListOf<String>()
        var columns = 0; var text = ""
        override fun checkpoint(emulator: TerminalEmulator, viewport: RuntimeTerminalState.Viewport?) {
            columns = emulator.mColumns; text = emulator.screen.transcriptText; order += "checkpoint:$columns"
        }
        override fun output(bytes: ByteArray) { order += "${bytes.decodeToString()}:$columns" }
        override fun resized(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) { this.columns = columns; order += "resize:$columns" }
        override fun exited(code: Int) { order += "exit" }
    }

    private suspend fun setup(registry: UnifiedSessionRegistry, owner: Owner, runtime: SessionRuntime = SessionRuntime.NATIVE) {
        registry.register(runtime, owner); registry.list().getOrThrow()
        listOf("native-a", "native-b").forEach { id -> registry.terminalStates.withSession(id) { registry.terminalStates.create(id, 80, 24) } }
    }

    @Test fun delayedResizeDoesNotBlockOutputOrSiblingConfigurationAndAppliesAtOutputBoundary() = runBlocking {
        val worker = CoroutineScope(coroutineContext + SupervisorJob())
        val registry = UnifiedSessionRegistry(scope = worker); val owner = Owner()
        try { withTimeout(5000) {
            setup(registry, owner)
            val first = Snapshot()
            registry.terminalStates.withSession("native-a") { registry.terminalStates.subscribe("native-a", first) }
            val resize = async { registry.configureTerminal("native-a", 30, 40, 8, 16, Properties()) }
            val request = owner.requests.receive()
            owner.events.emit(SessionEvent.Output("native-a", "OLD".toByteArray()))
            owner.events.emit(SessionEvent.Output("native-b", "SIBLING".toByteArray()))
            val second = Snapshot()
            registry.configureTerminal("native-b", 24, 80, 8, 16, Properties(), second)
            while (first.order.size < 2 || !registry.terminalStates.withSession("native-b") {
                val snapshot = Snapshot(); registry.terminalStates.subscribe("native-b", snapshot)
                registry.terminalStates.unsubscribe("native-b", snapshot); snapshot.text.contains("SIBLING")
            }) yield()
            assertFalse(resize.isCompleted)
            val siblingResize = async { registry.resize("native-b", 25u, 60u) }
            owner.complete(owner.requests.receive()); siblingResize.await().getOrThrow()
            owner.complete(request, "NEW"); resize.await()
            while (first.order.size < 4) yield()
            assertEquals(listOf("checkpoint:80", "OLD:80", "resize:40", "NEW:40"), first.order)
            val restored = Snapshot()
            registry.configureTerminal("native-a", 30, 40, 8, 16, Properties(), restored)
            assertEquals(40, restored.columns); assertTrue(restored.text.contains("OLDNEW"))
        } } finally { registry.close() }
    }

    @Test fun sameSessionSerializesResizeAndFailureRetainsGeometryAndAllowsNextRequest() = runBlocking {
        val registry = UnifiedSessionRegistry(scope = CoroutineScope(coroutineContext + SupervisorJob())); val owner = Owner()
        try { withTimeout(5000) {
            setup(registry, owner)
            val mirror = Snapshot()
            registry.terminalStates.withSession("native-a") { registry.terminalStates.subscribe("native-a", mirror) }
            val first = async { registry.resize("native-a", 30u, 40u) }; val request = owner.requests.receive()
            val second = async(start = CoroutineStart.UNDISPATCHED) { registry.resize("native-a", 31u, 41u) }
            assertTrue(owner.requests.tryReceive().isFailure)
            request.result.complete(Result.failure(IllegalStateException("failed")))
            assertTrue(first.await().isFailure)
            assertTrue(registry.terminalStates.sameDimensions("native-a", 80, 24))
            val next = owner.requests.receive()
            // A duplicate/late marker from the failed request cannot match the next one.
            owner.complete(request); owner.events.emit(SessionEvent.Output("native-a", "old".toByteArray()))
            owner.complete(next); second.await().getOrThrow()
            assertTrue(registry.terminalStates.sameDimensions("native-a", 41, 31))
            assertEquals(listOf("checkpoint:80", "old:80", "resize:41"), mirror.order)
        } } finally { registry.close() }
    }

    @Test fun terminationAndRecreationFenceAnInFlightResizeEvenWhenTheIdIsReused() = runBlocking {
        val registry = UnifiedSessionRegistry(scope = CoroutineScope(coroutineContext + SupervisorJob())); val owner = Owner()
        try { withTimeout(5000) {
            setup(registry, owner)
            val resize = async { registry.resize("native-a", 30u, 40u) }; val request = owner.requests.receive()
            registry.terminate("native-a", 9).getOrThrow()
            registry.list().getOrThrow() // Same identifier, new lifetime.
            registry.terminalStates.withSession("native-a") { registry.terminalStates.create("native-a", 80, 24) }
            owner.complete(request)
            assertTrue(resize.await().isFailure)
            assertTrue(registry.terminalStates.sameDimensions("native-a", 80, 24))
        } } finally { registry.close() }
    }

    @Test fun ownerReplacementAndSessionExitRejectLateResizeWithoutChangingCheckpoint() = runBlocking {
        for (replace in listOf(false, true)) {
            val registry = UnifiedSessionRegistry(scope = CoroutineScope(coroutineContext + SupervisorJob())); val owner = Owner()
            try { withTimeout(5000) {
                setup(registry, owner)
                val resize = async { registry.resize("native-a", 30u, 40u) }; val request = owner.requests.receive()
                if (replace) registry.register(SessionRuntime.NATIVE, Owner())
                else owner.events.emit(SessionEvent.Exited("native-a", 0))
                owner.complete(request)
                assertTrue(resize.await().isFailure)
                assertTrue(registry.terminalStates.sameDimensions("native-a", 80, 24))
            } } finally { registry.close() }
        }
    }

    @Test fun slowCreateAndAttachLeaveExistingOutputAndCheckpointsAvailableAndKeepInitialOutput() = runBlocking {
        for (runtime in listOf(SessionRuntime.NATIVE, SessionRuntime.AVF)) {
            val registry = UnifiedSessionRegistry(scope = CoroutineScope(coroutineContext + SupervisorJob())); val owner = Owner()
            try { withTimeout(5000) {
                setup(registry, owner, runtime)
                owner.creation = CompletableDeferred(); owner.initialOutput = true
                owner.attachment = CompletableDeferred()
                val creating = async(start = CoroutineStart.UNDISPATCHED) {
                    registry.create(runtime, "/bin/sh", "/", rows = 24u, cols = 80u)
                }
                owner.events.emit(SessionEvent.Output("native-b", "DURING_CREATE".toByteArray()))
                val seen = Snapshot()
                while (!seen.text.contains("DURING_CREATE")) {
                    registry.configureTerminal("native-b", 24, 80, 8, 16, Properties(), seen); yield()
                }
                assertFalse(creating.isCompleted)
                owner.creation!!.complete(Unit)
                while (registry.terminalStates.identity("native-new") == null) yield()
                val fresh = Snapshot()
                while (!fresh.text.contains("FIRST")) {
                    registry.configureTerminal("native-new", 24, 80, 8, 16, Properties(), fresh); yield()
                }
                if (runtime == SessionRuntime.AVF) assertFalse("ATTACH remains suspended", creating.isCompleted)
                owner.attachment!!.complete(Unit); creating.await().getOrThrow()
            } } finally { registry.close() }
        }
    }
}
