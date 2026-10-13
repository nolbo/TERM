package dev.terminal.image

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

sealed interface ImageInstallResult {
    data class Installed(val destination: Path) : ImageInstallResult
    data class AlreadyInstalled(val verification: ImageVerification) : ImageInstallResult
    data class Failed(val reason: String) : ImageInstallResult
}

/**
 * Promotes a complete, verified staging directory to a VM image directory.
 * It deliberately refuses replacement: upgrades and rollback require a later
 * versioned-image design rather than silently deleting user data.
 */
object AvfImageInstaller {
    /** A fresh path owned by an installation attempt, on the destination filesystem. */
    fun newStagingPath(destination: Path): Path =
        requireNotNull(destination.parent) { "Image destination has no parent directory" }
            .resolve(".${destination.fileName}.install-${UUID.randomUUID()}")

    /** Adopt previously fully verified installs without resetting their writable state. */
    fun verifyForStartup(root: Path, expectedDistribution: AvfDistribution? = null): ImageVerification =
        AvfImageVerifier.verifyInstalled(root, expectedDistribution = expectedDistribution).also {
        if (it is ImageVerification.Ready) AvfImageVerifier.recordInstallation(root)
    }

    /**
     * Activation may fence deletion under a metadata lock; copying and hashing stay outside it.
     * An owned sibling staging directory can be consumed without another full image copy.
     */
    fun install(stagingDirectory: Path, destination: Path,
        activate: (() -> Unit) -> Unit = { it() }, initializePackageIndexes: Boolean = false,
        expectedDistribution: AvfDistribution? = null,
        consumeStagingDirectory: Boolean = false,
        checkCancellation: () -> Unit = {}): ImageInstallResult {
        checkCancellation()
        if (Files.exists(destination)) {
            return ImageInstallResult.AlreadyInstalled(AvfImageVerifier.verifyInstalled(destination, expectedDistribution, checkCancellation))
        }
        val parent = destination.parent ?: return ImageInstallResult.Failed("Image destination has no parent directory")
        if (!Files.isDirectory(parent)) return ImageInstallResult.Failed("Image destination parent is missing")
        if (consumeStagingDirectory && (
            stagingDirectory.toAbsolutePath().normalize().parent != parent.toAbsolutePath().normalize() ||
            !stagingDirectory.fileName.toString().startsWith(".${destination.fileName}.install-") ||
            !Files.isDirectory(stagingDirectory, java.nio.file.LinkOption.NOFOLLOW_LINKS))) {
            return ImageInstallResult.Failed("Only an owned sibling staging directory can be consumed")
        }
        val stagingVerification = AvfImageVerifier.verify(stagingDirectory, expectedDistribution, checkCancellation)
        if (stagingVerification !is ImageVerification.Ready) {
            return ImageInstallResult.Failed((stagingVerification as ImageVerification.Invalid).reason)
        }

        val temporary = if (consumeStagingDirectory) stagingDirectory else newStagingPath(destination)
        return try {
            if (!consumeStagingDirectory) {
                Files.createDirectory(temporary)
                for (name in listOf(AvfImageLayout.MANIFEST_FILE, AvfImageLayout.KERNEL_IMAGE,
                    AvfImageLayout.INITRD_IMAGE, AvfImageLayout.SYSTEM_IMAGE,
                    AvfImageLayout.HOME_IMAGE, AvfImageLayout.CONFIG_IMAGE)) {
                    copyArtifact(stagingDirectory, temporary, name, checkCancellation)
                }
            }
            val prepared = if (consumeStagingDirectory) stagingVerification else
                AvfImageVerifier.verify(temporary, expectedDistribution, checkCancellation)
            when (val verification = prepared) {
                is ImageVerification.Invalid -> ImageInstallResult.Failed(verification.reason)
                is ImageVerification.Ready -> {
                    AvfImageVerifier.recordInstallation(temporary)
                    if (initializePackageIndexes && verification.manifest.initializesPackageIndexes) {
                        AvfPackageInitialization.markPending(temporary)
                    }
                    activate {
                        checkCancellation()
                        check(!Files.exists(destination)) { "Image destination already exists; refusing replacement" }
                        moveIntoPlace(temporary, destination)
                    }
                    ImageInstallResult.Installed(destination)
                }
            }
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (error: Exception) {
            ImageInstallResult.Failed("Image installation failed: ${error.message}")
        } finally {
            if (Files.exists(temporary)) deleteTree(temporary)
        }
    }

    private fun copyArtifact(sourceRoot: Path, targetRoot: Path, name: String, checkCancellation: () -> Unit) {
        val source = sourceRoot.resolve(name)
        require(Files.isRegularFile(source, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "Staged artifact is not a regular file: $name" }
        val target = targetRoot.resolve(name)
        Files.newInputStream(source).use { input ->
            AvfSparseFiles.copy(input, target, checkCancellation = checkCancellation)
        }
        Files.setLastModifiedTime(target, Files.getLastModifiedTime(source))
    }

    private fun moveIntoPlace(temporary: Path, destination: Path) {
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            // Both paths are siblings; a non-atomic fallback still avoids a
            // cross-filesystem copy and is used only where the filesystem lacks rename atomicity.
            Files.move(temporary, destination)
        }
    }

    private fun deleteTree(root: Path) {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
