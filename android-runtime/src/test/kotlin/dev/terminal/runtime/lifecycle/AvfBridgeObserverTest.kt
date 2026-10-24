package dev.terminal.runtime.lifecycle

import dev.terminal.protocol.*
import dev.terminal.runtime.api.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class AvfBridgeObserverTest {
    private class Connection : GuestConnection {
        override val guestCid = 5
        val frames = Channel<ProtocolFrame>(Channel.UNLIMITED)
        val closed = AtomicBoolean()
        override suspend fun send(frame: ProtocolFrame) = Result.success(Unit)
        override fun incomingFrames() = frames.receiveAsFlow()
        override fun close() { closed.set(true); frames.close() }
        suspend fun work(active: Boolean) = frames.send(ProtocolFrame(service = Service.LIFECYCLE, kind = FrameKind.STREAM,
            requestId = 0, payload = LifecyclePayloadCodec.encode(
                if (active) GuestWorkEvent.WORK_ACTIVE else GuestWorkEvent.WORK_IDLE)))
    }

    private class Owner : RuntimeBackend {
        val state = MutableStateFlow<RuntimeState>(RuntimeState.Stopped)
        val offers = Channel<Connection>(Channel.UNLIMITED)
        val attempts = AtomicInteger()
        override fun observeState() = state
        override suspend fun connectGuestBridge(): Result<GuestConnection> {
            attempts.incrementAndGet()
            return Result.success(offers.receive())
        }
        override suspend fun prepare(config: RuntimeConfig) = Result.success(Unit)
        override suspend fun start() = Result.success(Unit)
        override suspend fun stop(mode: StopMode) = Result.success(Unit)
        override suspend fun suspendRuntime() = Result.success(Unit)
        override suspend fun resumeRuntime() = Result.success(Unit)
        override suspend fun connectGuestAgent() = Result.failure<GuestConnection>(UnsupportedOperationException())
    }

    private fun observer(scope: CoroutineScope, id: String, owner: Owner, demand: AtomicBoolean, active: AtomicBoolean) =
        AvfBridgeObserver(scope, id, owner, demand::get, active::set,
            serve = { transport, work -> transport.incomingFrames().collect { frame ->
                work(LifecyclePayloadCodec.decode(frame.payload) == GuestWorkEvent.WORK_ACTIVE)
            } }, retry = ReconnectBackoff(listOf(10)))

    private suspend fun until(predicate: () -> Boolean) = withTimeout(3_000) { while (!predicate()) delay(5) }

    @Test fun twoAvfEnvironmentsConnectAndRecoverIndependentlyWithoutAnotherRuntime() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = Owner(); val second = Owner()
        val firstWork = AtomicBoolean(); val secondWork = AtomicBoolean()
        val demand = AtomicBoolean(true)
        val a = observer(scope, "first", first, demand, firstWork)
        val b = observer(scope, "second", second, demand, secondWork)
        try {
            val firstLink = Connection(); val secondLink = Connection()
            first.offers.send(firstLink); second.offers.send(secondLink)
            first.state.value = RuntimeState.Ready; second.state.value = RuntimeState.Ready
            firstLink.work(true); secondLink.work(true)
            until { firstWork.get() && secondWork.get() }
            val replacement = Connection(); first.offers.send(replacement)
            firstLink.close()
            until { first.attempts.get() == 2 }
            replacement.work(true)
            until { firstWork.get() }
            assertEquals(1, second.attempts.get()); assertFalse(secondLink.closed.get())
            first.state.value = RuntimeState.Stopped
            until { replacement.closed.get() && !firstWork.get() }
            assertTrue(secondWork.get()); assertFalse(secondLink.closed.get())
        } finally { a.close(); b.close(); scope.cancel() }
    }

    @Test fun readyEnvironmentWaitsForItsOwnWorkBeforeConnecting() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val owner = Owner(); val demand = AtomicBoolean(false); val active = AtomicBoolean()
        owner.state.value = RuntimeState.Ready
        val observer = observer(scope, "idle", owner, demand, active)
        try {
            delay(30)
            assertEquals(0, owner.attempts.get())
            val link = Connection(); owner.offers.send(link)
            demand.set(true); observer.reconcile()
            link.work(true); until { active.get() }
            demand.set(false); link.close()
            until { !active.get() }
            delay(30)
            assertEquals(1, owner.attempts.get())
        } finally { observer.close(); scope.cancel() }
    }
}
