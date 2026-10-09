package dev.terminal.runtime.rootless

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE

/** Probe unpublished installations; retain emulation when host hardlinks are unavailable. */
internal object RootlessStorageFormat {
    private const val MARKER = ".terminal-storage-format"
    private const val NATIVE_LINKS = "native-hardlinks-1\n"

    private const val EMULATED_LINKS = "emulated-hardlinks-1\n"

    enum class LaunchFormat { NATIVE, EMULATED, LEGACY_EMULATED }

    /** Compatibility migration is a launch decision, not a rewrite of guest data.
     * Old link2symlink installations must never be upgraded by probing today's
     * filesystem: their existing emulated links still need the extension.
     * No migration journal or partial conversion can be left by process death.
     */
    fun resolveForLaunch(root: Path): LaunchFormat {
        val marker = root.resolve(MARKER)
        if (Files.notExists(marker, NOFOLLOW_LINKS)) return LaunchFormat.LEGACY_EMULATED
        require(Files.isRegularFile(marker, NOFOLLOW_LINKS)) {
            "Rootless 저장 형식 표시가 올바른 일반 파일이 아닙니다. 기존 설치를 보존했습니다"
        }
        val value = Files.newInputStream(marker, NOFOLLOW_LINKS).use { input ->
            val bytes = ArrayList<Byte>()
            repeat(maxOf(NATIVE_LINKS.length, EMULATED_LINKS.length) + 1) {
                val next = input.read()
                if (next == -1) return@use bytes.toByteArray().toString(Charsets.UTF_8)
                bytes.add(next.toByte())
            }
            bytes.toByteArray().toString(Charsets.UTF_8)
        }
        return when (value) {
            NATIVE_LINKS -> LaunchFormat.NATIVE
            EMULATED_LINKS -> LaunchFormat.EMULATED
            else -> throw IllegalArgumentException(
                "지원하지 않거나 손상된 Rootless 저장 형식입니다. 기존 설치를 보존했습니다")
        }
    }

    fun usesNativeLinks(root: Path): Boolean = hasFormat(root, NATIVE_LINKS)
    fun usesEmulatedLinks(root: Path): Boolean = hasFormat(root, EMULATED_LINKS)

    private fun hasFormat(root: Path, format: String): Boolean {
        val marker = root.resolve(MARKER)
        return Files.isRegularFile(marker, NOFOLLOW_LINKS) &&
            Files.size(marker) == format.length.toLong() &&
            Files.newInputStream(marker).use { input ->
                // Bound reads even if a guest changes the marker after the size check.
                format.all { input.read() == it.code } && input.read() == -1
            }
    }

    /** Run in unpublished staging, before package setup can create emulated links. */
    fun prepareNewInstallation(root: Path, createHardLink: (Path, Path) -> Path = Files::createLink) =
        prepareNewInstallation(root, null, createHardLink)

    fun prepareNewInstallation(root: Path, preferred: LaunchFormat?, createHardLink: (Path, Path) -> Path = Files::createLink) {
        val permissions = Files.getPosixFilePermissions(root, NOFOLLOW_LINKS)
        try {
            Files.setPosixFilePermissions(root, permissions + OWNER_WRITE)
            val probe = Files.createTempDirectory(root, ".native-link-probe-")
            var nativeLinks = true
            try {
                val original = Files.write(probe.resolve("original"), byteArrayOf(1), CREATE_NEW)
                val linked = probe.resolve("linked")
                try {
                    if (preferred == LaunchFormat.EMULATED || preferred == LaunchFormat.LEGACY_EMULATED) nativeLinks = false
                    else createHardLink(linked, original)
                } catch (_: IOException) {
                    nativeLinks = false
                } catch (_: UnsupportedOperationException) {
                    nativeLinks = false
                }
                require(preferred != LaunchFormat.NATIVE || nativeLinks) { "This device cannot preserve the restored home hardlink format" }
                if (!nativeLinks) {
                    // Exercise the primitive PRoot's link emulation needs. Other storage
                    // failures still propagate; no marker is published unless this works.
                    Files.deleteIfExists(linked)
                    Files.createSymbolicLink(linked, original.fileName)
                }
                check(Files.isSameFile(original, linked)) { "Rootless storage must support native hardlinks" }
                check(Files.readAllBytes(linked).contentEquals(byteArrayOf(1)))
                if (nativeLinks) {
                    Files.delete(original)
                    check(Files.readAllBytes(linked).contentEquals(byteArrayOf(1))) {
                        "Rootless hardlink did not survive unlink"
                    }
                }
            } finally {
                Files.deleteIfExists(probe.resolve("linked"))
                Files.deleteIfExists(probe.resolve("original"))
                Files.delete(probe)
            }
            Files.write(root.resolve(MARKER), (if (nativeLinks) NATIVE_LINKS else EMULATED_LINKS).toByteArray(Charsets.UTF_8), CREATE_NEW)
        } finally {
            Files.setPosixFilePermissions(root, permissions)
        }
    }

    /** App-written metadata from a verified DATA restore; guest archives cannot select this path. */
    fun restoredDataFormat(storageRoot: Path): LaunchFormat? {
        val marker = storageRoot.resolve(".terminal-data-storage-format")
        if (Files.notExists(marker, NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(marker, NOFOLLOW_LINKS) && Files.size(marker) <= 64) { "Invalid restored data storage format" }
        val value = Files.newInputStream(marker, NOFOLLOW_LINKS).use { input ->
            val bytes = java.io.ByteArrayOutputStream()
            repeat(65) { val next = input.read(); if (next < 0) return@use bytes.toString(Charsets.UTF_8.name()); bytes.write(next) }
            error("Invalid restored data storage format")
        }
        return when (value.trim()) {
            "native-hardlinks-1" -> LaunchFormat.NATIVE
            "emulated-hardlinks-1" -> LaunchFormat.EMULATED
            "legacy-emulated-hardlinks" -> LaunchFormat.LEGACY_EMULATED
            else -> error("Unsupported restored data storage format")
        }
    }
}
