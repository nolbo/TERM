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

    @Test fun unmarkedUserImageIsNotSilentlyReinterpreted() {
        val layout = RootlessDebianLayout(File(temporary.root, "rootless/debian"))
        assertFalse(RootlessStorageFormat.usesNativeLinks(layout.rootfs.toPath()))
        val launch = buildRootlessLaunchSpec(layout, File("/proot"), File("/loader"),
            "/bin/sh", "/root", emptyMap()).arguments.toList()
        assertTrue(launch.contains("--link2symlink"))
    }
}
