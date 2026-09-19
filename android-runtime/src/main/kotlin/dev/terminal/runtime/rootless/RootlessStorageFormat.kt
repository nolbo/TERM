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
    fun prepareNewInstallation(root: Path, createHardLink: (Path, Path) -> Path = Files::createLink) {
        val permissions = Files.getPosixFilePermissions(root, NOFOLLOW_LINKS)
        try {
            Files.setPosixFilePermissions(root, permissions + OWNER_WRITE)
            val probe = Files.createTempDirectory(root, ".native-link-probe-")
            var nativeLinks = true
            try {
                val original = Files.write(probe.resolve("original"), byteArrayOf(1), CREATE_NEW)
                val linked = probe.resolve("linked")
                try {
                    createHardLink(linked, original)
                } catch (_: IOException) {
                    nativeLinks = false
                } catch (_: UnsupportedOperationException) {
                    nativeLinks = false
                }
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
}
