package dev.terminal.runtime.rootless

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootlessStorageFormatTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun newInstallationUsesKernelLinksAndPreservesReadOnlyRootMode() {
        val layout = RootlessDebianLayout(File(temporary.root, "rootless/debian"))
        Files.createDirectories(layout.rootfs.toPath())
        val permissions = PosixFilePermissions.fromString("r-xr-xr-x")
        Files.setPosixFilePermissions(layout.rootfs.toPath(), permissions)
        try {
            RootlessStorageFormat.prepareNewInstallation(layout.rootfs.toPath())
            assertTrue(RootlessStorageFormat.usesNativeLinks(layout.rootfs.toPath()))
            assertEquals(permissions, Files.getPosixFilePermissions(layout.rootfs.toPath()))
            val launch = buildRootlessLaunchSpec(layout, File("/proot"), File("/loader"),
                "/bin/sh", "/root", emptyMap()).arguments.toList()
            assertFalse(launch.contains("--link2symlink"))
            assertTrue(launch.contains("-0"))
            Files.list(layout.rootfs.toPath()).use { paths ->
                assertEquals(1L, paths.count()) // No probe artifacts survive.
            }
        } finally {
            Files.setPosixFilePermissions(layout.rootfs.toPath(), PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test fun deniedNativeLinkSelectsVerifiedEmulationWithoutLeavingProbeFiles() {
        val root = temporary.newFolder("denied").toPath()
        val permissions = PosixFilePermissions.fromString("r-xr-xr-x")
        Files.setPosixFilePermissions(root, permissions)
        try {
            RootlessStorageFormat.prepareNewInstallation(root) { link, original ->
                throw java.nio.file.FileSystemException(link.toString(), original.toString(), "Operation not permitted")
            }
            assertFalse(RootlessStorageFormat.usesNativeLinks(root))
            assertTrue(RootlessStorageFormat.usesEmulatedLinks(root))
            assertEquals(permissions, Files.getPosixFilePermissions(root))
            Files.list(root).use { assertEquals(1L, it.count()) }
        } finally {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test fun failedProbeIntegrityDoesNotPublishAFormat() {
        val root = temporary.newFolder("bad-link").toPath()
        assertTrue(runCatching {
            RootlessStorageFormat.prepareNewInstallation(root) { link, _ -> Files.write(link, byteArrayOf(2)) }
        }.isFailure)
        assertFalse(RootlessStorageFormat.usesNativeLinks(root))
        assertFalse(RootlessStorageFormat.usesEmulatedLinks(root))
        Files.list(root).use { assertEquals(0L, it.count()) }
    }

    @Test fun unmarkedUserImageIsNotSilentlyReinterpreted() {
        val layout = RootlessDebianLayout(File(temporary.root, "rootless/debian"))
        assertFalse(RootlessStorageFormat.usesNativeLinks(layout.rootfs.toPath()))
        val launch = buildRootlessLaunchSpec(layout, File("/proot"), File("/loader"),
            "/bin/sh", "/root", emptyMap()).arguments.toList()
        assertTrue(launch.contains("--link2symlink"))
    }
}
