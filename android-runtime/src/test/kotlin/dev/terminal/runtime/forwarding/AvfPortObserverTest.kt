package dev.terminal.runtime.forwarding

import dev.terminal.protocol.*
import dev.terminal.runtime.api.*
import dev.terminal.runtime.lifecycle.ReconnectBackoff
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AvfPortObserverTest {
    private class Connection : GuestConnection {
        override val guestCid = 5
        val frames = Channel<ProtocolFrame>(Channel.UNLIMITED)
        val closed = AtomicBoolean()
        override suspend fun send(frame: ProtocolFrame) = Result.success(Unit)
        override fun incomingFrames() = frames.receiveAsFlow()
        override fun close() { closed.set(true); frames.close() }
        suspend fun event(event: PortEvent) { frames.send(frame(PortPayloadCodec.encode(event))) }
    }
    private class Backend : RuntimeBackend {
        val state = MutableStateFlow<RuntimeState>(RuntimeState.Stopped)
        val offers = Channel<Connection>(Channel.UNLIMITED)
        val attempts = AtomicInteger()
        override fun observeState() = state
        override suspend fun connectGuestPortAgent(): Result<GuestConnection> { attempts.incrementAndGet(); return Result.success(offers.receive()) }
        override suspend fun connectGuestAgent(): Result<GuestConnection> = Result.failure(Exception("unused"))
        override suspend fun prepare(config: RuntimeConfig) = Result.success(Unit)
        override suspend fun start() = Result.success(Unit)
        override suspend fun suspendRuntime() = Result.success(Unit)
        override suspend fun resumeRuntime() = Result.success(Unit)
        override suspend fun stop(mode: StopMode) = Result.success(Unit)
    }
    private class Driver : PortForwardDriver {
        val active = mutableSetOf<String>()
        @Synchronized override fun replace(id: String, guestPort: Int, address: String, hostPort: Int, commit: () -> Unit): Result<Unit> = runCatching { commit(); active.add(id); Unit }
        @Synchronized override fun stop(id: String) { active.remove(id) }
        @Synchronized override fun close() { active.clear() }
    }
    @Test fun malformedPayloadsAndOtherServicesDoNotInterruptSubsequentReports() = runBlocking {
        val valid = PortEvent.Listening(8000)
        val received = mutableListOf<PortEvent>()
        val transport = object : FrameTransport {
            override suspend fun send(frame: ProtocolFrame) = Result.success(Unit)
            override fun incomingFrames() = flowOf(frame(byteArrayOf(127)), frame(byteArrayOf(2, 0, 0)),
                frame(ByteArray(181)), frame(PortPayloadCodec.encode(valid)).copy(service = Service.BRIDGE), frame(PortPayloadCodec.encode(valid)))
            override fun close() {}
        }
        PortAgentCollector { received.add(it) }.collect(transport)
        assertEquals(listOf(valid), received)
    }
    @Test fun disconnectReconnectAndOwnerStopOnlyReleaseTheirOwnListeners() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = LocalServiceManager()
        val backend = Backend()
        val firstDriver = Driver(); val otherDriver = Driver()
        manager.attach("first", firstDriver); manager.attach("other", otherDriver)
        manager.event("other", PortEvent.SnapshotBegin); manager.event("other", PortEvent.EndpointListening(8000, "::1"))
        val working = AtomicBoolean(true)
        val observer = AvfPortObserver(scope, "first", backend, manager, { working.get() }, {}, ReconnectBackoff(listOf(10)))
        suspend fun until(predicate: () -> Boolean) { withTimeout(3000) { while (!predicate()) delay(5) } }
        try {
            val connection = Connection(); backend.offers.send(connection)
            backend.state.value = RuntimeState.Ready
            connection.event(PortEvent.SnapshotBegin); connection.event(PortEvent.EndpointListening(8000, "127.0.0.1")); connection.event(PortEvent.SnapshotEnd)
            until { manager.registry.services.value.any { it.environmentId == "first" && it.forwarding == ForwardingState.ACTIVE } }
            working.set(false); connection.close()
            until { manager.registry.services.value.any { it.environmentId == "first" && it.observation == ServiceObservation.DISCONNECTED } }
            assertEquals(1, backend.attempts.get())
            assertEquals(ForwardingState.ACTIVE, manager.registry.services.value.single { it.environmentId == "other" }.forwarding)
            working.set(true)
            val second = Connection(); backend.offers.send(second); observer.reconcile()
            second.event(PortEvent.SnapshotBegin); second.event(PortEvent.SnapshotEnd)
            until { manager.registry.services.value.any { it.environmentId == "first" && it.observation == ServiceObservation.CLOSED } }
            backend.state.value = RuntimeState.Stopped
            until { second.closed.get() }
            assertFalse(otherDriver.active.isEmpty())
        } finally { observer.close(); scope.cancel(); manager.close() }
    }
    companion object {
        private fun frame(payload: ByteArray) = ProtocolFrame(service = Service.PORT, kind = FrameKind.STREAM, requestId = 0, payload = payload)
    }
}
