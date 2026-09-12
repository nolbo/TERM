package dev.terminal.runtime.rootless

import dev.terminal.runtime.session.SessionRuntime
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootlessDistributionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun distributionsSeparateHomesDatabasesAndCacheCleanupButShareWorkspace() {
        val layouts = RootlessDistribution.entries.map {
            RootlessDebianLayout(File(temporary.root, "rootless/${it.directory}"), it).apply { createHostDirectories() }
        }
        assertEquals(3, layouts.map { it.rootfs }.toSet().size)
        assertEquals(3, layouts.map { it.home }.toSet().size)
        assertEquals(1, layouts.map { it.workspace }.toSet().size)
        val debianData = File(layouts[0].rootfs, "var/lib/dpkg/status").apply { parentFile.mkdirs(); writeText("keep") }
        val archDownload = File(temporary.root, "cache/archlinux/image.part").apply { parentFile.mkdirs(); writeText("keep") }
        val ubuntuCache = File(temporary.root, "cache/ubuntu").apply { mkdirs() }
        File(ubuntuCache, "partial").writeText("partial")
        cleanupIncompleteRootlessInstall(layouts[1].rootfs.toPath(), ubuntuCache.toPath())
        assertEquals("keep", debianData.readText())
        assertEquals("keep", archDownload.readText())
        assertTrue(ubuntuCache.listFiles()!!.isEmpty())
    }

    @Test fun retryRemovesReadOnlyDirectoriesWithoutFollowingLinksToSiblingData() {
        val rootfs = File(temporary.root, "rootless/archlinux/rootfs")
        val certificates = File(rootfs, "etc/ssl/certs").apply { mkdirs() }
        File(certificates, "certificate").writeText("temporary")
        val retained = File(temporary.root, "keep/data").apply { parentFile.mkdirs(); writeText("keep") }
        java.nio.file.Files.createSymbolicLink(File(rootfs, "sibling").toPath(), retained.parentFile.toPath())
        java.nio.file.Files.setPosixFilePermissions(certificates.toPath(), emptySet())
        val cache = temporary.newFolder("download")
        cleanupIncompleteRootlessInstall(rootfs.toPath(), cache.toPath())
        assertFalse(rootfs.exists())
        assertEquals("keep", retained.readText())
    }

    @Test fun archRequiresPacmanWhileDebianAndUbuntuRequireApt() {
        RootlessDistribution.entries.forEach { distribution ->
            val layout = RootlessDebianLayout(File(temporary.root, "rootless/${distribution.directory}"), distribution)
            File(layout.rootfs, "bin/sh").apply { parentFile.mkdirs(); writeText("shell"); setExecutable(true) }
            val wrongManager = if (distribution == RootlessDistribution.ARCH) "apt" else "pacman"
            File(layout.rootfs, "usr/bin/$wrongManager").apply { parentFile.mkdirs(); writeText("wrong"); setExecutable(true) }
            assertTrue(layout.validateInstalledRootfs().isFailure)
            File(layout.rootfs, distribution.packageExecutables.first()).apply { writeText("manager"); setExecutable(true) }
            assertTrue(layout.validateInstalledRootfs().isSuccess)
        }
    }

    @Test fun archBootstrapDiscardsStaleRepositoryIndexesButPreservesInstalledPackagesAndKeys() {
        val rootfs = temporary.newFolder("arch-staging")
        val sync = File(rootfs, "var/lib/pacman/sync/extra.db").apply { parentFile.mkdirs(); writeText("old index") }
        val local = File(rootfs, "var/lib/pacman/local/package/desc").apply { parentFile.mkdirs(); writeText("installed") }
        val keys = File(rootfs, "etc/pacman.d/gnupg/pubring.gpg").apply { parentFile.mkdirs(); writeText("keys") }
        configureRootlessRootfs(rootfs.toPath(), RootlessDistribution.ARCH)
        assertFalse(sync.exists())
        assertEquals("installed", local.readText())
        assertEquals("keys", keys.readText())
    }

    @Test fun legacyDebianIdentityIsPreservedAndAllPrefixesRouteUnambiguously() {
        assertEquals(SessionRuntime.ROOTLESS, SessionRuntime.fromSessionId("rootless-existing"))
        RootlessDistribution.entries.forEach { distro ->
            assertEquals(distro.runtime, SessionRuntime.fromSessionId(distro.runtime.idPrefix + "test"))
            assertEquals(distro.runtime, SessionRuntime.fromWireName(distro.runtime.wireName))
            assertEquals("https", distro.archiveUrl.scheme)
            assertTrue(distro.sha256.matches(Regex("[0-9a-f]{64}")))
        }
        assertNull(SessionRuntime.fromWireName("ROOTLESS_UNKNOWN"))
    }
}
