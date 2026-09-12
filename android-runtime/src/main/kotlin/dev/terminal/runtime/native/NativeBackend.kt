package dev.terminal.runtime.native

import android.content.Context
import dev.terminal.runtime.api.GuestConnection
import dev.terminal.runtime.api.RuntimeBackend
import dev.terminal.runtime.api.RuntimeConfig
import dev.terminal.runtime.api.RuntimeState
import dev.terminal.runtime.api.StopMode
import dev.terminal.protocol.SessionController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Stock-Android fallback: owns local shell processes in :vmhost, never an Activity. */
class NativeBackend(context: Context) : RuntimeBackend {
    private val appContext = context.applicationContext
    private val state = MutableStateFlow<RuntimeState>(RuntimeState.Stopped)
    private val nativeRoot = File(appContext.filesDir, "native")
    private var sessions: NativeSessionController? = null

    override suspend fun prepare(config: RuntimeConfig): Result<Unit> = runCatching {
        state.value = RuntimeState.Preparing
        File(nativeRoot, "home").mkdirs()
        File(nativeRoot, "tmp").mkdirs()
        File(nativeRoot, "README.txt").writeText(
            "TERM Native mode. This is an Android userspace shell; AVF Debian is available on privileged builds.\n",
        )
        state.value = RuntimeState.Stopped
    }.onFailure { state.value = RuntimeState.Failed(it.message ?: "Native userspace setup failed") }

    override suspend fun start(): Result<Unit> = runCatching {
        if (sessions != null) {
            state.value = RuntimeState.Ready
            return@runCatching
        }
        sessions = NativeSessionController(nativeRoot)
        state.value = RuntimeState.Ready
    }.onFailure { state.value = RuntimeState.Failed(it.message ?: "Native shell startup failed") }

    override suspend fun suspendRuntime(): Result<Unit> = Result.failure(UnsupportedOperationException("Native sessions cannot be suspended"))
    override suspend fun resumeRuntime(): Result<Unit> = Result.success(Unit)
    override suspend fun stop(mode: StopMode): Result<Unit> = runCatching { sessions?.close(); sessions = null; state.value = RuntimeState.Stopped }
    override fun observeState(): Flow<RuntimeState> = state.asStateFlow()
    override suspend fun connectGuestAgent(): Result<GuestConnection> = Result.failure(UnsupportedOperationException("Native mode has no guest vsock agent"))

    fun sessionController(): SessionController? = sessions
    fun isReady(): Boolean = sessions != null
}
