package dev.terminal.runtime.avf

import dev.terminal.runtime.api.GuestConnection
import dev.terminal.runtime.api.GuestByteStream
import dev.terminal.runtime.api.RuntimeBackend
import dev.terminal.runtime.api.RuntimeConfig
import dev.terminal.runtime.api.RuntimeState
import dev.terminal.runtime.api.StopMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * AVF backend state machine. The platform-specific driver is injected so this
 * module compiles on the public SDK and can be tested without an AVF device.
 */
class AvfBackend(
    private val driver: AvfPlatformDriver,
) : RuntimeBackend {
    val startedNewVm: Boolean get() = driver.startedNewVm
    suspend fun connectImageInitializer(): Result<GuestConnection> = driver.connectImageInitializer()
    private val mutex = Mutex()
    private val state = MutableStateFlow<RuntimeState>(RuntimeState.Stopped)
    private var config: RuntimeConfig? = null

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            driver.observeEvents().collect { event ->
                mutex.withLock {
                    state.value = when (event) {
                        is AvfPlatformEvent.Failed -> RuntimeState.Failed(event.reason)
                        AvfPlatformEvent.Stopped -> RuntimeState.Stopped
                    }
                }
            }
        }
    }

    override suspend fun prepare(config: RuntimeConfig): Result<Unit> = mutex.withLock {
        if (state.value !is RuntimeState.Stopped && state.value !is RuntimeState.Failed) {
            return@withLock Result.failure(IllegalStateException("Runtime is already prepared"))
        }
        state.value = RuntimeState.Preparing
        driver.prepare(config).onSuccess {
            this.config = config
            state.value = RuntimeState.Stopped
        }.onFailure { state.value = RuntimeState.Failed(it.message ?: "AVF preparation failed") }
    }

    override suspend fun start(): Result<Unit> = mutex.withLock {
        val preparedConfig = config ?: return@withLock Result.failure(
            IllegalStateException("Prepare an image before starting the runtime"),
        )
        state.value = RuntimeState.Booting
        driver.start(preparedConfig).onSuccess {
            state.value = RuntimeState.Ready
        }.onFailure { state.value = RuntimeState.Failed(it.message ?: "AVF boot failed") }
    }

    override suspend fun suspendRuntime(): Result<Unit> = mutex.withLock {
        state.value = RuntimeState.Suspended
        Result.success(Unit)
    }

    override suspend fun resumeRuntime(): Result<Unit> = mutex.withLock {
        state.value = RuntimeState.Ready
        Result.success(Unit)
    }

    override suspend fun stop(mode: StopMode): Result<Unit> = mutex.withLock {
        state.value = RuntimeState.Stopping
        driver.stop(mode).onSuccess {
            state.value = RuntimeState.Stopped
        }.onFailure { state.value = RuntimeState.Failed(it.message ?: "AVF stop failed") }
    }

    /** Provider must verify a stopped persistent instance, including after host process restart. */
    suspend fun deleteStorage(instanceId: String): Result<Unit> = mutex.withLock { driver.deleteStorage(instanceId) }

    override fun observeState(): StateFlow<RuntimeState> = state.asStateFlow()

    override suspend fun connectGuestAgent(): Result<GuestConnection> = driver.connectGuestAgent()

    override suspend fun connectGuestBridge(): Result<GuestConnection> = driver.connectGuestBridge()

    override suspend fun connectGuestPortAgent(): Result<GuestConnection> = driver.connectGuestPortAgent()

    override suspend fun openGuestPortForward(port: Int): Result<GuestByteStream> = driver.openGuestPortForward(port)
}

/** Implemented in the AOSP/system-SDK deployment module in the next spike. */
interface AvfPlatformDriver {
    /** True only if the latest successful start booted a stopped VM, not a reconnect. */
    val startedNewVm: Boolean get() = false
    suspend fun connectImageInitializer(): Result<GuestConnection> = Result.failure(
        UnsupportedOperationException("이 Debian 이미지는 자동 패키지 목록 초기화를 지원하지 않습니다."))
    /** Must fully verify the installed image before accepting it for [start]. */
    suspend fun prepare(config: RuntimeConfig): Result<Unit>
    suspend fun start(config: RuntimeConfig): Result<Unit>
    suspend fun stop(mode: StopMode): Result<Unit>
    suspend fun connectGuestAgent(): Result<GuestConnection>
    suspend fun deleteStorage(instanceId: String): Result<Unit> = Result.failure(
        UnsupportedOperationException("AVF provider cannot verify and delete persistent storage"),
    )
    suspend fun connectGuestBridge(): Result<GuestConnection> = Result.failure(
        UnsupportedOperationException("Guest capability bridge is unavailable"),
    )
    suspend fun connectGuestPortAgent(): Result<GuestConnection> = Result.failure(
        UnsupportedOperationException("Guest port agent is unavailable"),
    )
    suspend fun openGuestPortForward(port: Int): Result<GuestByteStream> = Result.failure(
        UnsupportedOperationException("Guest port forwarding is unavailable"),
    )

    /** Reports asynchronous AVF death/errors that occur after [start] completed. */
    fun observeEvents(): Flow<AvfPlatformEvent> = emptyFlow()
}

sealed interface AvfPlatformEvent {
    data class Failed(val reason: String) : AvfPlatformEvent
    data object Stopped : AvfPlatformEvent
}
