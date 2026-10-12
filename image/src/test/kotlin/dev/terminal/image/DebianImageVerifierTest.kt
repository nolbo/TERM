package dev.terminal.image

import java.nio.file.Files
import java.nio.file.Path
import java.net.URI
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DebianImageVerifierTest {
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
