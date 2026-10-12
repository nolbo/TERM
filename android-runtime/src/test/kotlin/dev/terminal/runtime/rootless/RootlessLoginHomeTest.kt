package dev.terminal.runtime.rootless

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootlessLoginHomeTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun layout(distribution: RootlessDistribution) =
        RootlessDebianLayout(File(temporary.root, "rootless/${distribution.directory}"), distribution)
            .apply {
                createHostDirectories()
                File(rootfs, "etc/skel").mkdirs()
                File(rootfs, "etc/skel/.bashrc").writeText("# official bashrc\n")
                File(rootfs, "etc/skel/.profile").writeText("# official Debian profile\n")
                File(rootfs, "etc/skel/.bash_profile").writeText("# official Arch profile\n")
            }

    @Test fun ubuntuPreparationAddsQuietLoginWithoutChangingExistingHomeContent() {
        val layout = layout(RootlessDistribution.UBUNTU)
        val bashrc = File(layout.home, ".bashrc").apply { writeText("export CUSTOM=keep\n") }
        configureRootlessLoginHome(layout)
        assertTrue(File(layout.home, ".hushlogin").isFile)
        assertEquals("export CUSTOM=keep\n", bashrc.readText())
        File(layout.home, ".hushlogin").writeText("existing marker")
        configureRootlessLoginHome(layout)
        assertEquals("existing marker", File(layout.home, ".hushlogin").readText())
    }

    @Test fun everyDistributionRestoresItsOwnDefaultsWithoutCommonCustomization() {
        RootlessDistribution.entries.forEach { distribution ->
            val layout = layout(distribution)
            val source = File(layout.rootfs, "root/.bashrc").apply {
                parentFile.mkdirs(); writeText("# original ${distribution.directory}\nalias ls='ls --color=auto'\n")
            }
            configureRootlessLoginHome(layout)
            assertEquals(source.readText(), File(layout.home, ".bashrc").readText())
            val login = if (distribution == RootlessDistribution.ARCH) ".bash_profile" else ".profile"
            assertEquals(File(layout.rootfs, "etc/skel/$login").readText(), File(layout.home, login).readText())
            File(layout.home, ".bashrc").writeText("custom")
            configureRootlessLoginHome(layout)
            assertEquals("custom", File(layout.home, ".bashrc").readText())
        }
    }

    @Test fun existingProfileIsNotShadowedAndSecretsAreNotCopied() {
        val layout = layout(RootlessDistribution.ARCH)
        File(layout.home, ".profile").writeText("user login")
        File(layout.rootfs, "root/.ssh/key").apply { parentFile.mkdirs(); writeText("not a shell default") }
        configureRootlessLoginHome(layout)
        assertFalse(File(layout.home, ".bash_profile").exists())
        assertEquals("user login", File(layout.home, ".profile").readText())
        assertFalse(File(layout.home, ".ssh").exists())
    }

    @Test fun guestSourceCannotEscapeRootfsAndExistingDanglingLinksArePreserved() {
        val layout = layout(RootlessDistribution.DEBIAN)
        val outside = File(temporary.root, "outside").apply { mkdirs() }
        File(outside, ".bashrc").writeText("outside secret")
        Files.createSymbolicLink(File(layout.rootfs, "root").toPath(), outside.toPath())
        configureRootlessLoginHome(layout)
        assertEquals("# official bashrc\n", File(layout.home, ".bashrc").readText())
        File(layout.home, ".bashrc").delete()
        val missing = File(temporary.root, "missing")
        Files.createSymbolicLink(File(layout.home, ".bashrc").toPath(), missing.toPath())
        configureRootlessLoginHome(layout)
        assertTrue(Files.isSymbolicLink(File(layout.home, ".bashrc").toPath()))
        assertFalse(missing.exists())
    }

    @Test fun otherDistributionsDoNotGetQuietLogin() {
        listOf(RootlessDistribution.DEBIAN, RootlessDistribution.ARCH).forEach {
            val layout = layout(it)
            configureRootlessLoginHome(layout)
            assertFalse(File(layout.home, ".hushlogin").exists())
        }
    }

    @Test fun markerSymlinkNeverWritesToItsTarget() {
        val layout = layout(RootlessDistribution.UBUNTU)
        val target = File(temporary.root, "retained").apply { writeText("keep") }
        val marker = File(layout.home, ".hushlogin").toPath()
        Files.createSymbolicLink(marker, target.toPath())
        configureRootlessLoginHome(layout)
        assertTrue(Files.isSymbolicLink(marker))
        assertEquals("keep", target.readText())
        target.delete()
        configureRootlessLoginHome(layout)
        assertFalse(target.exists())
    }
}
