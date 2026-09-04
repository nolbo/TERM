package dev.terminal.runtime.rootless

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Installs the pinned Debian rootfs on demand. No file picker, ADB, root, or
 * user-supplied executable is involved: PRoot is packaged in the APK and the
 * archive is accepted only when its catalog checksum matches.
 */
class RootlessBootstrapper(
    private val context: Context,
    private val layout: RootlessDebianLayout,
) {
    suspend fun installIfNeeded(): Result<Unit> = withContext(Dispatchers.IO) {
        layout.validateInstalledRootfs().fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { install() },
        )
    }

    private fun install(): Result<Unit> = runCatching {
        require(ANDROID_ARM64 in android.os.Build.SUPPORTED_ABIS) {
            "Rootless Debian 자동 설치는 현재 ARM64 기기에서 지원됩니다"
        }
        val rootfs = layout.rootfs.toPath()
        // installIfNeeded() reaches here only after validation failed. This is
        // therefore an incomplete/corrupt app-owned rootfs, never one that was
        // successfully installed. Remove it so a transient interrupted download
        // does not turn into a manual "clear app data" support step.
        if (Files.exists(rootfs)) deleteTree(rootfs)
        require(StatFs(context.filesDir.path).availableBytes >= MIN_FREE_BYTES) {
            "Rootless Debian 설치에는 최소 700 MiB의 여유 공간이 필요합니다"
        }

        val downloadDirectory = context.cacheDir.toPath().resolve("rootless-downloads")
        Files.createDirectories(downloadDirectory)
        val archive = downloadDirectory.resolve("${RELEASE_ID}-${UUID.randomUUID()}.tar.xz")
        val staging = rootfs.parent.resolve(".rootfs-install-${UUID.randomUUID()}")
        try {
            downloadPinnedArchive(archive)
            require(sha256(archive) == ARCHIVE_SHA256) { "Debian archive integrity check failed" }
            Files.createDirectory(staging)
            extractArchive(archive, staging)
            configureRootfs(staging)
            require(Files.isRegularFile(staging.resolve("bin/sh"))) { "Debian archive does not contain /bin/sh" }
            require(Files.isRegularFile(staging.resolve("usr/bin/apt")) || Files.isRegularFile(staging.resolve("usr/bin/apt-get"))) {
                "Debian archive does not contain apt"
            }
            try {
                Files.move(staging, rootfs, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging, rootfs)
            }
            layout.validateInstalledRootfs().getOrThrow()
        } finally {
            Files.deleteIfExists(archive)
            if (Files.exists(staging)) deleteTree(staging)
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
            require(connection.responseCode in 200..299) { "Debian archive download failed: HTTP ${connection.responseCode}" }
            require(connection.contentLengthLong < 0 || connection.contentLengthLong <= MAX_ARCHIVE_BYTES) {
                "Debian archive is larger than the approved limit"
            }
            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(Files.newOutputStream(destination)).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_ARCHIVE_BYTES) { "Debian archive is larger than the approved limit" }
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractArchive(archive: Path, destination: Path) {
        BufferedInputStream(Files.newInputStream(archive)).use { compressed ->
            XZCompressorInputStream(compressed).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    var extractedBytes = 0L
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        val target = safeTarget(destination, entry.name)
                        when {
                            entry.isDirectory -> Files.createDirectories(target)
                            entry.isSymbolicLink -> createSafeSymbolicLink(destination, target, entry.linkName)
                            entry.isLink -> createSafeHardLink(destination, target, entry.linkName)
                            entry.isFile -> {
                                require(entry.size in 0..MAX_EXTRACTED_BYTES) { "Invalid Debian archive entry size" }
                                extractedBytes += entry.size
                                require(extractedBytes <= MAX_EXTRACTED_BYTES) { "Debian rootfs exceeds the approved size" }
                                Files.createDirectories(requireNotNull(target.parent))
                                BufferedOutputStream(Files.newOutputStream(target)).use { output -> tar.copyTo(output) }
                            }
                        }
                    }
                }
            }
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

    private fun safeTarget(root: Path, name: String): Path {
        require(name == ARCHIVE_ROOT || name.startsWith("$ARCHIVE_ROOT/")) { "Unexpected Debian archive layout" }
        val relative = Path.of(name.removePrefix(ARCHIVE_ROOT).removePrefix("/")).normalize()
        require(!relative.isAbsolute && relative.none { it.toString() == ".." }) { "Debian archive contains an unsafe path" }
        val target = root.resolve(relative).normalize()
        require(target.startsWith(root)) { "Debian archive escaped its rootfs" }
        return target
    }

    private fun createSafeSymbolicLink(root: Path, target: Path, linkName: String) {
        val link = Path.of(linkName)
        // Debian intentionally contains guest-root absolute links, such as
        // /etc/alternatives/awk -> /usr/bin/mawk and /dev/fd -> /proc/self/fd.
        // An extracted absolute link must not retain host-absolute semantics:
        // resolve it under the staged guest root and store an equivalent
        // relative link instead.
        val resolved = if (link.isAbsolute) {
            val guestRelative = Path.of(linkName.removePrefix("/")).normalize()
            require(!guestRelative.isAbsolute && guestRelative.none { it.toString() == ".." }) {
                "Debian archive contains an unsafe symbolic link"
            }
            root.resolve(guestRelative).normalize()
        } else {
            target.parent.resolve(link).normalize()
        }
        require(resolved.startsWith(root)) { "Debian archive contains an unsafe symbolic link" }
        Files.createDirectories(requireNotNull(target.parent))
        Files.deleteIfExists(target)
        Files.createSymbolicLink(target, target.parent.relativize(resolved))
    }

    private fun createSafeHardLink(root: Path, target: Path, linkName: String) {
        val source = safeTarget(root, linkName)
        require(Files.isRegularFile(source)) { "Debian archive contains a forward or invalid hard link" }
        Files.createDirectories(requireNotNull(target.parent))
        Files.deleteIfExists(target)
        Files.createLink(target, source)
    }

    private fun sha256(path: Path): String = Files.newInputStream(path).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun deleteTree(root: Path) {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
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
