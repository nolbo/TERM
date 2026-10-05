package dev.terminal.image

import java.nio.file.Files
import java.nio.file.Path
import java.net.URI
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class DebianImageVerifierTest {
    @Test fun activationOnlyWrapsPromotionOfCompleteVerifiedImage() {
        val root = Files.createTempDirectory("debian-activation")
        try {
            val source = Files.createDirectory(root.resolve("source"))
            writeVerifiedImage(source)
            val destination = root.resolve("installed")
            var activating = false
            var activated = false
            var activationChecks = 0
            assertTrue(DebianImageInstaller.install(source, destination, activate = { promote ->
                Files.list(root).use { entries ->
                    val prepared = entries.filter { it.fileName.toString().startsWith(".installed.install-") }.findFirst().get()
                    assertTrue(DebianImageVerifier.verifyInstalled(prepared) is ImageVerification.Ready)
                }
                assertFalse(Files.exists(destination))
                activating = true
                promote()
                activated = true
                activating = false
            }, checkCancellation = {
                if (activating) activationChecks++
            }) is ImageInstallResult.Installed)
            assertTrue(activated)
            assertEquals("Promotion must not hash or copy artifacts", 1, activationChecks)
            assertTrue(DebianImageVerifier.verifyInstalled(destination) is ImageVerification.Ready)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun activationRefusesADestinationCreatedDuringPreparation() {
        val root = Files.createTempDirectory("debian-activation-race")
        try {
            val source = Files.createDirectory(root.resolve("source"))
            writeVerifiedImage(source)
            val destination = root.resolve("installed")
            assertTrue(DebianImageInstaller.install(source, destination, activate = { promote ->
                Files.createDirectory(destination)
                Files.writeString(destination.resolve("keep.txt"), "existing data")
                promote()
            }) is ImageInstallResult.Failed)
            assertEquals("existing data", Files.readString(destination.resolve("keep.txt")))
            Files.list(root).use { entries -> assertEquals(setOf(source, destination), entries.toList().toSet()) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun cancellationStopsVerificationWithoutChangingArtifacts() {
        val root = Files.createTempDirectory("debian-cancel-verification")
        try {
            writeVerifiedImage(root)
            var checks = 0
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                DebianImageVerifier.verify(root) {
                    if (++checks == 3) throw java.util.concurrent.CancellationException("cancelled")
                }
            }
            assertTrue(DebianImageVerifier.verify(root) is ImageVerification.Ready)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun cancellationDuringCopyCleansTemporaryImageAndCanRetry() {
        val root = Files.createTempDirectory("debian-cancel-copy")
        try {
            val source = Files.createDirectory(root.resolve("source"))
            writeVerifiedImage(source)
            Files.write(source.resolve("system.img"), ByteArray(300_000) { (it * 37).toByte() })
            val manifest = source.resolve("manifest.properties")
            Files.writeString(manifest, Files.readString(manifest).replace(Regex("system.sha256=[a-f0-9]+"),
                "system.sha256=${DebianImageVerifier.sha256(source.resolve("system.img"))}"))
            val destination = root.resolve("installed")
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                DebianImageInstaller.install(source, destination) {
                    Files.list(root).use { entries ->
                        entries.filter { it.fileName.toString().startsWith(".installed.install-") }.forEach { temporary ->
                            val artifact = temporary.resolve("system.img")
                            if (Files.exists(artifact) && Files.size(artifact) >= 64 * 1024)
                                throw java.util.concurrent.CancellationException("cancelled")
                        }
                    }
                }
            }
            assertFalse(Files.exists(destination))
            Files.list(root).use { entries -> assertEquals(listOf(source), entries.toList()) }
            assertTrue(DebianImageInstaller.install(source, destination) is ImageInstallResult.Installed)
            assertTrue(DebianImageVerifier.verifyInstalled(destination) is ImageVerification.Ready)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun installationPresenceRequiresCompleteRegularFiles() {
        val root = Files.createTempDirectory("debian-presence-test")
        try {
            assertFalse(DebianImageVerifier.hasInstalledImage(root))
            writeVerifiedImage(root)
            assertTrue(DebianImageVerifier.hasInstalledImage(root))
            Files.delete(root.resolve("home.img"))
            assertFalse(DebianImageVerifier.hasInstalledImage(root))
            Files.write(root.resolve("home.img"), byteArrayOf())
            assertFalse(DebianImageVerifier.hasInstalledImage(root))
            Files.delete(root.resolve("home.img"))
            Files.createSymbolicLink(root.resolve("home.img"), root.resolve("system.img"))
            assertFalse(DebianImageVerifier.hasInstalledImage(root))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun installationPresenceDoesNotAuthorizeBootOrDependOnWritableDiskHashes() {
        val root = Files.createTempDirectory("debian-presence-test")
        try {
            writeVerifiedImage(root)
            Files.writeString(root.resolve("home.img"), "guest data")
            assertTrue(DebianImageVerifier.hasInstalledImage(root))
            assertTrue(DebianImageVerifier.verifyInstalled(root) is ImageVerification.Invalid)
            Files.writeString(root.resolve("system.img"), "tampered")
            assertTrue(DebianImageVerifier.hasInstalledImage(root))
            assertTrue(DebianImageVerifier.verifyInstalled(root) is ImageVerification.Invalid)
            Files.writeString(root.resolve("manifest.properties"), "formatVersion=999")
            assertFalse(DebianImageVerifier.hasInstalledImage(root))
        } finally { root.toFile().deleteRecursively() }
    }

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
