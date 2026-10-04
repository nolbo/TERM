package dev.terminal.image

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties

/** Immutable contract for the first supported guest image: Debian ARM64. */
data class DebianImageManifest(
    val formatVersion: Int,
    val distribution: String,
    val architecture: String,
    val kernelSha256: String,
    val initrdSha256: String,
    val systemSha256: String,
    val homeSha256: String,
    val configSha256: String,
)

data class DebianImageLayout(val root: Path) {
    val manifest: Path = root.resolve(MANIFEST_FILE)
    val kernelImage: Path = root.resolve(KERNEL_IMAGE)
    val initrdImage: Path = root.resolve(INITRD_IMAGE)
    val systemImage: Path = root.resolve(SYSTEM_IMAGE)
    val homeImage: Path = root.resolve(HOME_IMAGE)
    val configImage: Path = root.resolve(CONFIG_IMAGE)

    fun isContained(path: Path): Boolean = path.normalize().startsWith(root.normalize())

    companion object {
        const val MANIFEST_FILE = "manifest.properties"
        const val KERNEL_IMAGE = "vmlinuz"
        const val INITRD_IMAGE = "initrd.img"
        const val SYSTEM_IMAGE = "system.img"
        const val HOME_IMAGE = "home.img"
        const val CONFIG_IMAGE = "config.img"
    }
}

sealed interface ImageVerification {
    data class Ready(val manifest: DebianImageManifest) : ImageVerification
    data class Invalid(val reason: String) : ImageVerification
}

object DebianImageVerifier {
    private const val EXPECTED_FORMAT_VERSION = 1
    private const val EXPECTED_DISTRIBUTION = "debian"
    private const val EXPECTED_ARCHITECTURE = "arm64"
    private val SHA256 = Regex("[0-9a-f]{64}")
    private const val HASH_BUFFER_BYTES = 256 * 1024

    private const val INSTALL_RECEIPT = ".installed-manifest-sha256"

    /** Installation presence for UI only; boot still requires [verifyInstalled]. No disk is hashed. */
    fun hasInstalledImage(root: Path): Boolean = runCatching {
        val layout = DebianImageLayout(root)
        val files = listOf(layout.manifest, layout.kernelImage, layout.initrdImage,
            layout.systemImage, layout.homeImage, layout.configImage)
        if (!Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS) || files.any {
                !Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.size(it) <= 0
            } || Files.size(layout.manifest) > 64 * 1024) return@runCatching false
        val manifest = parse(layout.manifest)
        manifest.formatVersion == EXPECTED_FORMAT_VERSION && manifest.distribution == EXPECTED_DISTRIBUTION &&
            manifest.architecture == EXPECTED_ARCHITECTURE && listOf(manifest.kernelSha256, manifest.initrdSha256,
                manifest.systemSha256, manifest.homeSha256, manifest.configSha256).all(SHA256::matches)
    }.getOrDefault(false)

    /** A writable VM disk changes after first boot; immutable artifacts remain pinned. */
    fun verifyInstalled(root: Path): ImageVerification {
        val receipt = root.resolve(INSTALL_RECEIPT)
        val manifest = root.resolve(DebianImageLayout.MANIFEST_FILE)
        val hasReceipt = runCatching {
            Files.isRegularFile(receipt, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                Files.size(receipt) <= 65 && String(Files.readAllBytes(receipt), Charsets.US_ASCII).trim() == sha256(manifest)
        }.getOrDefault(false)
        return verify(root, verifyHome = !hasReceipt)
    }

    internal fun recordInstallation(root: Path) {
        Files.write(root.resolve(INSTALL_RECEIPT), (sha256(root.resolve(DebianImageLayout.MANIFEST_FILE)) + "\n").toByteArray(Charsets.US_ASCII))
    }

    fun verify(root: Path): ImageVerification = verify(root, verifyHome = true)

    private fun verify(root: Path, verifyHome: Boolean): ImageVerification {
        val layout = DebianImageLayout(root)
        if (!Files.isDirectory(root)) return ImageVerification.Invalid("Debian image directory is missing")
        val required = listOf(layout.manifest, layout.kernelImage, layout.initrdImage, layout.systemImage, layout.homeImage, layout.configImage)
        if (required.any { !layout.isContained(it) || !Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }) {
            return ImageVerification.Invalid("Debian image is incomplete")
        }
        if (required.drop(1).any { runCatching { Files.size(it) <= 0 }.getOrDefault(true) }) {
            return ImageVerification.Invalid("Debian image contains an empty disk artifact")
        }
        val manifest = runCatching { parse(layout.manifest) }
            .getOrElse { return ImageVerification.Invalid("Invalid Debian image manifest: ${it.message}") }
        if (manifest.formatVersion != EXPECTED_FORMAT_VERSION ||
            manifest.distribution != EXPECTED_DISTRIBUTION ||
            manifest.architecture != EXPECTED_ARCHITECTURE
        ) return ImageVerification.Invalid("Unsupported image: expected Debian ARM64 format v1")
        val hashes = listOf(
            layout.kernelImage to manifest.kernelSha256,
            layout.initrdImage to manifest.initrdSha256,
            layout.systemImage to manifest.systemSha256,
            layout.homeImage to manifest.homeSha256,
            layout.configImage to manifest.configSha256,
        )
        for ((file, expected) in hashes) {
            if (file == layout.homeImage && !verifyHome) continue
            if (!SHA256.matches(expected) || sha256(file) != expected) {
                return ImageVerification.Invalid("Checksum verification failed for ${file.fileName}")
            }
        }
        return ImageVerification.Ready(manifest)
    }

    fun parse(manifestPath: Path): DebianImageManifest = Properties().run {
        Files.newInputStream(manifestPath).use(::load)
        DebianImageManifest(
            formatVersion = getProperty("formatVersion")?.toIntOrNull() ?: error("formatVersion missing"),
            distribution = getProperty("distribution") ?: error("distribution missing"),
            architecture = getProperty("architecture") ?: error("architecture missing"),
            kernelSha256 = getProperty("kernel.sha256") ?: error("kernel.sha256 missing"),
            initrdSha256 = getProperty("initrd.sha256") ?: error("initrd.sha256 missing"),
            systemSha256 = getProperty("system.sha256") ?: error("system.sha256 missing"),
            homeSha256 = getProperty("home.sha256") ?: error("home.sha256 missing"),
            configSha256 = getProperty("config.sha256") ?: error("config.sha256 missing"),
        )
    }

    fun sha256(path: Path): String = Files.newInputStream(path).use { input -> sha256(input) }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(HASH_BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
