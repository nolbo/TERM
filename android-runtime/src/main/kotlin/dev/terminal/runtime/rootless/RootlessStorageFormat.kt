package dev.terminal.runtime.rootless

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE

/** New installations use kernel hardlinks; old images are never converted in place. */
internal object RootlessStorageFormat {
    private const val MARKER = ".terminal-storage-format"
    private const val NATIVE_LINKS = "native-hardlinks-1\n"

    fun usesNativeLinks(root: Path): Boolean {
        val marker = root.resolve(MARKER)
        return Files.isRegularFile(marker, NOFOLLOW_LINKS) &&
            Files.size(marker) == NATIVE_LINKS.length.toLong() &&
            Files.newInputStream(marker).use { input ->
                // Bound reads even if a guest changes the marker after the size check.
                NATIVE_LINKS.all { input.read() == it.code } && input.read() == -1
            }
    }

    /** Run in unpublished staging, before package setup can create emulated links. */
    fun prepareNewInstallation(root: Path) {
        val permissions = Files.getPosixFilePermissions(root, NOFOLLOW_LINKS)
        try {
            Files.setPosixFilePermissions(root, permissions + OWNER_WRITE)
            val probe = Files.createTempDirectory(root, ".native-link-probe-")
            try {
                val original = Files.write(probe.resolve("original"), byteArrayOf(1), CREATE_NEW)
                val linked = Files.createLink(probe.resolve("linked"), original)
                check(Files.isSameFile(original, linked)) { "Rootless storage must support native hardlinks" }
                Files.delete(original)
                check(Files.readAllBytes(linked).contentEquals(byteArrayOf(1))) {
                    "Rootless hardlink did not survive unlink"
                }
            } finally {
                Files.deleteIfExists(probe.resolve("linked"))
                Files.deleteIfExists(probe.resolve("original"))
                Files.delete(probe)
            }
            Files.write(root.resolve(MARKER), NATIVE_LINKS.toByteArray(Charsets.UTF_8), CREATE_NEW)
        } finally {
            Files.setPosixFilePermissions(root, permissions)
        }
    }
}
