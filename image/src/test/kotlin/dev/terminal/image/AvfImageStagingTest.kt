package dev.terminal.image

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AvfImageStagingTest {
    @Test fun consumesTheVerifiedFilesWithoutCopyingForEveryDistribution() {
        val root = Files.createTempDirectory("avf-staging-distributions")
        try {
            for (distribution in AvfDistribution.entries) {
                val destination = root.resolve(distribution.id)
                val staged = Files.createDirectory(AvfImageInstaller.newStagingPath(destination))
                writeImage(staged, distribution)
                val homeIdentity = Files.readAttributes(staged.resolve("home.img"), BasicFileAttributes::class.java).fileKey()
                var promotions = 0
                assertTrue(AvfImageInstaller.install(staged, destination, expectedDistribution = distribution,
                    initializePackageIndexes = true, consumeStagingDirectory = true, activate = { promote ->
                        assertFalse(Files.exists(destination))
                        assertTrue(AvfPackageInitialization.isRequired(staged))
                        Files.list(root).use { entries ->
                            assertEquals(1L, entries.filter { it.fileName.toString().startsWith(".${distribution.id}.install-") }.count())
                        }
                        promotions++
                        promote()
                    }) is ImageInstallResult.Installed)
                assertEquals(1, promotions)
                assertFalse(Files.exists(staged))
                assertEquals(homeIdentity, Files.readAttributes(destination.resolve("home.img"), BasicFileAttributes::class.java).fileKey())
                assertTrue(AvfImageVerifier.verifyInstalled(destination, distribution) is ImageVerification.Ready)
                Files.writeString(destination.resolve("home.img"), "user packages and files")
                assertTrue(AvfImageVerifier.verifyInstalled(destination, distribution) is ImageVerification.Ready)
                val retry = Files.createDirectory(AvfImageInstaller.newStagingPath(destination))
                writeImage(retry, distribution)
                assertTrue(AvfImageInstaller.install(retry, destination, consumeStagingDirectory = true) is ImageInstallResult.AlreadyInstalled)
                assertEquals("user packages and files", Files.readString(destination.resolve("home.img")))
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun refusesToConsumeAnExternalDirectoryOrSymlink() {
        val root = Files.createTempDirectory("avf-staging-boundary")
        try {
            val destination = root.resolve("installed")
            val external = Files.createDirectory(root.resolve("external")).also { writeImage(it) }
            val otherParent = Files.createDirectory(root.resolve("elsewhere"))
            val nonSibling = Files.createDirectory(otherParent.resolve(AvfImageInstaller.newStagingPath(destination).fileName))
                .also { writeImage(it) }
            val link = Files.createSymbolicLink(AvfImageInstaller.newStagingPath(destination), external)
            for (source in listOf(external, nonSibling, link)) {
                assertTrue(AvfImageInstaller.install(source, destination, consumeStagingDirectory = true) is ImageInstallResult.Failed)
                assertFalse(Files.exists(destination))
                assertTrue(Files.exists(source))
            }
            assertTrue(AvfImageVerifier.verify(external) is ImageVerification.Ready)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun invalidContentAndWrongDistributionCannotBeActivated() {
        val root = Files.createTempDirectory("avf-staging-invalid")
        try {
            val destination = root.resolve("installed")
            val staged = Files.createDirectory(AvfImageInstaller.newStagingPath(destination)).also { writeImage(it) }
            assertTrue(AvfImageInstaller.install(staged, destination, consumeStagingDirectory = true,
                expectedDistribution = AvfDistribution.UBUNTU) is ImageInstallResult.Failed)
            Files.writeString(staged.resolve("system.img"), "corrupted")
            assertTrue(AvfImageInstaller.install(staged, destination, consumeStagingDirectory = true) is ImageInstallResult.Failed)
            assertFalse(Files.exists(destination))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun cancellationAtPromotionRemovesOnlyTheOwnedStagingDirectory() {
        val root = Files.createTempDirectory("avf-staging-cancel")
        try {
            val destination = root.resolve("installed")
            val staged = Files.createDirectory(AvfImageInstaller.newStagingPath(destination)).also { writeImage(it) }
            val sentinel = Files.writeString(root.resolve("keep.txt"), "keep")
            var cancelled = false
            assertThrows(CancellationException::class.java) {
                AvfImageInstaller.install(staged, destination, consumeStagingDirectory = true,
                    activate = { promote -> cancelled = true; promote() }, checkCancellation = {
                        if (cancelled) throw CancellationException("cancelled")
                    })
            }
            assertFalse(Files.exists(staged))
            assertFalse(Files.exists(destination))
            assertEquals("keep", Files.readString(sentinel))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun destinationCreatedDuringActivationIsPreserved() {
        val root = Files.createTempDirectory("avf-staging-race")
        try {
            val destination = root.resolve("installed")
            val staged = Files.createDirectory(AvfImageInstaller.newStagingPath(destination)).also { writeImage(it) }
            assertTrue(AvfImageInstaller.install(staged, destination, consumeStagingDirectory = true,
                activate = { promote ->
                    Files.createDirectory(destination)
                    Files.writeString(destination.resolve("keep.txt"), "existing data")
                    promote()
                }) is ImageInstallResult.Failed)
            assertEquals("existing data", Files.readString(destination.resolve("keep.txt")))
            assertFalse(Files.exists(staged))
        } finally { root.toFile().deleteRecursively() }
    }

    private fun writeImage(root: Path, distribution: AvfDistribution = AvfDistribution.DEBIAN) {
        val artifacts = listOf("kernel" to "vmlinuz", "initrd" to "initrd.img", "system" to "system.img",
            "home" to "home.img", "config" to "config.img")
        artifacts.forEach { (name, file) -> Files.writeString(root.resolve(file), name) }
        Files.writeString(root.resolve("manifest.properties"),
            "formatVersion=1\ndistribution=${distribution.id}\narchitecture=arm64\npackageInitializationVersion=1\n" +
                artifacts.joinToString("\n") { (name, file) -> "$name.sha256=${AvfImageVerifier.sha256(root.resolve(file))}" })
    }
}
