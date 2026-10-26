package dev.terminal.runtime.lifecycle

import dev.terminal.protocol.*
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.UnifiedSessionRegistry
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class SessionExitReconcilerTest {
    @Test fun exitRefreshDrainsOutputAheadOfItsGuestResponse() = runBlocking {
        val transport = OutputBeforeListTransport()
        val diagnostics = ConcurrentLinkedQueue<String>()
        val client = ProtocolSessionClient(transport, requestTimeoutMillis = 1_000,
            onDiagnostic = { event, _ -> diagnostics.add(event) })
        val registry = UnifiedSessionRegistry()
        val snapshot = SessionSnapshotPipeline({})
        val refreshed = CompletableDeferred<Unit>()
        val drained = CompletableDeferred<Unit>()
        val failures = ConcurrentLinkedQueue<Exception>()
        registry.register(SessionRuntime.AVF, client)
        registry.list().getOrThrow()
        transport.sessions.forEach { session -> registry.terminalStates.withSession(session.id) { registry.terminalStates.create(session.id, 80, 24) } }
        val reconciler = SessionExitReconciler(this, reconcile = {
            // The service reconciles workspaces before publishing a session snapshot.
            checkNotNull(registry.verifiedWorkspaceSessions(emptySet()))
            snapshot.refresh(registry).getOrThrow()
            refreshed.complete(Unit)
        }, onFailure = { failures.add(it) })
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            var outputs = 0
            registry.events.collect { event ->
                if (event is SessionEvent.Exited) assertTrue(reconciler.request())
                if (event is SessionEvent.Output && ++outputs == 512) drained.complete(Unit)
            }
        }
        try {
            transport.flood.set(true)
            transport.event(SessionEvent.Exited("session-1", 0))
            withTimeout(5_000) { refreshed.await(); drained.await() }
            assertEquals(SessionConnectionState.CONNECTED, client.connectionState.value)
            assertTrue(diagnostics.toString(), diagnostics.isEmpty())
            assertTrue(failures.toString(), failures.isEmpty())
        } finally { reconciler.close(); collector.cancelAndJoin(); registry.close(); client.close() }
    }

    @Test fun exitBurstsCoalesceAndCloseCancelsActiveAndPendingWork() = runBlocking {
        val entered = Channel<Int>(Channel.UNLIMITED)
        val release = Channel<Unit>()
        val stopped = CompletableDeferred<Unit>()
        var calls = 0
        val reconciler = SessionExitReconciler(this, reconcile = {
            val pass = ++calls
            entered.send(pass)
            try { release.receive() }
            finally { if (pass == 2) stopped.complete(Unit) }
        })
        try {
            assertTrue(reconciler.request())
            withTimeout(2_000) { assertEquals(1, entered.receive()) }
            repeat(10_000) { assertTrue(reconciler.request()) }
            assertEquals(1, calls)
            release.send(Unit)
            withTimeout(2_000) { assertEquals(2, entered.receive()) }
            assertTrue(reconciler.request())
            reconciler.close()
            withTimeout(2_000) { stopped.await() }
            assertFalse(reconciler.request())
            assertEquals(2, calls)
            assertTrue(entered.tryReceive().isFailure)
        } finally { reconciler.close() }
    }

    @Test fun failedReconciliationDoesNotDisableLaterExits() = runBlocking {
        val failed = CompletableDeferred<Unit>()
        val recovered = CompletableDeferred<Unit>()
        var calls = 0
        val reconciler = SessionExitReconciler(this, reconcile = {
            if (++calls == 1) throw IOException("Disconnected owner")
            recovered.complete(Unit)
        }, onFailure = { failed.complete(Unit) })
        try {
            assertTrue(reconciler.request())
            withTimeout(2_000) { failed.await() }
            assertTrue(reconciler.request())
            withTimeout(2_000) { recovered.await() }
            assertEquals(2, calls)
        } finally { reconciler.close() }
    }

    private class OutputBeforeListTransport : FrameTransport {
        val incoming = Channel<ProtocolFrame>(Channel.UNLIMITED)
        val flood = AtomicBoolean(false)
        val sessions = listOf("session-1", "session-2").map {
            SessionDescriptor(it, "Shell", "/bin/sh", "/", 0, 24u, 80u, SessionStatus.RUNNING)
        }
        override fun incomingFrames(): Flow<ProtocolFrame> = incoming.receiveAsFlow()
        fun event(value: SessionEvent) {
            check(incoming.trySend(ProtocolFrame(service = Service.SESSION, kind = FrameKind.STREAM,
                requestId = 0, payload = SessionEventCodec.encode(value))).isSuccess)
        }
        override suspend fun send(frame: ProtocolFrame): Result<Unit> {
            check(SessionPayloadCodec.decode(frame.payload) == SessionRequest.List)
            if (flood.compareAndSet(true, false)) {
                repeat(512) { event(SessionEvent.Output("session-2", "working output\r\n".toByteArray())) }
            }
            check(incoming.trySend(frame.copy(kind = FrameKind.RESPONSE,
                payload = SessionResponseCodec.encode(SessionResponse.Listed(sessions)))).isSuccess)
            return Result.success(Unit)
        }
        override fun close() { incoming.close() }
    }
}
