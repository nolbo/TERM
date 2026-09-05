package dev.terminal.runtime.rootless

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootlessInstallRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun retryRemovesOnlyInvalidRootfsDownloadsAndStagingTrees() {
        val root = temporary.newFolder("rootless").toPath()
        val rootfs = Files.createDirectories(root.resolve("rootfs/bin"))
            .parent
        Files.write(rootfs.resolve("partial"), "incomplete".encodeToByteArray())
        val downloadDirectory = Files.createDirectories(root.resolve("downloads"))
        Files.write(downloadDirectory.resolve("partial.tar.xz"), "incomplete".encodeToByteArray())
        val staging = Files.createDirectories(root.resolve(".rootfs-install-interrupted/usr"))
            .parent
        val unrelated = Files.write(root.resolve("keep-me"), "unrelated".encodeToByteArray())

        cleanupIncompleteRootlessInstall(rootfs, downloadDirectory)

        assertFalse(Files.exists(rootfs))
        assertTrue(Files.list(downloadDirectory).use { it.findAny().isEmpty })
        assertFalse(Files.exists(staging))
        assertTrue(Files.exists(unrelated))
    }
}
