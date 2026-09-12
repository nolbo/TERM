package dev.terminal.runtime.rootless

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootlessImageRemovalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun removalPreservesHomeWorkspaceSiblingAndSymlinkTargets() {
        val app = temporary.newFolder().toPath()
        val distro = Files.createDirectories(app.resolve("rootless/debian"))
        val rootfs = Files.createDirectories(distro.resolve("rootfs"))
        val home = Files.createDirectories(distro.resolve("home"))
        val workspace = Files.createDirectories(app.resolve("workspace"))
        val sibling = Files.createDirectories(app.resolve("rootless/ubuntu/rootfs"))
        val cache = Files.createDirectories(app.resolve("cache/debian"))
        val retained = listOf(home, workspace, sibling).map { Files.writeString(it.resolve("keep"), "retained") }
        Files.createSymbolicLink(rootfs.resolve("outside"), workspace)
        Files.createSymbolicLink(rootfs.resolve("dangling"), app.resolve("absent"))
        Files.writeString(rootfs.resolve("package"), "installed")
        Files.writeString(cache.resolve("archive"), "cached")
        val staging = Files.createDirectories(distro.resolve(".rootfs-install-old"))
        removeRootlessImage(rootfs, cache)
        assertFalse(Files.exists(rootfs, NOFOLLOW_LINKS))
        assertFalse(Files.exists(cache))
        assertFalse(Files.exists(staging))
        retained.forEach { assertEquals("retained", Files.readString(it)) }
        removeRootlessImage(rootfs, cache) // Already removed is harmless.
    }

    @Test fun removalCanAlsoDeleteOnlyTheSelectedDistributionHome() {
        val app = temporary.newFolder().toPath()
        val distro = Files.createDirectories(app.resolve("rootless/debian"))
        val rootfs = Files.createDirectories(distro.resolve("rootfs"))
        val home = Files.createDirectories(distro.resolve("home"))
        val workspace = Files.createDirectories(app.resolve("workspace"))
        val sibling = Files.createDirectories(app.resolve("rootless/ubuntu/home"))
        val cache = Files.createDirectories(app.resolve("cache/debian"))
        Files.writeString(home.resolve("private"), "delete")
        val retained = listOf(workspace, sibling).map { Files.writeString(it.resolve("keep"), "retained") }

        removeRootlessImage(rootfs, cache, home, removeHome = true)

        assertFalse(Files.exists(rootfs, NOFOLLOW_LINKS))
        assertFalse(Files.exists(home, NOFOLLOW_LINKS))
        assertFalse(Files.exists(cache, NOFOLLOW_LINKS))
        retained.forEach { assertEquals("retained", Files.readString(it)) }
    }

    @Test fun homeDeletionDoesNotFollowHomeSymlinks() {
        val app = temporary.newFolder().toPath()
        val distro = Files.createDirectories(app.resolve("rootless/debian"))
        val rootfs = Files.createDirectories(distro.resolve("rootfs"))
        val outside = Files.createDirectories(app.resolve("outside"))
        val home = distro.resolve("home")
        Files.writeString(outside.resolve("keep"), "retained")
        Files.createSymbolicLink(home, outside)

        removeRootlessImage(rootfs, app.resolve("cache"), home, removeHome = true)

        assertFalse(Files.exists(home, NOFOLLOW_LINKS))
        assertEquals("retained", Files.readString(outside.resolve("keep")))
    }
}
