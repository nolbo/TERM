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
    /** Adopt previously fully verified installs without resetting their writable state. */
    fun verifyForStartup(root: Path): ImageVerification = DebianImageVerifier.verifyInstalled(root).also {
        if (it is ImageVerification.Ready) DebianImageVerifier.recordInstallation(root)
    }

    /** Activation may fence deletion under a metadata lock; copying and hashing stay outside it. */
    fun install(stagingDirectory: Path, destination: Path,
        activate: (() -> Unit) -> Unit = { it() }, initializePackageIndexes: Boolean = false,
        checkCancellation: () -> Unit = {}): ImageInstallResult {
        checkCancellation()
        if (Files.exists(destination)) {
            return ImageInstallResult.AlreadyInstalled(DebianImageVerifier.verifyInstalled(destination, checkCancellation))
        }
        val parent = destination.parent ?: return ImageInstallResult.Failed("Image destination has no parent directory")
        if (!Files.isDirectory(parent)) return ImageInstallResult.Failed("Image destination parent is missing")
        val stagingVerification = DebianImageVerifier.verify(stagingDirectory, checkCancellation)
        if (stagingVerification !is ImageVerification.Ready) {
            return ImageInstallResult.Failed((stagingVerification as ImageVerification.Invalid).reason)
        }

        val temporary = parent.resolve(".${destination.fileName}.install-${UUID.randomUUID()}")
        return try {
            Files.createDirectory(temporary)
            for (name in listOf(DebianImageLayout.MANIFEST_FILE, DebianImageLayout.KERNEL_IMAGE,
                DebianImageLayout.INITRD_IMAGE, DebianImageLayout.SYSTEM_IMAGE,
                DebianImageLayout.HOME_IMAGE, DebianImageLayout.CONFIG_IMAGE)) {
                copyArtifact(stagingDirectory, temporary, name, checkCancellation)
            }
            when (val verification = DebianImageVerifier.verify(temporary, checkCancellation)) {
                is ImageVerification.Invalid -> ImageInstallResult.Failed(verification.reason)
                is ImageVerification.Ready -> {
                    DebianImageVerifier.recordInstallation(temporary)
                    if (initializePackageIndexes && verification.manifest.aptInitializationVersion == 1) {
                        DebianAptInitialization.markPending(temporary)
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
            Files.newOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancellation()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
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
