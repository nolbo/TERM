package dev.terminal.runtime.rootless

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
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
import java.util.Comparator
import java.util.UUID

fun interface RootlessProgressReporter {
    fun report(phase: RootlessPhase, detail: String)

    companion object {
        val NONE = RootlessProgressReporter { _, _ -> }
    }
}

enum class RootlessPhase(val displayName: String) {
    EXECUTABLE("실행 파일 확인"),
    STORAGE("저장 공간 확인"),
    DOWNLOAD("Debian 다운로드"),
    CHECKSUM("무결성 검증"),
    EXTRACT("안전한 압축 해제"),
    ACTIVATE("설치 활성화"),
    READY("Rootless 준비 완료"),
}

class RootlessSetupException(
    val phase: RootlessPhase,
    cause: Throwable,
) : IllegalStateException("${phase.displayName} 실패: ${cause.message ?: cause.javaClass.simpleName}", cause)

/** Installs only the catalog-pinned Debian data archive; executable code stays in the APK. */
class RootlessBootstrapper(
    private val context: Context,
    private val layout: RootlessDebianLayout,
    private val reporter: RootlessProgressReporter = RootlessProgressReporter.NONE,
) {
    private val installMutex = Mutex()
    private val extractor = RootlessArchiveExtractor(ARCHIVE_ROOT, MAX_EXTRACTED_BYTES)

    suspend fun installIfNeeded(): Result<Unit> = withContext(Dispatchers.IO) {
        installMutex.withLock {
            layout.validateInstalledRootfs().fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { install() },
            )
        }
    }

    private fun install(): Result<Unit> = runCatching {
        val rootfs = layout.rootfs.toPath()
        val downloadDirectory = context.cacheDir.toPath().resolve("rootless-downloads")
        Files.createDirectories(downloadDirectory)
        phase(RootlessPhase.STORAGE, "ARM64 지원과 설치 여유 공간을 확인하고 있습니다.") {
            require(ANDROID_ARM64 in android.os.Build.SUPPORTED_ABIS) {
                "지원되지 않는 ABI입니다: ${android.os.Build.SUPPORTED_ABIS.joinToString()} (필요: $ANDROID_ARM64)"
            }
            // installIfNeeded() reaches install() only for an invalid rootfs. Reclaim
            // interrupted app-owned state before measuring free space for the retry.
            cleanupIncompleteRootlessInstall(rootfs, downloadDirectory)
            require(StatFs(context.filesDir.path).availableBytes >= MIN_FREE_BYTES) {
                "최소 700 MiB의 여유 공간이 필요합니다"
            }
        }

        val archive = downloadDirectory.resolve("${RELEASE_ID}-${UUID.randomUUID()}.tar.xz")
        val staging = rootfs.parent.resolve(".rootfs-install-${UUID.randomUUID()}")
        try {
            phase(RootlessPhase.DOWNLOAD, "고정된 Debian Trixie ARM64 이미지를 내려받고 있습니다.") {
                downloadPinnedArchive(archive)
            }
            phase(RootlessPhase.CHECKSUM, "내려받은 이미지의 SHA-256을 검증하고 있습니다.") {
                extractor.verifySha256(archive, ARCHIVE_SHA256)
            }
            phase(RootlessPhase.EXTRACT, "검증된 이미지를 격리된 설치 디렉터리에 풀고 있습니다.") {
                Files.createDirectory(staging)
                extractor.extract(archive, staging)
                configureRootfs(staging)
                require(Files.isExecutable(staging.resolve("bin/sh"))) { "Debian archive does not contain an executable /bin/sh" }
                require(Files.isExecutable(staging.resolve("usr/bin/apt")) || Files.isExecutable(staging.resolve("usr/bin/apt-get"))) {
                    "Debian archive does not contain executable apt"
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
            reporter.report(RootlessPhase.READY, "Debian rootfs와 APK PRoot가 준비되었습니다.")
        } finally {
            runCatching { Files.deleteIfExists(archive) }
            runCatching { if (Files.exists(staging)) deleteRootlessTree(staging) }
        }
    }

    private inline fun <T> phase(phase: RootlessPhase, detail: String, block: () -> T): T {
        reporter.report(phase, detail)
        return try {
            block()
        } catch (error: RootlessSetupException) {
            throw error
        } catch (error: Throwable) {
            throw RootlessSetupException(phase, error)
        }
    }

    private fun downloadPinnedArchive(destination: Path) {
        val connection = (ARCHIVE_URL.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            require(connection.url.protocol.equals("https", ignoreCase = true)) { "download redirected outside HTTPS" }
            require(connection.contentLengthLong < 0 || connection.contentLengthLong <= MAX_ARCHIVE_BYTES) {
                "archive is larger than the approved 128 MiB limit"
            }
            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(Files.newOutputStream(destination)).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_ARCHIVE_BYTES) { "archive is larger than the approved 128 MiB limit" }
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun configureRootfs(rootfs: Path) {
        val etc = rootfs.resolve("etc")
        Files.createDirectories(etc)
        listOf(
            "resolv.conf" to "nameserver 1.1.1.1\nnameserver 8.8.8.8\n",
            "hosts" to "127.0.0.1 localhost\n::1 localhost\n",
        ).forEach { (name, content) ->
            val target = etc.resolve(name)
            Files.deleteIfExists(target)
            Files.newBufferedWriter(target).use { it.write(content) }
        }
    }

    private companion object {
        const val ANDROID_ARM64 = "arm64-v8a"
        const val RELEASE_ID = "debian-trixie-aarch64-pd-v4.29.0"
        const val ARCHIVE_ROOT = "debian-trixie-aarch64"
        val ARCHIVE_URL = java.net.URI("https://github.com/termux/proot-distro/releases/download/v4.29.0/debian-trixie-aarch64-pd-v4.29.0.tar.xz")
        const val ARCHIVE_SHA256 = "3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918"
        const val MAX_ARCHIVE_BYTES = 128L * 1024 * 1024
        const val MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024
        const val MIN_FREE_BYTES = 700L * 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER_SIZE = 64 * 1024
    }
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
    if (Files.isDirectory(path)) deleteRootlessTree(path) else Files.deleteIfExists(path)
}

private fun deleteRootlessTree(root: Path) {
    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}
