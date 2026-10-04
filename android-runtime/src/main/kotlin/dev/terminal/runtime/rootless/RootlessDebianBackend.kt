package dev.terminal.runtime.rootless

import android.content.Context
import android.os.Environment
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

/** Permissionless distribution/glibc runtime. PRoot and its loader must be immutable APK payloads. */
class RootlessDebianBackend(
    context: Context,
    private val executableLoader: NativeExecutableLoader = ApkNativeExecutableLoader(context),
    private val distribution: RootlessDistribution = RootlessDistribution.DEBIAN,
    private val usbBroker: dev.terminal.runtime.usb.UsbBroker? = null,
    storageRoot: File? = null,
    reporter: RootlessProgressReporter = RootlessProgressReporter.NONE,
) : RuntimeBackend {
    private val appContext = context.applicationContext
    private val state = MutableStateFlow<RuntimeState>(RuntimeState.Stopped)
    private val progressTracker = RootlessProgressTracker(android.os.SystemClock::elapsedRealtime)
    private val progressReporter = object : RootlessProgressReporter {
        override fun report(phase: RootlessPhase, detail: String) = update(RootlessProgressUpdate(phase, detail))
        override fun update(progress: RootlessProgressUpdate) {
            if (progressTracker.record(progress)) reporter.report(progress.phase, progressTracker.latest!!.update.detail)
        }
    }
    private val layout = RootlessDebianLayout(storageRoot ?: File(appContext.filesDir, "rootless/${distribution.directory}"), distribution,
        workspaceRoot = storageRoot?.let { File(it, "workspace") })
    private val bootstrapper = RootlessBootstrapper(appContext, layout, progressReporter)
    private val prepareMutex = Mutex()
    private var controller: RootlessSessionController? = null
    private var proot: File? = null
    private var loader: File? = null

    suspend fun prepare(): Result<Unit> = prepare(RuntimeConfig(imageDirectory = layout.root.path))

    override suspend fun prepare(config: RuntimeConfig): Result<Unit> = prepareMutex.withLock {
        // Do not run package operations or replace a controller underneath live sessions.
        if (RootlessEnvironmentProfile.validate(layout).isFailure && controller?.list()?.getOrThrow().orEmpty().isNotEmpty()) {
            return@withLock Result.failure(IllegalStateException("이 배포판의 모든 세션을 종료한 뒤 환경 보완을 실행하세요"))
        }
        runCatching {
            check(!RootlessNativeMigration.pending(layout.root.parentFile.toPath())) {
                "중단된 가속 형식 이전이 있습니다. 설정에서 중단된 이전 복구를 실행하세요"
            }
            if (progressTracker.latest?.update?.phase != RootlessPhase.WAITING) progressTracker.reset()
            state.value = RuntimeState.Preparing
            layout.createHostDirectories()
            progressReporter.report(RootlessPhase.EXECUTABLE, "셸 실행에 필요한 파일을 확인하고 있습니다")
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
            if (RootlessEnvironmentProfile.validate(layout).isFailure) {
                controller?.close()
                controller = null
                RootlessEnvironmentSetup(appContext, layout, requireNotNull(proot), requireNotNull(loader), progressReporter).prepare()
            } else configureRootlessLoginHome(layout)
            state.value = if (controller == null) RuntimeState.Stopped else RuntimeState.Ready
            progressReporter.report(RootlessPhase.READY, "${distribution.displayName} 기본 구성과 셸 실행을 확인했습니다")
            progressTracker.finish()
        }.onFailure {
            proot = null
            loader = null
            state.value = RuntimeState.Failed(it.message ?: "${distribution.displayName} setup failed")
            progressTracker.finish()
        }
    }

    // Kept for IPC compatibility: the button now only recovers an interrupted
    // old conversion. Acceleration no longer requires changing storage format.
    fun canMigrateToNative(): Boolean = dev.terminal.runtime.BuildConfig.ROOTLESS_ACCELERATION &&
        RootlessNativeMigration.pending(layout.root.parentFile.toPath()) &&
        RootlessNativeMigration.pendingOwner(layout.root.parentFile.toPath()) == distribution

    suspend fun migrateToNative(): Result<Unit> = prepareMutex.withLock {
        runCatching {
            check(dev.terminal.runtime.BuildConfig.ROOTLESS_ACCELERATION) { "가속 기능이 포함된 앱 버전이 필요합니다" }
            check(controller?.list()?.getOrThrow().orEmpty().isEmpty()) { "이 배포판의 세션을 먼저 종료하세요" }
            state.value = RuntimeState.Preparing
            progressTracker.reset()
            progressReporter.report(RootlessPhase.MIGRATE, "기존 패키지와 파일을 유지하며 중단된 이전을 복구하고 있습니다")
            controller?.close()
            controller = null
            val migration = RootlessNativeMigration(layout)
            migration.recover()
            layout.validateInstalledRootfs().getOrThrow()
            state.value = RuntimeState.Stopped
            progressReporter.report(RootlessPhase.READY, "복구를 완료했습니다. 저장 형식 변경 없이 새 세션부터 가속을 적용합니다")
        }.onFailure { state.value = RuntimeState.Failed(it.message ?: "중단된 이전 복구 실패") }
            .also { progressTracker.finish() }
    }

    // The service excludes controller startup/session creation while removing an image.
    suspend fun remove(removeHome: Boolean = false): Result<Unit> = prepareMutex.withLock {
        val sessions = controller?.list() ?: Result.success(emptyList())
        if (sessions.isFailure) return@withLock Result.failure(requireNotNull(sessions.exceptionOrNull()))
        if (sessions.getOrThrow().isNotEmpty()) return@withLock Result.failure(IllegalStateException(
            "이 배포판에 실행 중인 세션이 있습니다. 모든 세션을 종료한 뒤 제거하세요.",
        ))
        state.value = RuntimeState.Preparing
        progressTracker.reset()
        controller?.close()
        controller = null
        proot = null
        loader = null
        bootstrapper.remove(removeHome).also { state.value = RuntimeState.Stopped }
    }

    override suspend fun start(): Result<Unit> = runCatching {
        if (controller != null) {
            state.value = RuntimeState.Ready
            return@runCatching
        }
        val executable = proot ?: error("${distribution.displayName} must be prepared before start")
        val loaderExecutable = loader ?: error("${distribution.displayName} loader must be prepared before start")
        controller = RootlessSessionController(layout, executable, loaderExecutable, usbBroker = usbBroker,
            cacheDirectory = appContext.cacheDir, sharedStorage = {
            // Query at session creation, including after returning from system settings.
            // This UID-wide OS grant is also the Native shell's access boundary.
            if (Environment.isExternalStorageManager()) Environment.getExternalStorageDirectory() else null
        })
        state.value = RuntimeState.Ready
    }.onFailure { state.value = RuntimeState.Failed(it.message ?: "${distribution.displayName} startup failed") }

    override suspend fun suspendRuntime(): Result<Unit> = Result.failure(UnsupportedOperationException("PRoot cannot suspend a userspace"))
    override suspend fun resumeRuntime(): Result<Unit> = Result.success(Unit)
    override suspend fun stop(mode: StopMode): Result<Unit> = runCatching {
        controller?.close()
        controller = null
        state.value = RuntimeState.Stopped
    }
    override fun observeState(): Flow<RuntimeState> = state.asStateFlow()
    override suspend fun connectGuestAgent(): Result<GuestConnection> = Result.failure(UnsupportedOperationException("Rootless Linux uses local IPC"))

    fun sessionController(): SessionController? = controller
    fun installProgress(): RootlessInstallProgress? = progressTracker.latest

    /** Called only when the service queues preparation behind another runtime operation. */
    fun reportPreparationWaiting() {
        if (state.value == RuntimeState.Preparing) return
        progressTracker.reset()
        progressReporter.report(RootlessPhase.WAITING, "다른 실행 환경의 설치·관리 작업이 끝나면 시작합니다")
    }
    fun clearPreparationWaiting() {
        if (progressTracker.latest?.update?.phase == RootlessPhase.WAITING) progressTracker.reset()
    }
    /** Installation can supply a rootfs, but cannot fix an unsupported ABI or missing APK tools. */
    fun canInstall(): Boolean = "arm64-v8a" in android.os.Build.SUPPORTED_ABIS &&
        executableLoader.resolvePackagedExecutable("proot").isSuccess &&
        executableLoader.resolvePackagedExecutable("proot-loader").isSuccess

    fun hasInstalledImage(): Boolean = java.nio.file.Files.isDirectory(layout.rootfs.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
    fun hasSoftwareData(): Boolean = java.nio.file.Files.exists(layout.rootfs.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
        java.nio.file.Files.isDirectory(layout.root.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
        layout.root.listFiles()?.any { it.name.startsWith(".rootfs-install-") } == true

    fun status(): RootlessStatus = if (progressTracker.latest?.let {
        it.update.phase == RootlessPhase.WAITING && it.finishedAtElapsedMs == null
    } == true) RootlessStatus.Checking else when (val current = state.value) {
        RuntimeState.Preparing, RuntimeState.Booting -> RootlessStatus.Checking
        is RuntimeState.Failed -> RootlessStatus.Error(current.reason)
        else -> layout.validateInstalledRootfs().fold(
            onSuccess = {
                executableLoader.resolvePackagedExecutable("proot").fold(
                    onSuccess = {
                        executableLoader.resolvePackagedExecutable("proot-loader").fold(
                            onSuccess = {
                                RootlessEnvironmentProfile.validate(layout).fold(
                                    onSuccess = { RootlessStatus.Ready },
                                    onFailure = { RootlessStatus.EnvironmentRequired(it.message ?: "기본 환경 보완이 필요합니다") },
                                )
                            },
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
    data class EnvironmentRequired(val reason: String) : RootlessStatus
}

class RootlessDebianLayout(val root: File, val distribution: RootlessDistribution = RootlessDistribution.DEBIAN,
    workspaceRoot: File? = null) {
    val rootfs = File(root, "rootfs")
    val home = File(root, "home")
    val tmp = File(root, "tmp")
    val workspace = workspaceRoot ?: File(requireNotNull(root.parentFile?.parentFile) { "Rootless root must have an application parent" }, "workspace")

    /** UUID environments cannot overwrite another instance's download/staging cache. */
    internal fun downloadDirectory(cache: File): File {
        val independent = root.name != "legacy" && runCatching { dev.terminal.runtime.session.SessionStorage.validate(root.name) }.isSuccess
        return File(cache, if (independent) "rootless-downloads/environments/${distribution.directory}/${root.name}"
            else "rootless-downloads/${distribution.directory}")
    }

    fun createHostDirectories() {
        listOf(root, home, tmp, workspace).forEach { require(it.mkdirs() || it.isDirectory) { "Cannot create ${it.path}" } }
    }

    fun validateInstalledRootfs(): Result<Unit> = runCatching {
        require(rootfs.isDirectory) { "${distribution.displayName} rootfs가 설치되지 않았습니다" }
        require(File(rootfs, "bin/sh").canExecute()) { "${distribution.displayName} rootfs의 /bin/sh가 없거나 실행할 수 없습니다" }
        require(distribution.packageExecutables.any { File(rootfs, it).canExecute() }) {
            "${distribution.displayName} 패키지 관리자가 없거나 실행할 수 없습니다"
        }
    }
}
