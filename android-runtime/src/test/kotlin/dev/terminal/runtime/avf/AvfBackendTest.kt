package dev.terminal.runtime.avf

import dev.terminal.runtime.api.GuestConnection
import dev.terminal.runtime.api.RuntimeConfig
import dev.terminal.runtime.api.RuntimeState
import dev.terminal.runtime.api.StopMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AvfBackendTest {
    @Test
    fun failedPreparationIsVisibleAsRuntimeFailure() = runBlocking {
        val backend = AvfBackend(FailingDriver)

        val result = backend.prepare(RuntimeConfig(imageDirectory = "/images/debian"))

        assertTrue(result.isFailure)
        assertTrue(backend.observeState().first() is RuntimeState.Failed)
    }

    @Test
    fun asynchronousPlatformFailureReplacesReadyState() = runBlocking {
        val driver = EventingDriver()
        val backend = AvfBackend(driver)

        assertTrue(backend.prepare(RuntimeConfig(imageDirectory = "/images/debian")).isSuccess)
        assertTrue(backend.start().isSuccess)
        val notice = async(start = CoroutineStart.UNDISPATCHED) { backend.observeEvents().first() }
        driver.events.emit(AvfPlatformEvent.Failed("VM stopped unexpectedly"))
        notice.await()

        val state = withTimeout(1_000) {
            backend.observeState().first { it is RuntimeState.Failed }
        }
        assertTrue(state is RuntimeState.Failed)
    }

    @Test fun storageDeletionRequiresProviderSupportAndPassesExactIdentity() = runBlocking {
        val storage = java.util.UUID.randomUUID().toString()
        assertTrue(AvfBackend(FailingDriver).deleteStorage(storage).isFailure)
        var requested: String? = null
        val supported = object : AvfPlatformDriver by FailingDriver {
            override suspend fun deleteStorage(instanceId: String): Result<Unit> {
                requested = instanceId
                return Result.success(Unit)
            }
        }
        assertTrue(AvfBackend(supported).deleteStorage(storage).isSuccess)
        org.junit.Assert.assertEquals(storage, requested)
    }

    @Test fun sharingLossIsObservableWithoutClaimingThatTheVmStopped() = runBlocking {
        val driver = EventingDriver()
        val backend = AvfBackend(driver)
        backend.prepare(RuntimeConfig(imageDirectory = "/image")).getOrThrow(); backend.start().getOrThrow()
        val notice = async(start = CoroutineStart.UNDISPATCHED) { backend.observeEvents().first() }
        driver.events.emit(AvfPlatformEvent.SharingUnavailable("share.owner.unavailable"))
        assertTrue(withTimeout(1_000) { notice.await() } is AvfPlatformEvent.SharingUnavailable)
        assertTrue(backend.observeState().value is RuntimeState.Ready)
    }

    @Test fun onlyPlatformProofCanReconcileAFailedOwnerAsStopped() = runBlocking {
        val events = EventingDriver()
        var stopped = false
        val backend = AvfBackend(object : AvfPlatformDriver by events {
            override suspend fun verifyStoppedInstance(instanceId: String): Result<Unit> =
                if (stopped) Result.success(Unit) else Result.failure(IllegalStateException("Unknown VM state"))
        })
        backend.prepare(RuntimeConfig(instanceId = "owner", imageDirectory = "/image")).getOrThrow(); backend.start().getOrThrow()
        val notice = async(start = CoroutineStart.UNDISPATCHED) { backend.observeEvents().first() }
        events.events.emit(AvfPlatformEvent.Failed("VM lost"))
        notice.await()
        withTimeout(1_000) { backend.observeState().first { it is RuntimeState.Failed } }
        assertTrue(backend.verifyStoppedInstance("owner").isFailure)
        assertTrue(backend.observeState().value is RuntimeState.Failed)
        stopped = true
        assertTrue(backend.verifyStoppedInstance("owner").isSuccess)
        assertTrue(backend.observeState().value is RuntimeState.Stopped)
    }

    @Test fun observationEndsWithItsCollectorWithoutStoppingTheVm() = runBlocking {
        val driver = EventingDriver()
        val backend = AvfBackend(driver)
        assertEquals(0, driver.events.subscriptionCount.value)
        val notice = async(start = CoroutineStart.UNDISPATCHED) { backend.observeEvents().first() }
        assertEquals(1, driver.events.subscriptionCount.value)
        notice.cancel(); notice.join()
        assertEquals(0, driver.events.subscriptionCount.value)
        assertTrue(backend.prepare(RuntimeConfig(imageDirectory = "/image")).isSuccess)
        assertTrue(backend.start().isSuccess)
    }

    private object FailingDriver : AvfPlatformDriver {
        override suspend fun prepare(config: RuntimeConfig): Result<Unit> =
            Result.failure(IllegalStateException("no platform AVF"))

        override suspend fun start(config: RuntimeConfig): Result<Unit> = Result.success(Unit)

        override suspend fun stop(mode: StopMode): Result<Unit> = Result.success(Unit)

        override suspend fun connectGuestAgent(): Result<GuestConnection> =
            Result.failure(IllegalStateException("not connected"))
    }

    private class EventingDriver : AvfPlatformDriver {
        val events = MutableSharedFlow<AvfPlatformEvent>(replay = 1)

        override suspend fun prepare(config: RuntimeConfig): Result<Unit> = Result.success(Unit)

        override suspend fun start(config: RuntimeConfig): Result<Unit> = Result.success(Unit)

        override suspend fun stop(mode: StopMode): Result<Unit> = Result.success(Unit)

        override suspend fun connectGuestAgent(): Result<GuestConnection> =
            Result.failure(IllegalStateException("not connected"))

        override fun observeEvents(): Flow<AvfPlatformEvent> = events
    }
}
