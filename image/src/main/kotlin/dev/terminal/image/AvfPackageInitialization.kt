package dev.terminal.image

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Host-side initialization receipt, separate from the verified image installation receipt. */
object AvfPackageInitialization {
    const val FILE_NAME = ".package-index-initialization"
    private const val LEGACY_FILE_NAME = ".apt-index-initialization"

    private fun receipt(root: Path): Path = root.resolve(FILE_NAME).takeIf {
        Files.exists(it, LinkOption.NOFOLLOW_LINKS)
    } ?: root.resolve(LEGACY_FILE_NAME).takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) } ?: root.resolve(FILE_NAME)

    fun isRequired(root: Path): Boolean = Files.exists(receipt(root), LinkOption.NOFOLLOW_LINKS)

    fun completedAt(root: Path): Long? = runCatching {
        val file = receipt(root)
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) <= 256)
        val properties = Properties().apply { Files.newInputStream(file).use(::load) }
        require(properties.getProperty("manifestSha256") == AvfImageVerifier.sha256(root.resolve(AvfImageLayout.MANIFEST_FILE)))
        properties.getProperty("completedAtEpochSeconds")?.toLongOrNull()?.takeIf { it > 0 }
    }.getOrNull()

    fun markPending(root: Path) = write(root, 0)

    fun markComplete(root: Path, epochSeconds: Long) {
        require(epochSeconds > 0)
        write(root, epochSeconds)
    }

    private fun write(root: Path, epochSeconds: Long) {
        val contents = "manifestSha256=${AvfImageVerifier.sha256(root.resolve(AvfImageLayout.MANIFEST_FILE))}\n" +
            "completedAtEpochSeconds=$epochSeconds\n"
        val temporary = Files.createTempFile(root, ".package-index-", ".tmp")
        try {
            Files.write(temporary, contents.toByteArray(Charsets.US_ASCII))
            Files.move(temporary, receipt(root), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
