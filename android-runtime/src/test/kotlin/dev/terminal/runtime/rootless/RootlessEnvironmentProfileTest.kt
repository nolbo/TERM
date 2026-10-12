package dev.terminal.runtime.rootless

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootlessEnvironmentProfileTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun installed(distribution: RootlessDistribution): RootlessDebianLayout =
        RootlessDebianLayout(File(temporary.root, "rootless/${distribution.directory}"), distribution).apply {
            createHostDirectories()
            listOf("bin/sh", distribution.packageExecutables.first()).forEach {
                File(rootfs, it).apply { parentFile!!.mkdirs(); writeText("executable"); setExecutable(true) }
            }
        }

    @Test fun shellAndPackageManagerAloneNeverMeanPrepared() {
        RootlessDistribution.entries.forEach { distribution ->
            val layout = installed(distribution)
            assertTrue(layout.validateInstalledRootfs().isSuccess)
            assertTrue(RootlessEnvironmentProfile.validate(layout).isFailure)
        }
    }

    @Test fun metadataUpdatesPreserveReadOnlyDistributionRootPermissions() {
        val layout = installed(RootlessDistribution.ARCH)
        val permissions = java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x")
        Files.setPosixFilePermissions(layout.rootfs.toPath(), permissions)
        RootlessEnvironmentProfile.markVerified(layout)
        assertEquals(permissions, Files.getPosixFilePermissions(layout.rootfs.toPath()))
        RootlessEnvironmentProfile.invalidate(layout)
        assertFalse(File(layout.rootfs, RootlessEnvironmentProfile.MARKER).exists())
        assertEquals(permissions, Files.getPosixFilePermissions(layout.rootfs.toPath()))
        layout.rootfs.setWritable(true, true)
    }

    @Test fun prootHardlinkTargetsResolveOnlyWithinTheirOwnRootfs() {
        val layout = installed(RootlessDistribution.ARCH)
        val backing = File(layout.rootfs, "usr/share/zoneinfo/.l2s.UTC0001").apply { parentFile!!.mkdirs(); writeText("zone") }
        val link = File(layout.rootfs, "usr/share/zoneinfo/UTC")
        Files.createSymbolicLink(link.toPath(), backing.absoluteFile.toPath())
        assertEquals(backing, resolveRootlessFile(layout.rootfs, "usr/share/zoneinfo/UTC"))
        Files.delete(link.toPath())
        val outside = temporary.newFile("outside-zone")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertFalse(resolveRootlessFile(layout.rootfs, "usr/share/zoneinfo/UTC")?.isFile == true)
    }

    @Test fun readinessRequiresVerifiedVersionDefaultsAndRuntimeTools() {
        val layout = installed(RootlessDistribution.DEBIAN)
        RootlessEnvironmentProfile.commands(layout.distribution).forEach {
            File(layout.rootfs, "usr/bin/$it").apply { parentFile!!.mkdirs(); writeText("executable"); setExecutable(true) }
        }
        File(layout.home, ".bashrc").writeText("user rc")
        File(layout.home, ".profile").writeText("user profile")
        val timezone = File(layout.rootfs, "usr/share/zoneinfo/Etc/UTC").apply { parentFile!!.mkdirs(); writeText("zone") }
        val localtime = File(layout.rootfs, "etc/localtime").apply { parentFile!!.mkdirs() }
        Files.createSymbolicLink(localtime.toPath(), java.nio.file.Path.of("/usr/share/zoneinfo/Etc/UTC"))
        assertTrue(RootlessEnvironmentProfile.validate(layout).isFailure)
        RootlessEnvironmentProfile.markVerified(layout)
        assertTrue(RootlessEnvironmentProfile.validate(layout).isSuccess)
        assertEquals(timezone, resolveRootlessFile(layout.rootfs, "etc/localtime"))
        File(layout.rootfs, "usr/bin/less").delete()
        assertTrue(RootlessEnvironmentProfile.validate(layout).isFailure)
        assertTrue(File(layout.rootfs, "bin/sh").exists())
    }

    @Test fun packageSelectionsFollowEachDistributionWithoutAnInventedCommonBundle() {
        assertEquals(listOf("?priority(required)", "?priority(important)", "?priority(standard)"),
            RootlessEnvironmentProfile.packages(RootlessDistribution.DEBIAN))
        assertEquals(listOf("ubuntu-minimal", "ubuntu-standard", "ubuntu-server"),
            RootlessEnvironmentProfile.packages(RootlessDistribution.UBUNTU))
        assertEquals(listOf("base"), RootlessEnvironmentProfile.packages(RootlessDistribution.ARCH))
        assertFalse(RootlessEnvironmentProfile.commands(RootlessDistribution.ARCH).contains("git"))
    }

    @Test fun localeSettingsAreReadAsDataAndPreserveValidUserChoices() {
        val layout = installed(RootlessDistribution.ARCH)
        val config = File(layout.rootfs, "etc/locale.conf").apply { parentFile!!.mkdirs() }
        config.writeText("LANG=\"ko_KR.UTF-8\"\nLC_TIME=en_GB.UTF-8\nLANGUAGE=ko:en\nBAD=ignored\n")
        assertEquals(mapOf("LANG" to "ko_KR.UTF-8", "LC_TIME" to "en_GB.UTF-8", "LANGUAGE" to "ko:en"), rootlessLocaleEnvironment(layout))
        config.writeText("LANG=\$(touch /outside)\n")
        assertEquals(mapOf("LANG" to "C.UTF-8"), rootlessLocaleEnvironment(layout))
        config.delete()
        Files.createSymbolicLink(config.toPath(), java.nio.file.Path.of("../../../../outside"))
        assertEquals(mapOf("LANG" to "C.UTF-8"), rootlessLocaleEnvironment(layout))
    }
}
