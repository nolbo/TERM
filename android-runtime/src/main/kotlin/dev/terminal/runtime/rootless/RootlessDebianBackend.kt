package dev.terminal.runtime.rootless

import android.content.Context
import dev.terminal.runtime.api.GuestConnection
import dev.terminal.runtime.api.RuntimeBackend
import dev.terminal.runtime.api.RuntimeConfig
import dev.terminal.runtime.api.RuntimeState
import dev.terminal.runtime.api.StopMode
import dev.terminal.runtime.native.ApkNativeExecutableLoader
import dev.terminal.runtime.native.NativeExecutableLoader
import dev.terminal.protocol.SessionController
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Permissionless Debian/glibc runtime. PRoot and its loader must be immutable APK payloads. */
class RootlessDebianBackend(
    context: Context,
    private val executableLoader: NativeExecutableLoader = ApkNativeExecutableLoader(context),
    private val reporter: RootlessProgressReporter = RootlessProgressReporter.NONE,
) : RuntimeBackend {
    private val appContext = context.applicationContext
    private val state = MutableStateFlow<RuntimeState>(RuntimeState.Stopped)
    private val layout = RootlessDebianLayout(File(appContext.filesDir, "rootless/debian"))
    private val bootstrapper = RootlessBootstrapper(appContext, layout, reporter)
    private val prepareMutex = Mutex()
    private var controller: RootlessSessionController? = null
    private var proot: File? = null
    private var loader: File? = null

    override suspend fun prepare(config: RuntimeConfig): Result<Unit> = prepareMutex.withLock {
        runCatching {
            state.value = RuntimeState.Preparing
            layout.createHostDirectories()
            reporter.report(RootlessPhase.EXECUTABLE, "APK에 포함된 ARM64 PRoot와 loader를 확인하고 있습니다.")
            require("arm64-v8a" in android.os.Build.SUPPORTED_ABIS) {
                "지원되지 않는 ABI입니다: ${android.os.Build.SUPPORTED_ABIS.joinToString()} (필요: arm64-v8a)"
            }
            proot = executableLoader.resolvePackagedExecutable("proot").getOrElse {
                throw RootlessSetupException(RootlessPhase.EXECUTABLE, it)
            }
            loader = executableLoader.resolvePackagedExecutable("proot-loader").getOrElse {
                throw RootlessSetupException(RootlessPhase.EXECUTABLE, it)
            }
            bootstrapper.installIfNeeded().getOrThrow()
            layout.validateInstalledRootfs().getOrThrow()
            state.value = RuntimeState.Stopped
        }.onFailure {
            proot = null
            loader = null
            state.value = RuntimeState.Failed(it.message ?: "Rootless Debian setup failed")
        }
    }

    override suspend fun start(): Result<Unit> = runCatching {
        if (controller != null) {
            state.value = RuntimeState.Ready
            return@runCatching
        }
        val executable = proot ?: error("Rootless Debian must be prepared before start")
        val loaderExecutable = loader ?: error("Rootless Debian loader must be prepared before start")
        controller = RootlessSessionController(layout, executable, loaderExecutable)
        state.value = RuntimeState.Ready
    }.onFailure { state.value = RuntimeState.Failed(it.message ?: "Rootless Debian startup failed") }

    override suspend fun suspendRuntime(): Result<Unit> = Result.failure(UnsupportedOperationException("PRoot cannot suspend a userspace"))
    override suspend fun resumeRuntime(): Result<Unit> = Result.success(Unit)
    override suspend fun stop(mode: StopMode): Result<Unit> = runCatching {
        controller?.close()
        controller = null
        state.value = RuntimeState.Stopped
    }
    override fun observeState(): Flow<RuntimeState> = state.asStateFlow()
    override suspend fun connectGuestAgent(): Result<GuestConnection> = Result.failure(UnsupportedOperationException("Rootless Debian uses local IPC"))

    fun sessionController(): SessionController? = controller
    fun status(): RootlessStatus = when (val current = state.value) {
        RuntimeState.Ready, RuntimeState.Active, RuntimeState.Idle -> RootlessStatus.Ready
        RuntimeState.Preparing, RuntimeState.Booting -> RootlessStatus.Checking
        is RuntimeState.Failed -> RootlessStatus.Error(current.reason)
        else -> layout.validateInstalledRootfs().fold(
            onSuccess = {
                executableLoader.resolvePackagedExecutable("proot").fold(
                    onSuccess = {
                        executableLoader.resolvePackagedExecutable("proot-loader").fold(
                            onSuccess = { RootlessStatus.Ready },
                            onFailure = { RootlessStatus.SetupRequired(it.message ?: "APK PRoot loader가 필요합니다") },
                        )
                    },
                    onFailure = { RootlessStatus.SetupRequired(it.message ?: "APK PRoot가 필요합니다") },
                )
            },
            onFailure = { RootlessStatus.SetupRequired(it.message ?: "Debian rootfs가 필요합니다") },
        )
    }
}

sealed interface RootlessStatus {
    data object Checking : RootlessStatus
    data object Ready : RootlessStatus
    data class Error(val reason: String) : RootlessStatus
    data class SetupRequired(val reason: String) : RootlessStatus
}

class RootlessDebianLayout(val root: File) {
    val rootfs = File(root, "rootfs")
    val home = File(root, "home")
    val tmp = File(root, "tmp")
    val workspace = File(requireNotNull(root.parentFile?.parentFile) { "Rootless root must have an application parent" }, "workspace")

    fun createHostDirectories() {
        listOf(root, home, tmp, workspace).forEach { require(it.mkdirs() || it.isDirectory) { "Cannot create ${it.path}" } }
    }

    fun validateInstalledRootfs(): Result<Unit> = runCatching {
        require(rootfs.isDirectory) { "Rootless Debian rootfs가 설치되지 않았습니다" }
        require(File(rootfs, "bin/sh").canExecute()) { "Debian rootfs의 /bin/sh가 없거나 실행할 수 없습니다" }
        require(File(rootfs, "usr/bin/apt").canExecute() || File(rootfs, "usr/bin/apt-get").canExecute()) {
            "Debian rootfs의 apt가 없거나 실행할 수 없습니다"
        }
    }
}
