package dev.terminal.runtime.lifecycle

import dev.terminal.runtime.api.GuestConnection
import dev.terminal.runtime.api.RuntimeBackend
import dev.terminal.runtime.api.RuntimeConfig
import dev.terminal.runtime.api.RuntimeState
import dev.terminal.runtime.api.StopMode
import dev.terminal.runtime.avf.AvfBackend
import dev.terminal.runtime.avf.AvfPlatformDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AvfRuntimeAvailabilityTest {
    @Test fun readyNativeOwnersDoNotClaimAvfReadinessOrRequireAnotherProductBuild() {
        val state = avfRuntimeAvailability(NativeOwner, listOf(NativeOwner))
        assertFalse(state.available)
        assertFalse(state.detail.contains("특권 AVF 제품 빌드"))
        assertTrue(state.detail.contains("프로필을 선택"))
    }

    @Test fun independentAvfOwnerIsReadyUnderNativeAndBecomesUnavailableWhenStopped() = runBlocking {
        val avf = AvfBackend(Driver)
        avf.prepare(RuntimeConfig(imageDirectory = "/test/image")).getOrThrow()
        avf.start().getOrThrow()
        assertTrue(avfRuntimeAvailability(NativeOwner, listOf(avf)).available)
        assertTrue(avfRuntimeAvailability(avf, emptyList()).available)
        avf.stop(StopMode.FORCE).getOrThrow()
        assertFalse(avfRuntimeAvailability(NativeOwner, listOf(avf)).available)
    }

    @Test fun aFailedSiblingDoesNotHideAReadyIndependentAvfOwner() = runBlocking {
        val failed = AvfBackend(object : AvfPlatformDriver by Driver {
            override suspend fun prepare(config: RuntimeConfig) = Result.failure<Unit>(IllegalStateException("bad image"))
        })
        failed.prepare(RuntimeConfig(imageDirectory = "/test/failed"))
        assertEquals("bad image", avfRuntimeAvailability(NativeOwner, listOf(failed)).detail)
        val ready = AvfBackend(Driver)
        ready.prepare(RuntimeConfig(imageDirectory = "/test/ready")).getOrThrow()
        ready.start().getOrThrow()
        assertTrue(avfRuntimeAvailability(NativeOwner, listOf(failed, ready)).available)
    }

    @Test fun aPendingAvfBootDoesNotBorrowNativeReadiness() = runBlocking {
        val boot = CompletableDeferred<Unit>()
        val avf = AvfBackend(object : AvfPlatformDriver by Driver {
            override suspend fun start(config: RuntimeConfig): Result<Unit> { boot.await(); return Result.success(Unit) }
        })
        avf.prepare(RuntimeConfig(imageDirectory = "/test/boot")).getOrThrow()
        val pending = launch { avf.start().getOrThrow() }
        avf.observeState().first { it is RuntimeState.Booting }
        val state = avfRuntimeAvailability(NativeOwner, listOf(avf))
        assertFalse(state.available)
        assertTrue(state.detail.contains("시작하고"))
        boot.complete(Unit)
        pending.join()
        assertTrue(avfRuntimeAvailability(NativeOwner, listOf(avf)).available)
    }

    private object Driver : AvfPlatformDriver {
        override suspend fun prepare(config: RuntimeConfig) = Result.success(Unit)
        override suspend fun start(config: RuntimeConfig) = Result.success(Unit)
        override suspend fun stop(mode: StopMode) = Result.success(Unit)
        override suspend fun connectGuestAgent() = Result.failure<GuestConnection>(UnsupportedOperationException("test"))
    }

    private object NativeOwner : RuntimeBackend {
        override fun observeState() = MutableStateFlow<RuntimeState>(RuntimeState.Ready)
        override suspend fun prepare(config: RuntimeConfig) = Result.success(Unit)
        override suspend fun start() = Result.success(Unit)
        override suspend fun stop(mode: StopMode) = Result.success(Unit)
        override suspend fun suspendRuntime() = Result.success(Unit)
        override suspend fun resumeRuntime() = Result.success(Unit)
        override suspend fun connectGuestAgent() = Result.failure<GuestConnection>(UnsupportedOperationException("native"))
    }
}
