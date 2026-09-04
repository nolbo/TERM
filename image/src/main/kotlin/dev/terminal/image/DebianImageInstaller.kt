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
object DebianImageInstaller {
    fun install(stagingDirectory: Path, destination: Path): ImageInstallResult {
        if (Files.exists(destination)) {
            return ImageInstallResult.AlreadyInstalled(DebianImageVerifier.verify(destination))
        }
        val parent = destination.parent ?: return ImageInstallResult.Failed("Image destination has no parent directory")
        if (!Files.isDirectory(parent)) return ImageInstallResult.Failed("Image destination parent is missing")
        val stagingVerification = DebianImageVerifier.verify(stagingDirectory)
        if (stagingVerification !is ImageVerification.Ready) {
            return ImageInstallResult.Failed((stagingVerification as ImageVerification.Invalid).reason)
        }

        val temporary = parent.resolve(".${destination.fileName}.install-${UUID.randomUUID()}")
        return try {
            Files.createDirectory(temporary)
            copyArtifact(stagingDirectory, temporary, DebianImageLayout.MANIFEST_FILE)
            copyArtifact(stagingDirectory, temporary, DebianImageLayout.KERNEL_IMAGE)
            copyArtifact(stagingDirectory, temporary, DebianImageLayout.INITRD_IMAGE)
            copyArtifact(stagingDirectory, temporary, DebianImageLayout.SYSTEM_IMAGE)
            copyArtifact(stagingDirectory, temporary, DebianImageLayout.HOME_IMAGE)
            copyArtifact(stagingDirectory, temporary, DebianImageLayout.CONFIG_IMAGE)
            when (val verification = DebianImageVerifier.verify(temporary)) {
                is ImageVerification.Invalid -> ImageInstallResult.Failed(verification.reason)
                is ImageVerification.Ready -> {
                    moveIntoPlace(temporary, destination)
                    ImageInstallResult.Installed(destination)
                }
            }
        } catch (error: Exception) {
            ImageInstallResult.Failed("Image installation failed: ${error.message}")
        } finally {
            if (Files.exists(temporary)) deleteTree(temporary)
        }
    }

    private fun copyArtifact(sourceRoot: Path, targetRoot: Path, name: String) {
        val source = sourceRoot.resolve(name)
        require(Files.isRegularFile(source, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "Staged artifact is not a regular file: $name" }
        Files.copy(source, targetRoot.resolve(name), StandardCopyOption.COPY_ATTRIBUTES)
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
