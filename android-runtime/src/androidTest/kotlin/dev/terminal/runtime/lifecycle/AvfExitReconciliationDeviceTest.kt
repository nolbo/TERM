package dev.terminal.runtime.lifecycle

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.terminal.protocol.*
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.UnifiedSessionRegistry
import dev.terminal.runtime.vsock.ParcelFileDescriptorByteStream
import dev.terminal.runtime.vsock.ParcelFileDescriptorFrameTransport
import java.io.DataInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android socket I/O, using the same collector/reconciler as the runtime service. */
@RunWith(AndroidJUnit4::class)
class AvfExitReconciliationDeviceTest {
    @Test fun exitRefreshKeepsNativeFrameReaderAndSiblingOutputMoving() = runBlocking {
        val sockets = ParcelFileDescriptor.createSocketPair()
        val peer = ParcelFileDescriptorByteStream(sockets[1])
        val incoming = DataInputStream(peer.input)
        val peerWrites = Mutex()
        suspend fun send(frame: ProtocolFrame) = peerWrites.withLock {
            withContext(Dispatchers.IO) { peer.output.write(Protocol.encode(frame)); peer.output.flush() }
        }
        fun event(value: SessionEvent) = ProtocolFrame(service = Service.SESSION, kind = FrameKind.STREAM,
            requestId = 0, payload = SessionEventCodec.encode(value))
        val flood = AtomicBoolean(false)
        val closing = AtomicBoolean(false)
        val completed = CompletableDeferred<Unit>()
        val drained = CompletableDeferred<Unit>()
        val sessions = listOf("session-1", "session-2").map {
            SessionDescriptor(it, "Shell", "/bin/sh", "/", 0, 24u, 80u, SessionStatus.RUNNING)
        }
        val server = launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val request = Protocol.read(incoming)
                    check(SessionPayloadCodec.decode(request.payload) == SessionRequest.List)
                    if (flood.compareAndSet(true, false)) {
                        repeat(512) { send(event(SessionEvent.Output("session-2", "output\r\n".toByteArray()))) }
                    }
                    send(request.copy(kind = FrameKind.RESPONSE,
                        payload = SessionResponseCodec.encode(SessionResponse.Listed(sessions))))
                }
            } catch (error: Exception) { if (!closing.get()) completed.completeExceptionally(error) }
        }
        val client = ProtocolSessionClient(ParcelFileDescriptorFrameTransport(sockets[0], 0), requestTimeoutMillis = 2_000)
        val registry = UnifiedSessionRegistry()
        val snapshot = SessionSnapshotPipeline({})
        val reconciler = SessionExitReconciler(this, reconcile = {
            checkNotNull(registry.verifiedWorkspaceSessions(emptySet()))
            snapshot.refresh(registry).getOrThrow()
            completed.complete(Unit)
        }, onFailure = { completed.completeExceptionally(it) })
        var collector: Job? = null
        try {
            registry.register(SessionRuntime.AVF, client)
            registry.list().getOrThrow()
            sessions.forEach { session -> registry.terminalStates.withSession(session.id) { registry.terminalStates.create(session.id, 80, 24) } }
            collector = launch(start = CoroutineStart.UNDISPATCHED) {
                var outputs = 0
                registry.events.collect { event ->
                    if (event is SessionEvent.Exited) assertTrue(reconciler.request())
                    if (event is SessionEvent.Output && ++outputs == 512) drained.complete(Unit)
                }
            }
            flood.set(true)
            send(event(SessionEvent.Exited("session-1", 0)))
            withTimeout(10_000) { completed.await(); drained.await() }
            assertEquals(SessionConnectionState.CONNECTED, client.connectionState.value)
        } finally {
            closing.set(true)
            reconciler.close()
            collector?.cancelAndJoin()
            registry.close()
            client.close()
            peer.close()
            withTimeout(2_000) { server.join() }
        }
    }
}
