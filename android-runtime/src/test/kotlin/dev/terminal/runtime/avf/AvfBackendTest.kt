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
import org.junit.Assert.assertTrue
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
        driver.events.emit(AvfPlatformEvent.Failed("VM stopped unexpectedly"))

        val state = withTimeout(1_000) {
            backend.observeState().first { it is RuntimeState.Failed }
        }
        assertTrue(state is RuntimeState.Failed)
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
