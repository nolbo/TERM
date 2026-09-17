package dev.terminal.runtime.rootless

import android.content.Context
import dev.terminal.runtime.session.SessionCommand
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/** Runs official package managers through the same APK-owned loader, in service-owned jobs. */
internal class RootlessEnvironmentSetup(
    private val context: Context,
    private val layout: RootlessDebianLayout,
    private val proot: File,
    private val loader: File,
    private val reporter: RootlessProgressReporter,
    private val statKernelAcceleration: Boolean = false,
    private val pathKernelAcceleration: Boolean = false,
    private val mutationEntryAcceleration: Boolean = false,
    private val fstatEntryAcceleration: Boolean = false,
    private val openNotifyThread: Boolean = false,
    private val fastfdLibrary: File? = null,
    private val fastOpen: Boolean = false,
    private val fastCreate: Boolean = false,
    private val fastUnlink: Boolean = false,
    private val tcgetsDirect: Boolean = false,
    private val idEntry: Boolean = false,
    private val sharedStat: Boolean = false,
) {
    suspend fun prepare() {
        // An older successful marker must not survive a failed repair of missing defaults.
        RootlessEnvironmentProfile.invalidate(layout)
        val packages = RootlessEnvironmentProfile.packages(layout.distribution)
        runStage(RootlessPhase.PACKAGES,
            if (layout.distribution == RootlessDistribution.ARCH) "packages-arch.sh" else "packages-debian.sh", packages)
        runStage(RootlessPhase.CONFIGURE, "configure.sh", listOf(layout.distribution.directory))
        reporter.report(RootlessPhase.HOME, "기존 사용자 설정을 보존하고 누락된 셸 설정을 복원하고 있습니다")
        configureRootlessLoginHome(layout)
        runStage(RootlessPhase.VERIFY, "verify.sh", listOf(layout.distribution.directory) + packages)
        RootlessEnvironmentProfile.markVerified(layout)
        RootlessEnvironmentProfile.validate(layout).getOrThrow()
    }

    private suspend fun runStage(phase: RootlessPhase, asset: String, arguments: List<String>) {
        reporter.report(phase, "${layout.distribution.displayName}: ${phase.displayName}")
        val script = context.assets.open("rootless/$asset").bufferedReader().use { it.readText() }
        val progress = RootlessPackageProgress(phase, reporter)
        try {
            val result = runRootlessSetupCommand(layout, proot, loader, listOf("/bin/sh", "-c", script, "terminal-setup") + arguments,
                onOutputLine = progress::line, statKernelAcceleration = statKernelAcceleration,
                pathKernelAcceleration = pathKernelAcceleration,
                mutationEntryAcceleration = mutationEntryAcceleration,
                fstatEntryAcceleration = fstatEntryAcceleration, openNotifyThread = openNotifyThread,
                fastfdLibrary = fastfdLibrary, fastOpen = fastOpen, fastCreate = fastCreate, fastUnlink = fastUnlink,
                tcgetsDirect = tcgetsDirect, idEntry = idEntry, sharedStat = sharedStat,
                accelerationLibrary = if (dev.terminal.runtime.BuildConfig.ROOTLESS_ACCELERATION &&
                    fastfdLibrary == null && !openNotifyThread) File(proot.parentFile, "libproot-faststat.so") else null)
            check(result.exitCode == 0) { "종료 코드 ${result.exitCode}\n${result.errorContext.ifBlank { result.output.takeLast(2000) }}" }
        } catch (error: TimeoutCancellationException) {
            throw RootlessSetupException(progress.currentPhase,
                IllegalStateException("20분 동안 단계가 완료되지 않았습니다. 네트워크와 패키지 상태를 확인한 뒤 재시도하세요", error))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw RootlessSetupException(progress.currentPhase, error)
        }
    }
}

internal data class RootlessCommandResult(val exitCode: Int, val output: String, val errorContext: String = "")

internal suspend fun runRootlessSetupCommand(
    layout: RootlessDebianLayout, proot: File, loader: File, argv: List<String>, timeoutMs: Long = 20 * 60 * 1000L,
    onOutputLine: (String) -> Unit = {},
    statKernelAcceleration: Boolean = false,
    pathKernelAcceleration: Boolean = false,
    mutationEntryAcceleration: Boolean = false,
    fstatEntryAcceleration: Boolean = false,
    openNotifyThread: Boolean = false,
    fastfdLibrary: File? = null,
    fastOpen: Boolean = false,
    fastCreate: Boolean = false,
    fastUnlink: Boolean = false,
    tcgetsDirect: Boolean = false,
    idEntry: Boolean = false,
    sharedStat: Boolean = false,
    accelerationLibrary: File? = null,
): RootlessCommandResult = withContext(Dispatchers.IO) {
    File(layout.tmp, "graphics-shm").mkdirs()
    require(accelerationLibrary == null || (fastfdLibrary == null && !openNotifyThread)) {
        "Packaged acceleration cannot be combined with a separate DSO or notification experiment"
    }
    require(!sharedStat || (fastfdLibrary != null && !openNotifyThread)) { "Shared stat requires a guest DSO without notification mode" }
    require(!fastUnlink || fastOpen) { "Fast unlink requires fast open" }
    require(!fastCreate || fastOpen) { "Fast create requires fast open" }
    require(!fastOpen || (fastfdLibrary != null && !openNotifyThread)) { "Fast open requires a guest DSO without notification mode" }
    val guestLibrary = "/usr/local/lib/terminal-faststat.so"
    fastfdLibrary?.let { require(it.isFile && it.canRead()) { "Experimental guest DSO is missing" } }
    val experimentEnvironment = if (fastfdLibrary != null)
        mapOf("LD_PRELOAD" to guestLibrary, "PROOT_FASTFD" to "1", "PROOT_FASTSTAT_PATH" to "0") +
            (if (fastOpen) mapOf("PROOT_FASTOPEN" to "1") else emptyMap()) +
            (if (fastCreate) mapOf("PROOT_FASTCREATE" to "1") else emptyMap()) +
            (if (fastUnlink) mapOf("PROOT_FASTUNLINK" to "1") else emptyMap()) +
            (if (sharedStat) mapOf("PROOT_FASTSTAT_SHARED" to "1") else emptyMap()) else emptyMap()
    val spec = buildRootlessLaunchSpec(layout, proot, loader, "/bin/sh", "/", mapOf(
        SessionCommand.KEY to SessionCommand.encode(argv), "TERM" to "dumb", "LC_ALL" to "C.UTF-8",
        "DEBIAN_FRONTEND" to "noninteractive", "NEEDRESTART_MODE" to "a",
    ) + experimentEnvironment, accelerationLibrary = accelerationLibrary)
    // Maintenance commands are finite: keyring hooks may leave gpg-agent detached.
    // Reap only this invocation's tracees once its command exits. Interactive PTYs
    // keep their independent lifetime and do not use this option.
    val arguments = spec.arguments.toMutableList().apply {
        add(1, "--kill-on-exit")
        fastfdLibrary?.let { addAll(1, listOf("-b", "${it.absolutePath}:$guestLibrary")) }
    }
    val process = ProcessBuilder(arguments).directory(File(spec.hostCwd)).redirectErrorStream(true).apply {
        environment().clear()
        spec.hostEnvironment.forEach { environment()[it.substringBefore('=')] = it.substringAfter('=') }
        if (fastfdLibrary != null) {
            environment()["PROOT_FASTSTAT"] = "2"
            environment()["PROOT_FASTFD"] = "1"
        }
        if (fastOpen || sharedStat) environment()["PROOT_FASTPATH"] = "1"
        if (statKernelAcceleration) {
            environment()["PROOT_STAT_ENTRY"] = "1"
            environment()["PROOT_STAT_KERNEL"] = "1"
        }
        if (pathKernelAcceleration) environment()["PROOT_PATH_KERNEL"] = "1"
        if (mutationEntryAcceleration) environment()["PROOT_MUTATION_ENTRY"] = "1"
        if (fstatEntryAcceleration) environment()["PROOT_FSTAT_ENTRY"] = "1"
        if (tcgetsDirect) environment()["PROOT_TCGETS_DIRECT"] = "1"
        if (idEntry) environment()["PROOT_ID_ENTRY"] = "1"
        if (openNotifyThread) {
            environment()["PROOT_OPEN_NOTIFY"] = "1"
            environment()["PROOT_OPEN_NOTIFY_THREAD"] = "1"
        }
    }.start()
    process.outputStream.close() // Installers must never wait for UI input.
    try {
        coroutineScope {
            val output = async(Dispatchers.IO) {
                val tail = StringBuilder()
                val recentLines = java.util.ArrayDeque<String>()
                var errorContext = ""
                val lines = RootlessOutputLines { line ->
                    if (!line.startsWith("pmstatus:") && !line.startsWith("dlstatus:")) {
                        recentLines.addLast(line.take(300))
                        if (recentLines.size > 8) recentLines.removeFirst()
                    }
                    if (errorContext.isEmpty() && (line.startsWith("dpkg: error") || line.startsWith("pmerror:") || line.startsWith("E:"))) {
                        errorContext = recentLines.joinToString("\n").takeLast(2000)
                    }
                    onOutputLine(line)
                }
                process.inputStream.reader().use { reader ->
                    val buffer = CharArray(2048)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        tail.append(buffer, 0, count)
                        if (tail.length > 8192) tail.delete(0, tail.length - 8192)
                        lines.accept(buffer, count)
                    }
                    lines.flush()
                }
                tail.toString() to errorContext
            }
            try {
                withTimeout(timeoutMs) {
                    val code = runInterruptible(Dispatchers.IO) { process.waitFor() }
                    val captured = output.await()
                    RootlessCommandResult(code, captured.first, captured.second)
                }
            } finally {
                // Closing the pipe releases the blocking reader before coroutineScope joins it.
                if (process.isAlive) process.destroyForcibly()
                process.inputStream.close()
                output.cancel()
            }
        }
    } finally {
        if (process.isAlive) process.destroyForcibly()
        process.inputStream.close()
    }
}
