package dev.terminal.runtime.rootless

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption

/** Official distribution package selections; deliberately no common app-curated tool bundle. */
internal object RootlessEnvironmentProfile {
    const val VERSION = "distribution-defaults-2"
    const val MARKER = ".terminal-environment-version"
    fun packages(distribution: RootlessDistribution): List<String> = when (distribution) {
        // Debian's standard installation consists of required, important and standard priorities.
        RootlessDistribution.DEBIAN -> listOf("?priority(required)", "?priority(important)", "?priority(standard)")
        RootlessDistribution.UBUNTU -> listOf("ubuntu-minimal", "ubuntu-standard")
        RootlessDistribution.ARCH -> listOf("base")
    }
    fun commands(distribution: RootlessDistribution): List<String> = when (distribution) {
        RootlessDistribution.DEBIAN -> listOf("bash", "apt-get", "dpkg", "less", "ps", "ip", "ping", "man")
        RootlessDistribution.UBUNTU -> listOf("bash", "apt-get", "dpkg", "less", "ps", "ip", "ping", "man")
        RootlessDistribution.ARCH -> listOf("bash", "pacman", "ps", "ip", "ping", "tar")
    }

    fun validate(layout: RootlessDebianLayout): Result<Unit> = runCatching {
        layout.validateInstalledRootfs().getOrThrow()
        require(readRootlessFile(layout.rootfs, MARKER)?.trim() == VERSION) { "기본 도구와 사용자 환경 보완이 필요합니다" }
        require(commands(layout.distribution).all { command -> listOf("usr/bin", "bin", "usr/sbin").any { File(layout.rootfs, "$it/$command").canExecute() } }) {
            "기본 도구가 누락되었습니다. 환경 보완을 실행하세요"
        }
        require(Files.exists(File(layout.home, ".bashrc").toPath(), NOFOLLOW_LINKS)) { "기본 홈 설정이 필요합니다" }
        require(listOf(".profile", ".bash_profile", ".bash_login").any { Files.exists(File(layout.home, it).toPath(), NOFOLLOW_LINKS) }) {
            "로그인 셸 설정이 필요합니다"
        }
        require(resolveRootlessFile(layout.rootfs, "etc/localtime")?.isFile == true) { "시간대 설정이 필요합니다" }
    }

    fun invalidate(layout: RootlessDebianLayout) = withWritableMetadataDirectory(layout) {
        Files.deleteIfExists(File(layout.rootfs, MARKER).toPath())
    }

    // Arch's filesystem package makes / mode 0555. Preserve that distribution default.
    private fun <T> withWritableMetadataDirectory(layout: RootlessDebianLayout, action: () -> T): T {
        val permissions = Files.getPosixFilePermissions(layout.rootfs.toPath(), NOFOLLOW_LINKS)
        val ownerWrite = java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
        try {
            if (ownerWrite !in permissions) Files.setPosixFilePermissions(layout.rootfs.toPath(), permissions + ownerWrite)
            return action()
        } finally {
            if (ownerWrite !in permissions) Files.setPosixFilePermissions(layout.rootfs.toPath(), permissions)
        }
    }

    fun markVerified(layout: RootlessDebianLayout) = withWritableMetadataDirectory(layout) {
        val temporary = Files.createTempFile(layout.rootfs.toPath(), ".terminal-environment-", ".tmp")
        try {
            Files.write(temporary, "$VERSION\n".toByteArray(Charsets.UTF_8))
            Files.move(temporary, File(layout.rootfs, MARKER).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temporary) }
    }
}

/** Resolve symlinks in the guest namespace, never in the Android host namespace. */
internal fun resolveRootlessFile(rootfs: File, path: String): File? = runCatching {
    val root = rootfs.canonicalFile.toPath()
    var current = root
    val remaining = java.util.ArrayDeque(path.split('/').filter(String::isNotEmpty))
    var links = 0
    while (remaining.isNotEmpty()) {
        val part = remaining.removeFirst()
        if (part == ".") continue
        if (part == "..") {
            if (current == root) return null
            current = current.parent
            continue
        }
        current = current.resolve(part)
        if (Files.isSymbolicLink(current)) {
            if (++links > 40) return null
            var target = Files.readSymbolicLink(current).toString()
            // PRoot --link2symlink represents package hardlinks using absolute host paths.
            // Accept those only when canonical resolution stays inside this exact rootfs.
            if (target.startsWith('/')) {
                val hostTarget = File(target).canonicalFile.toPath()
                if (hostTarget.startsWith(root)) target = "/" + root.relativize(hostTarget).toString()
            }
            current = if (target.startsWith('/')) root else current.parent
            target.split('/').filter(String::isNotEmpty).asReversed().forEach(remaining::addFirst)
        }
    }
    current.toFile()
}.getOrNull()

/** Read bounded data only, never evaluate shell assignments. */
internal fun readRootlessFile(rootfs: File, path: String): String? = runCatching {
    val file = resolveRootlessFile(rootfs, path) ?: return null
    if (!file.isFile || file.length() > 64 * 1024) return null
    file.readText()
}.getOrNull()

internal fun rootlessLocaleEnvironment(layout: RootlessDebianLayout): Map<String, String> {
    val path = if (layout.distribution == RootlessDistribution.ARCH) "etc/locale.conf" else "etc/default/locale"
    val values = linkedMapOf("LANG" to "C.UTF-8")
    readRootlessFile(layout.rootfs, path)?.lineSequence()?.forEach { line ->
        val match = Regex("^\\s*(LANG|LANGUAGE|LC_[A-Z_]+)\\s*=\\s*[\"']?([A-Za-z0-9_.:@-]{1,128})[\"']?\\s*$").matchEntire(line)
        if (match != null) values[match.groupValues[1]] = match.groupValues[2]
    }
    return values
}
