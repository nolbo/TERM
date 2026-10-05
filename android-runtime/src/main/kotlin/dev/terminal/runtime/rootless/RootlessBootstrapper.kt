package dev.terminal.runtime.rootless

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermission
import java.util.ArrayDeque
import java.util.UUID

fun interface RootlessProgressReporter {
    fun report(phase: RootlessPhase, detail: String)
    fun update(progress: RootlessProgressUpdate) = report(progress.phase, progress.detail)

    companion object {
        val NONE = RootlessProgressReporter { _, _ -> }
    }
}

enum class RootlessPhase(val displayName: String) {
    WAITING("다른 설치 작업 완료 대기"),
    EXECUTABLE("실행 파일 확인"),
    STORAGE("저장 공간 확인"),
    DOWNLOAD("배포판 이미지 다운로드"),
    CHECKSUM("무결성 검증"),
    EXTRACT("안전한 압축 해제"),
    ACTIVATE("설치 활성화"),
    MIGRATE("가속 형식 이전"),
    PACKAGES("배포판 기본 구성 설치"),
    PACKAGE_INDEX("패키지 저장소 목록 갱신"),
    PACKAGE_RECOVERY("이전 패키지 작업 확인·복구"),
    PACKAGE_PLAN("기본 패키지와 의존성 확인"),
    PACKAGE_DOWNLOAD("패키지 다운로드"),
    PACKAGE_INSTALL("패키지 압축 해제·설치"),
    PACKAGE_UPGRADE("기존 패키지 업그레이드"),
    PACKAGE_CHECK("패키지 무결성·의존성 확인"),
    PACKAGE_HOOKS("패키지 설치 후처리"),
    CONFIGURE("배포판 초기 설정"),
    LOCALE("로케일 생성"),
    TIMEZONE("시간대 설정 확인"),
    HOME("사용자 셸 설정 보완"),
    VERIFY("기본 환경 동작 검증"),
    VERIFY_PACKAGES("패키지 데이터베이스 검증"),
    VERIFY_COMMANDS("기본 명령 실행 검증"),
    VERIFY_LOCALE("로케일·홈·시간대 검증"),
    READY("Rootless 준비 완료"),
    CANCELLED("설치 취소됨"),
}

class RootlessSetupException(
    val phase: RootlessPhase,
    cause: Throwable,
) : IllegalStateException("${phase.displayName} 실패: ${cause.message ?: cause.javaClass.simpleName}", cause)

/** Installs approved distribution rootfs archives; host PRoot executables stay in the APK. */
class RootlessBootstrapper(
    private val context: Context,
    private val layout: RootlessDebianLayout,
    private val reporter: RootlessProgressReporter = RootlessProgressReporter.NONE,
    private val createHardLink: (Path, Path) -> Path = Files::createLink,
    private val checkCancellation: () -> Unit = {},
) {
    private val installMutex = Mutex()
    private val distribution = layout.distribution
    private val extractor = RootlessArchiveExtractor(distribution.archiveRoot, MAX_EXTRACTED_BYTES,
        checkCancellation = checkCancellation)

    suspend fun installIfNeeded(): Result<Unit> = withContext(Dispatchers.IO) {
        installMutex.withLock {
            if (RootlessNativeMigration.pending(layout.root.parentFile.toPath())) return@withLock Result.failure(
                IllegalStateException("중단된 가속 형식 이전을 먼저 복구하세요"))
            layout.validateInstalledRootfs().fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { install() },
            )
        }
    }

    suspend fun remove(removeHome: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        installMutex.withLock {
            runCatching {
                check(!RootlessNativeMigration.pending(layout.root.parentFile.toPath())) { "중단된 가속 형식 이전을 먼저 복구하세요" }
                removeRootlessImage(
                    layout.rootfs.toPath(),
                    layout.downloadDirectory(context.cacheDir).toPath(),
                    layout.home.toPath(),
                    removeHome,
                )
            }
        }
    }

    private fun install(): Result<Unit> = runCatching {
        val rootfs = layout.rootfs.toPath()
        val downloadDirectory = layout.downloadDirectory(context.cacheDir).toPath()
        Files.createDirectories(downloadDirectory)
        phase(RootlessPhase.STORAGE, "ARM64 지원과 설치 여유 공간을 확인하고 있습니다.") {
            require(ANDROID_ARM64 in android.os.Build.SUPPORTED_ABIS) {
                "지원되지 않는 ABI입니다: ${android.os.Build.SUPPORTED_ABIS.joinToString()} (필요: $ANDROID_ARM64)"
            }
            // installIfNeeded() reaches install() only for an invalid rootfs. Reclaim
            // interrupted app-owned state before measuring free space for the retry.
            cleanupIncompleteRootlessInstall(rootfs, downloadDirectory)
            require(StatFs(context.filesDir.path).availableBytes >= distribution.minimumFreeMiB * 1024 * 1024) {
                "최소 ${distribution.minimumFreeMiB} MiB의 여유 공간이 필요합니다"
            }
        }

        val archive = downloadDirectory.resolve("${distribution.directory}-${UUID.randomUUID()}.tar.xz")
        val staging = rootfs.parent.resolve(".rootfs-install-${UUID.randomUUID()}")
        try {
            phase(RootlessPhase.DOWNLOAD, "고정된 ${distribution.displayName} ARM64 이미지를 내려받고 있습니다.") {
                downloadPinnedArchive(archive)
            }
            phase(RootlessPhase.CHECKSUM, "내려받은 이미지의 SHA-256을 검증하고 있습니다.") {
                extractor.verifySha256(archive, distribution.sha256) { completed, total ->
                    reporter.update(RootlessProgressUpdate(RootlessPhase.CHECKSUM, "이미지 SHA-256을 검증하고 있습니다",
                        if (total > 0) completed.toFloat() / total else null, completed, total))
                }
            }
            phase(RootlessPhase.EXTRACT, "검증된 이미지를 격리된 설치 디렉터리에 풀고 있습니다.") {
                Files.createDirectory(staging)
                extractor.extract(archive, staging) { entries, bytes ->
                    reporter.update(RootlessProgressUpdate(RootlessPhase.EXTRACT,
                        "파일 $entries 개 처리 · ${bytes / (1024 * 1024)} MiB 압축 해제"))
                }
                configureRootlessRootfs(staging, distribution)
                RootlessStorageFormat.prepareNewInstallation(staging, createHardLink)
                if (RootlessStorageFormat.usesEmulatedLinks(staging)) {
                    reporter.report(RootlessPhase.EXTRACT, "하드링크를 사용할 수 없어 PRoot 링크 에뮬레이션으로 설치합니다. 파일 가속은 비활성화됩니다")
                }
                require(Files.isExecutable(staging.resolve("bin/sh"))) { "${distribution.displayName} archive does not contain an executable /bin/sh" }
                require(distribution.packageExecutables.any { Files.isExecutable(staging.resolve(it)) }) {
                    "${distribution.displayName} archive does not contain its package manager"
                }
            }
            phase(RootlessPhase.ACTIVATE, "완성된 rootfs를 원자적으로 활성화하고 있습니다.") {
                try {
                    Files.move(staging, rootfs, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(staging, rootfs)
                }
                layout.validateInstalledRootfs().getOrThrow()
            }
        } finally {
            runCatching { Files.deleteIfExists(archive) }
            runCatching { if (Files.exists(staging)) deleteRootlessTree(staging) }
        }
    }

    private inline fun <T> phase(phase: RootlessPhase, detail: String, block: () -> T): T {
        checkCancellation()
        reporter.report(phase, detail)
        return try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: RootlessSetupException) {
            throw error
        } catch (error: Throwable) {
            throw RootlessSetupException(phase, error)
        }
    }

    private fun downloadPinnedArchive(destination: Path) {
        val maximumArchiveBytes = distribution.maximumArchiveMiB * 1024 * 1024
        val connection = (distribution.archiveUrl.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            checkCancellation()
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            require(connection.url.protocol.equals("https", ignoreCase = true)) { "download redirected outside HTTPS" }
            require(connection.contentLengthLong < 0 || connection.contentLengthLong <= maximumArchiveBytes) {
                "archive is larger than the approved ${distribution.maximumArchiveMiB} MiB limit"
            }
            val expected = connection.contentLengthLong.takeIf { it > 0 }
            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(Files.newOutputStream(destination)).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        checkCancellation()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= maximumArchiveBytes) { "archive is larger than the approved ${distribution.maximumArchiveMiB} MiB limit" }
                        output.write(buffer, 0, count)
                        reporter.update(RootlessProgressUpdate(RootlessPhase.DOWNLOAD, "${distribution.displayName} 이미지를 내려받고 있습니다",
                            expected?.let { (total.toFloat() / it).coerceIn(0f, 1f) }, total, expected))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val ANDROID_ARM64 = "arm64-v8a"
        const val MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER_SIZE = 64 * 1024
    }
}

/** Deletes image data and, only when explicitly requested, that distribution's separate home. */
internal fun removeRootlessImage(rootfs: Path, downloadDirectory: Path? = null, home: Path? = null, removeHome: Boolean = false) {
    if (removeHome) {
        requireNotNull(home) { "A distribution home is required when removing home data" }
        require(home.normalize().parent == rootfs.normalize().parent) { "Home must belong to the removed distribution" }
    }
    deleteRootlessTreeIfPresent(rootfs)
    downloadDirectory?.let(::deleteRootlessTreeIfPresent)
    if (Files.isDirectory(rootfs.parent, NOFOLLOW_LINKS)) {
        Files.list(rootfs.parent).use { files ->
            files.filter { it.fileName.toString().startsWith(".rootfs-install-") }
                .forEach(::deleteRootlessTreeIfPresent)
        }
    }
    if (removeHome) deleteRootlessTreeIfPresent(requireNotNull(home))
}

/** Removes only invalid/incomplete app-owned installation state before a retry. */
internal fun cleanupIncompleteRootlessInstall(rootfs: Path, downloadDirectory: Path) {
    if (Files.exists(rootfs)) deleteRootlessTree(rootfs)
    Files.list(downloadDirectory).use { files -> files.forEach(::deleteRootlessTreeIfPresent) }
    Files.list(rootfs.parent).use { files ->
        files.filter { it.fileName.toString().startsWith(".rootfs-install-") }
            .forEach(::deleteRootlessTreeIfPresent)
    }
}

private fun deleteRootlessTreeIfPresent(path: Path) {
    if (Files.isDirectory(path, NOFOLLOW_LINKS)) deleteRootlessTree(path) else Files.deleteIfExists(path)
}

internal fun deleteRootlessTree(root: Path) {
    // Distribution archives may contain read-only directories. Restore only owner
    // access within the discarded tree, and never follow links outside that tree.
    val pending = ArrayDeque<Pair<Path, Boolean>>()
    pending.addLast(root to false)
    while (pending.isNotEmpty()) {
        val (path, visited) = pending.removeLast()
        if (!visited && Files.isDirectory(path, NOFOLLOW_LINKS)) {
            val permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS).toMutableSet()
            permissions.addAll(listOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
            Files.setPosixFilePermissions(path, permissions)
            pending.addLast(path to true)
            Files.list(path).use { children -> children.forEach { pending.addLast(it to false) } }
        } else {
            Files.deleteIfExists(path)
        }
    }
}

internal fun configureRootlessRootfs(rootfs: Path, distribution: RootlessDistribution) {
    val etc = rootfs.resolve("etc")
    Files.createDirectories(etc)
    listOf(
        "resolv.conf" to "# DNS is supplied by the TERM Userspace Linux engine.\n",
        "hosts" to "127.0.0.1 localhost\n::1 localhost\n",
    ).forEach { (name, content) ->
        val target = etc.resolve(name)
        Files.deleteIfExists(target)
        Files.newBufferedWriter(target).use { it.write(content) }
    }
    if (distribution == RootlessDistribution.ARCH) {
        // The image contains old repository indexes. Extraction gives files a new
        // mtime, which can make pacman accept HTTP 304 and request removed packages.
        // Discard only bundled sync indexes, never the installed-package database.
        val sync = rootfs.resolve("var/lib/pacman/sync")
        if (Files.isDirectory(sync)) {
            Files.list(sync).use { entries -> entries.forEach(Files::delete) }
        }
    }
}
