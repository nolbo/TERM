package dev.terminal.image

import java.nio.file.Files
import java.nio.file.Path
import java.net.URI
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class AvfImageVerifierTest {
    @Test fun ext4RootIsWritableAfterInstallationWhileTheSeedRemainsVerified() {
        for (distribution in AvfDistribution.entries) {
            val root = Files.createTempDirectory("avf-ext4-root")
            try {
                val artifacts = mapOf("kernel" to "vmlinuz", "initrd" to "initrd.img",
                    "system" to "system.img", "home" to "home.img", "config" to "config.img")
                artifacts.values.forEach { Files.writeString(root.resolve(it), it) }
                Files.writeString(root.resolve("manifest.properties"),
                    "formatVersion=2\ndistribution=${distribution.id}\narchitecture=arm64\n" +
                        artifacts.entries.joinToString("\n") { (key, name) -> "$key.sha256=${AvfImageVerifier.sha256(root.resolve(name))}" })
                val installed = AvfImageInstaller.verifyForStartup(root, distribution) as ImageVerification.Ready
                assertEquals("/dev/vdb", installed.manifest.rootDevice)
                assertEquals("rw", installed.manifest.rootMountMode)
                Files.writeString(root.resolve("home.img"), "package database, home and container data")
                assertTrue(AvfImageVerifier.verifyInstalled(root, distribution) is ImageVerification.Ready)
                assertTrue(AvfImageVerifier.verify(root, distribution) is ImageVerification.Invalid)
                Files.writeString(root.resolve("system.img"), "changed seed")
                assertTrue(AvfImageVerifier.verifyInstalled(root, distribution) is ImageVerification.Invalid)
            } finally { root.toFile().deleteRecursively() }
        }
    }

    @Test fun privateSharingRequiresItsOwnVerifiedFeatureAndRejectsUnknownVersions() {
        for (version in listOf(null, "1", "0", "2")) {
            val root = Files.createTempDirectory("private-share-feature")
            try {
                writeVerifiedImage(root)
                version?.let { Files.writeString(root.resolve("manifest.properties"), "\nshareBrokerVersion=$it\n",
                    java.nio.file.StandardOpenOption.APPEND) }
                val result = AvfImageVerifier.verify(root)
                if (version == null || version == "1") assertEquals(if (version == null) 0 else 1,
                    (result as ImageVerification.Ready).manifest.shareBrokerVersion)
                else assertTrue(result is ImageVerification.Invalid)
            } finally { root.toFile().deleteRecursively() }
        }
    }
    @Test fun androidStorageRequiresItsOwnSupportedMarkerAndVirtiofs() {
        for (features in listOf("", "virtiofsVersion=1\n", "androidStorageVersion=1\n", "virtiofsVersion=1\nandroidStorageVersion=1\n",
            "virtiofsVersion=1\nandroidStorageVersion=2\n")) {
            val root = Files.createTempDirectory("android-storage-feature")
            try {
                writeVerifiedImage(root)
                Files.writeString(root.resolve("manifest.properties"), "\n$features", java.nio.file.StandardOpenOption.APPEND)
                val verification = AvfImageVerifier.verify(root)
                if (features.contains("androidStorageVersion") && features != "virtiofsVersion=1\nandroidStorageVersion=1\n") {
                    assertTrue(verification is ImageVerification.Invalid)
                    assertFalse(AvfImageVerifier.hasInstalledImage(root))
                } else assertEquals(if (features.contains("androidStorageVersion")) 1 else 0,
                    (verification as ImageVerification.Ready).manifest.androidStorageVersion)
            } finally { root.toFile().deleteRecursively() }
        }
    }
    @Test fun virtiofsIsAnExplicitVerifiedImageFeatureAndLegacyImagesRemainUsable() {
        val root = Files.createTempDirectory("debian-virtiofs")
        try {
            writeVerifiedImage(root)
            assertEquals(0, (AvfImageVerifier.verify(root) as ImageVerification.Ready).manifest.virtiofsVersion)
            Files.writeString(root.resolve("manifest.properties"), "\nvirtiofsVersion=1\n", java.nio.file.StandardOpenOption.APPEND)
            assertEquals(1, (AvfImageVerifier.verify(root) as ImageVerification.Ready).manifest.virtiofsVersion)
            Files.writeString(root.resolve("manifest.properties"), "\nvirtiofsVersion=2\n", java.nio.file.StandardOpenOption.APPEND)
            assertTrue(AvfImageVerifier.verify(root) is ImageVerification.Invalid)
            assertFalse(AvfImageVerifier.hasInstalledImage(root))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun aptInitializationIsRecordedBeforeActivationAndDoesNotInvalidateWritableState() {
        val root = Files.createTempDirectory("debian-apt-init")
        try {
            val source = Files.createDirectory(root.resolve("source"))
            writeVerifiedImage(source, aptInitializationVersion = "1")
            val destination = root.resolve("installed")
            assertTrue(AvfImageInstaller.install(source, destination, initializePackageIndexes = true,
                activate = { promote ->
                    Files.list(root).use { entries ->
                        val prepared = entries.filter { it.fileName.toString().startsWith(".installed.install-") }.findFirst().get()
                        assertTrue(AvfPackageInitialization.isRequired(prepared))
                        assertEquals(null, AvfPackageInitialization.completedAt(prepared))
                    }
                    promote()
                }) is ImageInstallResult.Installed)
            assertTrue(AvfPackageInitialization.isRequired(destination))
            assertEquals(null, AvfPackageInitialization.completedAt(destination))
            Files.writeString(destination.resolve("home.img"), "updated APT indexes")
            assertTrue(AvfImageVerifier.verifyInstalled(destination) is ImageVerification.Ready)
            AvfPackageInitialization.markComplete(destination, 1_800_000_000)
            assertEquals(1_800_000_000L, AvfPackageInitialization.completedAt(destination))
            assertTrue(AvfImageVerifier.verifyInstalled(destination) is ImageVerification.Ready)
            Files.writeString(destination.resolve("manifest.properties"), "changed manifest")
            assertEquals(null, AvfPackageInitialization.completedAt(destination))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun legacyImagesAreNotAutomaticallyMarkedForInitialization() {
        val root = Files.createTempDirectory("debian-apt-legacy")
        try {
            writeVerifiedImage(root)
            assertFalse(AvfPackageInitialization.isRequired(root))
            assertEquals(null, AvfPackageInitialization.completedAt(root))
            Files.createSymbolicLink(root.resolve(AvfPackageInitialization.FILE_NAME), root.resolve("manifest.properties"))
            assertTrue(AvfPackageInitialization.isRequired(root))
            assertEquals(null, AvfPackageInitialization.completedAt(root))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun onlineInstallationOfLegacyImageDoesNotRequireAMissingGuestEndpoint() {
        val root = Files.createTempDirectory("debian-apt-legacy-install")
        try {
            val source = Files.createDirectory(root.resolve("source"))
            writeVerifiedImage(source)
            val destination = root.resolve("installed")
            assertTrue(AvfImageInstaller.install(source, destination,
                initializePackageIndexes = true) is ImageInstallResult.Installed)
            assertFalse(AvfPackageInitialization.isRequired(destination))
            assertEquals(0, (AvfImageVerifier.verifyInstalled(destination) as ImageVerification.Ready)
                .manifest.aptInitializationVersion)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun unknownAptInitializationVersionIsRejectedBeforeInstallation() {
        val root = Files.createTempDirectory("debian-apt-unknown-version")
        try {
            val source = Files.createDirectory(root.resolve("source"))
            writeVerifiedImage(source, aptInitializationVersion = "2")
            val destination = root.resolve("installed")
            assertFalse(AvfImageVerifier.hasInstalledImage(source))
            assertTrue(AvfImageVerifier.verify(source) is ImageVerification.Invalid)
            assertTrue(AvfImageInstaller.install(source, destination,
                initializePackageIndexes = true) is ImageInstallResult.Failed)
            assertFalse(Files.exists(destination))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun activationOnlyWrapsPromotionOfCompleteVerifiedImage() {
        val root = Files.createTempDirectory("debian-activation")
        try {
            val source = Files.createDirectory(root.resolve("source"))
            writeVerifiedImage(source)
            val destination = root.resolve("installed")
            var activating = false
            var activated = false
            var activationChecks = 0
            assertTrue(AvfImageInstaller.install(source, destination, activate = { promote ->
                Files.list(root).use { entries ->
                    val prepared = entries.filter { it.fileName.toString().startsWith(".installed.install-") }.findFirst().get()
                    assertTrue(AvfImageVerifier.verifyInstalled(prepared) is ImageVerification.Ready)
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
            assertTrue(AvfImageVerifier.verifyInstalled(destination) is ImageVerification.Ready)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun activationRefusesADestinationCreatedDuringPreparation() {
        val root = Files.createTempDirectory("debian-activation-race")
        try {
            val source = Files.createDirectory(root.resolve("source"))
            writeVerifiedImage(source)
            val destination = root.resolve("installed")
            assertTrue(AvfImageInstaller.install(source, destination, activate = { promote ->
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
                AvfImageVerifier.verify(root) {
                    if (++checks == 3) throw java.util.concurrent.CancellationException("cancelled")
                }
            }
            assertTrue(AvfImageVerifier.verify(root) is ImageVerification.Ready)
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
                "system.sha256=${AvfImageVerifier.sha256(source.resolve("system.img"))}"))
            val destination = root.resolve("installed")
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                AvfImageInstaller.install(source, destination) {
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
            assertTrue(AvfImageInstaller.install(source, destination) is ImageInstallResult.Installed)
            assertTrue(AvfImageVerifier.verifyInstalled(destination) is ImageVerification.Ready)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun installationPresenceRequiresCompleteRegularFiles() {
        val root = Files.createTempDirectory("debian-presence-test")
        try {
            assertFalse(AvfImageVerifier.hasInstalledImage(root))
            writeVerifiedImage(root)
            assertTrue(AvfImageVerifier.hasInstalledImage(root))
            Files.delete(root.resolve("home.img"))
            assertFalse(AvfImageVerifier.hasInstalledImage(root))
            Files.write(root.resolve("home.img"), byteArrayOf())
            assertFalse(AvfImageVerifier.hasInstalledImage(root))
            Files.delete(root.resolve("home.img"))
            Files.createSymbolicLink(root.resolve("home.img"), root.resolve("system.img"))
            assertFalse(AvfImageVerifier.hasInstalledImage(root))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun installationPresenceDoesNotAuthorizeBootOrDependOnWritableDiskHashes() {
        val root = Files.createTempDirectory("debian-presence-test")
        try {
            writeVerifiedImage(root)
            Files.writeString(root.resolve("home.img"), "guest data")
            assertTrue(AvfImageVerifier.hasInstalledImage(root))
            assertTrue(AvfImageVerifier.verifyInstalled(root) is ImageVerification.Invalid)
            Files.writeString(root.resolve("system.img"), "tampered")
            assertTrue(AvfImageVerifier.hasInstalledImage(root))
            assertTrue(AvfImageVerifier.verifyInstalled(root) is ImageVerification.Invalid)
            Files.writeString(root.resolve("manifest.properties"), "formatVersion=999")
            assertFalse(AvfImageVerifier.hasInstalledImage(root))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun hashesEveryByteAcrossMultipleReadBuffers() {
        val artifact = Files.createTempFile("debian-hash-test", ".img")
        try {
            Files.write(artifact, ByteArray(600_017) { (it * 37).toByte() })
            assertEquals("02ad2bd651b7aeb8b36f9b66b9cb677eb01beae890a133960b7ce9de054eaf1c",
                AvfImageVerifier.sha256(artifact))
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
                kernel.sha256=${AvfImageVerifier.sha256(root.resolve("vmlinuz"))}
                initrd.sha256=${AvfImageVerifier.sha256(root.resolve("initrd.img"))}
                system.sha256=${AvfImageVerifier.sha256(root.resolve("system.img"))}
                home.sha256=${AvfImageVerifier.sha256(root.resolve("home.img"))}
                config.sha256=${AvfImageVerifier.sha256(root.resolve("config.img"))}
            """.trimIndent())

            assertTrue(AvfImageVerifier.verify(root) is ImageVerification.Ready)
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
                kernel.sha256=${AvfImageVerifier.sha256(root.resolve("vmlinuz"))}
                initrd.sha256=${AvfImageVerifier.sha256(root.resolve("initrd.img"))}
                system.sha256=${"0".repeat(64)}
                home.sha256=${AvfImageVerifier.sha256(root.resolve("home.img"))}
                config.sha256=${AvfImageVerifier.sha256(root.resolve("config.img"))}
            """.trimIndent())

            assertTrue(AvfImageVerifier.verify(root) is ImageVerification.Invalid)
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

            assertTrue(AvfImageInstaller.install(staged, destination) is ImageInstallResult.Installed)
            assertTrue(AvfImageVerifier.verify(destination) is ImageVerification.Ready)
            assertTrue(AvfImageInstaller.install(staged, destination) is ImageInstallResult.AlreadyInstalled)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun catalogReleaseRejectsNonHttpsOrigin() {
        assertThrows(IllegalArgumentException::class.java) {
            AvfImageRelease("debian-1", URI("http://example.invalid/manifest.properties"), "0".repeat(64), 1024)
        }
    }

    @Test
    fun writableDiskChangesAreAcceptedOnlyAfterVerifiedInstallation() {
        val root = Files.createTempDirectory("debian-persistence-test")
        try {
            val source = Files.createDirectory(root.resolve("source"))
            writeVerifiedImage(source)
            val installed = root.resolve("installed")
            assertTrue(AvfImageInstaller.install(source, installed) is ImageInstallResult.Installed)
            Files.writeString(installed.resolve("home.img"), "guest writes after first boot")
            assertTrue(AvfImageVerifier.verifyInstalled(installed) is ImageVerification.Ready)
            assertTrue(AvfImageVerifier.verify(installed) is ImageVerification.Invalid)
            Files.delete(installed.resolve(".installed-manifest-sha256"))
            assertTrue(AvfImageVerifier.verifyInstalled(installed) is ImageVerification.Invalid)
            Files.writeString(source.resolve("home.img"), "unverified disk")
            assertTrue(AvfImageVerifier.verifyInstalled(source) is ImageVerification.Invalid)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun installedImageStillRejectsChangedImmutableArtifacts() {
        val root = Files.createTempDirectory("debian-immutable-test")
        try {
            val source = Files.createDirectory(root.resolve("source"))
            writeVerifiedImage(source)
            val installed = root.resolve("installed")
            assertTrue(AvfImageInstaller.install(source, installed) is ImageInstallResult.Installed)
            Files.writeString(installed.resolve("system.img"), "tampered")
            assertTrue(AvfImageVerifier.verifyInstalled(installed) is ImageVerification.Invalid)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun distributionsAreVerifiedAndPinnedBeforeInstallationAndStartup() {
        for (distribution in AvfDistribution.entries) {
            val root = Files.createTempDirectory("avf-distribution")
            val source = Files.createDirectory(root.resolve("source"))
            writeVerifiedImage(source, distribution = distribution.id)
            Files.writeString(source.resolve("manifest.properties"), "\npackageInitializationVersion=1\n",
                java.nio.file.StandardOpenOption.APPEND)
            assertTrue(AvfImageVerifier.verify(source, distribution) is ImageVerification.Ready)
            val other = AvfDistribution.entries.first { it != distribution }
            assertFalse(AvfImageVerifier.hasInstalledImage(source, other))
            assertTrue(AvfImageVerifier.verify(source, other) is ImageVerification.Invalid)
            val destination = root.resolve("installed")
            assertTrue(AvfImageInstaller.install(source, destination, initializePackageIndexes = true,
                expectedDistribution = other) is ImageInstallResult.Failed)
            assertFalse(Files.exists(destination))
            assertTrue(AvfImageInstaller.install(source, destination, initializePackageIndexes = true,
                expectedDistribution = distribution) is ImageInstallResult.Installed)
            assertTrue(AvfPackageInitialization.isRequired(destination))
            Files.writeString(destination.resolve("home.img"), "user data")
            assertTrue(AvfImageInstaller.verifyForStartup(destination, distribution) is ImageVerification.Ready)
            assertTrue(AvfImageInstaller.verifyForStartup(destination, other) is ImageVerification.Invalid)
        }
    }

    @Test fun legacyDebianInitializationReceiptRetainsItsCompletionAndPendingRetry() {
        val root = Files.createTempDirectory("avf-legacy-receipt")
        writeVerifiedImage(root, aptInitializationVersion = "1")
        AvfPackageInitialization.markPending(root)
        Files.move(root.resolve(AvfPackageInitialization.FILE_NAME), root.resolve(".apt-index-initialization"))
        assertTrue(AvfPackageInitialization.isRequired(root))
        assertEquals(null, AvfPackageInitialization.completedAt(root))
        AvfPackageInitialization.markComplete(root, 123)
        assertEquals(123L, AvfPackageInitialization.completedAt(root))
        assertFalse(Files.exists(root.resolve(AvfPackageInitialization.FILE_NAME)))
    }

    @Test fun unknownDistributionAndArchAptCapabilityAreRejected() {
        for (distribution in listOf("unknown", "archlinux")) {
            val root = Files.createTempDirectory("avf-invalid-distribution")
            writeVerifiedImage(root, aptInitializationVersion = "1", distribution = distribution)
            assertFalse(AvfImageVerifier.hasInstalledImage(root))
            assertTrue(AvfImageVerifier.verify(root) is ImageVerification.Invalid)
        }
    }

    private fun writeVerifiedImage(root: Path, aptInitializationVersion: String? = null, distribution: String = "debian") {
        Files.writeString(root.resolve("system.img"), "system")
        Files.writeString(root.resolve("vmlinuz"), "kernel")
        Files.writeString(root.resolve("initrd.img"), "initrd")
        Files.writeString(root.resolve("home.img"), "home")
        Files.writeString(root.resolve("config.img"), "config")
        Files.writeString(root.resolve("manifest.properties"), """
            formatVersion=1
            distribution=$distribution
            architecture=arm64
            kernel.sha256=${AvfImageVerifier.sha256(root.resolve("vmlinuz"))}
            initrd.sha256=${AvfImageVerifier.sha256(root.resolve("initrd.img"))}
            system.sha256=${AvfImageVerifier.sha256(root.resolve("system.img"))}
            home.sha256=${AvfImageVerifier.sha256(root.resolve("home.img"))}
            config.sha256=${AvfImageVerifier.sha256(root.resolve("config.img"))}
        """.trimIndent())
        if (aptInitializationVersion != null) {
            Files.writeString(root.resolve("manifest.properties"), "\naptInitializationVersion=$aptInitializationVersion\n",
                java.nio.file.StandardOpenOption.APPEND)
        }
    }
}
