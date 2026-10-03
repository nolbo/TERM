package dev.terminal.image

import java.nio.file.Files
import java.nio.file.Path
import java.net.URI
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class DebianImageVerifierTest {
    @Test fun hashesEveryByteAcrossMultipleReadBuffers() {
        val artifact = Files.createTempFile("debian-hash-test", ".img")
        try {
            Files.write(artifact, ByteArray(600_017) { (it * 37).toByte() })
            assertEquals("02ad2bd651b7aeb8b36f9b66b9cb677eb01beae890a133960b7ce9de054eaf1c",
                DebianImageVerifier.sha256(artifact))
        } finally { Files.delete(artifact) }
    }

    @Test
    fun verifiesKnownGoodDebianArm64Layout() {
        val root = Files.createTempDirectory("debian-image-test")
        try {
            Files.writeString(root.resolve("system.img"), "system")
            Files.writeString(root.resolve("vmlinuz"), "kernel")
            Files.writeString(root.resolve("initrd.img"), "initrd")
            Files.writeString(root.resolve("home.img"), "home")
            Files.writeString(root.resolve("config.img"), "config")
            Files.writeString(root.resolve("manifest.properties"), """
                formatVersion=1
                distribution=debian
                architecture=arm64
                kernel.sha256=${DebianImageVerifier.sha256(root.resolve("vmlinuz"))}
                initrd.sha256=${DebianImageVerifier.sha256(root.resolve("initrd.img"))}
                system.sha256=${DebianImageVerifier.sha256(root.resolve("system.img"))}
                home.sha256=${DebianImageVerifier.sha256(root.resolve("home.img"))}
                config.sha256=${DebianImageVerifier.sha256(root.resolve("config.img"))}
            """.trimIndent())

            assertTrue(DebianImageVerifier.verify(root) is ImageVerification.Ready)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsTamperedArtifact() {
        val root = Files.createTempDirectory("debian-image-test")
        try {
            Files.writeString(root.resolve("system.img"), "system")
            Files.writeString(root.resolve("vmlinuz"), "kernel")
            Files.writeString(root.resolve("initrd.img"), "initrd")
            Files.writeString(root.resolve("home.img"), "home")
            Files.writeString(root.resolve("config.img"), "config")
            Files.writeString(root.resolve("manifest.properties"), """
                formatVersion=1
                distribution=debian
                architecture=arm64
                kernel.sha256=${DebianImageVerifier.sha256(root.resolve("vmlinuz"))}
                initrd.sha256=${DebianImageVerifier.sha256(root.resolve("initrd.img"))}
                system.sha256=${"0".repeat(64)}
                home.sha256=${DebianImageVerifier.sha256(root.resolve("home.img"))}
                config.sha256=${DebianImageVerifier.sha256(root.resolve("config.img"))}
            """.trimIndent())

            assertTrue(DebianImageVerifier.verify(root) is ImageVerification.Invalid)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun installsVerifiedStagingDirectoryWithoutReplacingExistingImage() {
        val root = Files.createTempDirectory("debian-install-test")
        val staged = root.resolve("staged")
        val destination = root.resolve("vm/default")
        try {
            Files.createDirectories(staged)
            Files.createDirectories(destination.parent)
            writeVerifiedImage(staged)

            assertTrue(DebianImageInstaller.install(staged, destination) is ImageInstallResult.Installed)
            assertTrue(DebianImageVerifier.verify(destination) is ImageVerification.Ready)
            assertTrue(DebianImageInstaller.install(staged, destination) is ImageInstallResult.AlreadyInstalled)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun catalogReleaseRejectsNonHttpsOrigin() {
        assertThrows(IllegalArgumentException::class.java) {
            DebianImageRelease("debian-1", URI("http://example.invalid/manifest.properties"), "0".repeat(64), 1024)
        }
    }

    @Test
    fun writableDiskChangesAreAcceptedOnlyAfterVerifiedInstallation() {
        val root = Files.createTempDirectory("debian-persistence-test")
        try {
            val source = Files.createDirectory(root.resolve("source"))
            writeVerifiedImage(source)
            val installed = root.resolve("installed")
            assertTrue(DebianImageInstaller.install(source, installed) is ImageInstallResult.Installed)
            Files.writeString(installed.resolve("home.img"), "guest writes after first boot")
            assertTrue(DebianImageVerifier.verifyInstalled(installed) is ImageVerification.Ready)
            assertTrue(DebianImageVerifier.verify(installed) is ImageVerification.Invalid)
            Files.delete(installed.resolve(".installed-manifest-sha256"))
            assertTrue(DebianImageVerifier.verifyInstalled(installed) is ImageVerification.Invalid)
            Files.writeString(source.resolve("home.img"), "unverified disk")
            assertTrue(DebianImageVerifier.verifyInstalled(source) is ImageVerification.Invalid)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun installedImageStillRejectsChangedImmutableArtifacts() {
        val root = Files.createTempDirectory("debian-immutable-test")
        try {
            val source = Files.createDirectory(root.resolve("source"))
            writeVerifiedImage(source)
            val installed = root.resolve("installed")
            assertTrue(DebianImageInstaller.install(source, installed) is ImageInstallResult.Installed)
            Files.writeString(installed.resolve("system.img"), "tampered")
            assertTrue(DebianImageVerifier.verifyInstalled(installed) is ImageVerification.Invalid)
        } finally { root.toFile().deleteRecursively() }
    }

    private fun writeVerifiedImage(root: Path) {
        Files.writeString(root.resolve("system.img"), "system")
        Files.writeString(root.resolve("vmlinuz"), "kernel")
        Files.writeString(root.resolve("initrd.img"), "initrd")
        Files.writeString(root.resolve("home.img"), "home")
        Files.writeString(root.resolve("config.img"), "config")
        Files.writeString(root.resolve("manifest.properties"), """
            formatVersion=1
            distribution=debian
            architecture=arm64
            kernel.sha256=${DebianImageVerifier.sha256(root.resolve("vmlinuz"))}
            initrd.sha256=${DebianImageVerifier.sha256(root.resolve("initrd.img"))}
            system.sha256=${DebianImageVerifier.sha256(root.resolve("system.img"))}
            home.sha256=${DebianImageVerifier.sha256(root.resolve("home.img"))}
            config.sha256=${DebianImageVerifier.sha256(root.resolve("config.img"))}
        """.trimIndent())
    }
}
