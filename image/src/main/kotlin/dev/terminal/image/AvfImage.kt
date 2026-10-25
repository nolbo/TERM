package dev.terminal.image

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties

/** Shared immutable AVF disk and guest-capability contract. */
data class AvfImageManifest(
    val formatVersion: Int,
    val distribution: String,
    val architecture: String,
    val kernelSha256: String,
    val initrdSha256: String,
    val systemSha256: String,
    val homeSha256: String,
    val configSha256: String,
    val aptInitializationVersion: Int = 0,
    val virtiofsVersion: Int = 0,
    val androidStorageVersion: Int = 0,
    val packageInitializationVersion: Int = 0,
    val shareBrokerVersion: Int = 0,
    val maintenanceVersion: Int = 0,
    val sessionResumeVersion: Int = 0,
) {
    /** v1: overlay upper/work on home.img; v2: complete writable ext4 root on home.img. */
    val usesExt4Root: Boolean get() = formatVersion == 2
    val rootDevice: String get() = if (usesExt4Root) "/dev/vdb" else "/dev/vda"
    val rootMountMode: String get() = if (usesExt4Root) "rw" else "ro"
    val guestDistribution: AvfDistribution get() = AvfDistribution.fromId(distribution)
    val initializesPackageIndexes: Boolean get() = packageInitializationVersion == 1 || aptInitializationVersion == 1
}

data class AvfImageLayout(val root: Path) {
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
    data class Ready(val manifest: AvfImageManifest) : ImageVerification
    data class Invalid(val reason: String) : ImageVerification
}

object AvfImageVerifier {
    private val SUPPORTED_FORMAT_VERSIONS = 1..2
    private const val EXPECTED_ARCHITECTURE = "arm64"
    private val SHA256 = Regex("[0-9a-f]{64}")
    private const val HASH_BUFFER_BYTES = 256 * 1024

    private const val INSTALL_RECEIPT = ".installed-manifest-sha256"

    /** Installation presence for UI only; boot still requires [verifyInstalled]. No disk is hashed. */
    fun hasInstalledImage(root: Path, expectedDistribution: AvfDistribution? = null): Boolean = runCatching {
        val layout = AvfImageLayout(root)
        val files = listOf(layout.manifest, layout.kernelImage, layout.initrdImage,
            layout.systemImage, layout.homeImage, layout.configImage)
        if (!Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS) || files.any {
                !Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.size(it) <= 0
            } || Files.size(layout.manifest) > 64 * 1024) return@runCatching false
        val manifest = parse(layout.manifest)
        manifest.formatVersion in SUPPORTED_FORMAT_VERSIONS &&
            (expectedDistribution == null || manifest.guestDistribution == expectedDistribution) &&
            manifest.architecture == EXPECTED_ARCHITECTURE && listOf(manifest.kernelSha256, manifest.initrdSha256,
                manifest.systemSha256, manifest.homeSha256, manifest.configSha256).all(SHA256::matches)
    }.getOrDefault(false)

    /** A writable VM disk changes after first boot; immutable artifacts remain pinned. */
    fun verifyInstalled(root: Path, expectedDistribution: AvfDistribution? = null, checkCancellation: () -> Unit = {}): ImageVerification {
        checkCancellation()
        if (AvfImageTransitions.interrupted(root)) return ImageVerification.Invalid("AVF 이미지 전환 복구가 필요합니다.")
        val receipt = root.resolve(INSTALL_RECEIPT)
        val manifest = root.resolve(AvfImageLayout.MANIFEST_FILE)
        if (!Files.isRegularFile(manifest, java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
            Files.size(manifest) !in 1..64 * 1024L) return ImageVerification.Invalid("Invalid AVF image manifest size")
        val hasReceipt = runCatching {
            Files.isRegularFile(receipt, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                Files.size(receipt) <= 65 && String(Files.readAllBytes(receipt), Charsets.US_ASCII).trim() == sha256(manifest)
        }.getOrDefault(false)
        return verify(root, verifyHome = !hasReceipt, checkCancellation, expectedDistribution)
    }

    internal fun recordInstallation(root: Path) {
        Files.write(root.resolve(INSTALL_RECEIPT), (sha256(root.resolve(AvfImageLayout.MANIFEST_FILE)) + "\n").toByteArray(Charsets.US_ASCII))
    }

    fun verify(root: Path, expectedDistribution: AvfDistribution? = null, checkCancellation: () -> Unit = {}): ImageVerification =
        verify(root, verifyHome = true, checkCancellation, expectedDistribution)

    private fun verify(root: Path, verifyHome: Boolean, checkCancellation: () -> Unit, expectedDistribution: AvfDistribution?): ImageVerification {
        checkCancellation()
        val layout = AvfImageLayout(root)
        if (!Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return ImageVerification.Invalid("AVF image directory is missing")
        val required = listOf(layout.manifest, layout.kernelImage, layout.initrdImage, layout.systemImage, layout.homeImage, layout.configImage)
        if (required.any { !layout.isContained(it) || !Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }) {
            return ImageVerification.Invalid("AVF image is incomplete")
        }
        if (required.drop(1).any { runCatching { Files.size(it) <= 0 }.getOrDefault(true) }) {
            return ImageVerification.Invalid("AVF image contains an empty disk artifact")
        }
        val manifest = runCatching { parse(layout.manifest) }
            .getOrElse { return ImageVerification.Invalid("Invalid AVF image manifest: ${it.message}") }
        if (manifest.formatVersion !in SUPPORTED_FORMAT_VERSIONS ||
            (expectedDistribution != null && manifest.guestDistribution != expectedDistribution) ||
            manifest.architecture != EXPECTED_ARCHITECTURE
        ) return ImageVerification.Invalid("Unsupported image: expected ${expectedDistribution?.displayName ?: "AVF Linux"} ARM64 format v1 or v2")
        val hashes = listOf(
            layout.kernelImage to manifest.kernelSha256,
            layout.initrdImage to manifest.initrdSha256,
            layout.systemImage to manifest.systemSha256,
            layout.homeImage to manifest.homeSha256,
            layout.configImage to manifest.configSha256,
        )
        val hashBuffer = ByteArray(HASH_BUFFER_BYTES)
        for ((file, expected) in hashes) {
            if (file == layout.homeImage && !verifyHome) continue
            if (!SHA256.matches(expected) || Files.newInputStream(file).use { sha256(it, checkCancellation, hashBuffer) } != expected) {
                return ImageVerification.Invalid("Checksum verification failed for ${file.fileName}")
            }
        }
        return ImageVerification.Ready(manifest)
    }

    fun parse(manifestPath: Path): AvfImageManifest = Properties().run {
        require(Files.size(manifestPath) in 1..64 * 1024L) { "Invalid AVF image manifest size" }
        Files.newInputStream(manifestPath).use(::load)
        val formatVersion = getProperty("formatVersion")?.toIntOrNull() ?: error("formatVersion missing")
        require(formatVersion in SUPPORTED_FORMAT_VERSIONS) { "Unsupported AVF image format" }
        val distribution = AvfDistribution.fromId(getProperty("distribution") ?: error("distribution missing"))
        require(getProperty("aptInitializationVersion") == null || distribution.packageManager == "apt") {
            "APT initialization requires an APT distribution"
        }
        AvfImageManifest(
            formatVersion = formatVersion,
            distribution = getProperty("distribution") ?: error("distribution missing"),
            architecture = getProperty("architecture") ?: error("architecture missing"),
            kernelSha256 = getProperty("kernel.sha256") ?: error("kernel.sha256 missing"),
            initrdSha256 = getProperty("initrd.sha256") ?: error("initrd.sha256 missing"),
            systemSha256 = getProperty("system.sha256") ?: error("system.sha256 missing"),
            homeSha256 = getProperty("home.sha256") ?: error("home.sha256 missing"),
            configSha256 = getProperty("config.sha256") ?: error("config.sha256 missing"),
            aptInitializationVersion = getProperty("aptInitializationVersion")?.let {
                require(it == "1") { "Unsupported APT initialization version" }
                1
            } ?: 0,
            virtiofsVersion = getProperty("virtiofsVersion")?.let {
                require(it == "1") { "Unsupported virtiofs version" }
                1
            } ?: 0,
            androidStorageVersion = getProperty("androidStorageVersion")?.let {
                require(it == "1" && getProperty("virtiofsVersion") == "1") { "Unsupported Android storage version" }
                1
            } ?: 0,
            packageInitializationVersion = getProperty("packageInitializationVersion")?.let {
                require(it == "1") { "Unsupported package initialization version" }
                1
            } ?: 0,
            shareBrokerVersion = getProperty("shareBrokerVersion")?.let {
                require(it in setOf("1", "2")) { "Unsupported private share broker version" }
                it.toInt()
            } ?: 0,
            maintenanceVersion = getProperty("maintenanceVersion")?.let {
                require(it == "1") { "Unsupported guest maintenance version" }
                1
            } ?: 0,
            sessionResumeVersion = getProperty("sessionResumeVersion")?.let {
                require(it == "1") { "Unsupported session resume version" }
                1
            } ?: 0,
        )
    }

    fun sha256(path: Path, checkCancellation: () -> Unit = {}): String =
        Files.newInputStream(path).use { input -> sha256(input, checkCancellation) }

    private fun sha256(input: InputStream, checkCancellation: () -> Unit,
        buffer: ByteArray = ByteArray(HASH_BUFFER_BYTES)): String {
        val digest = MessageDigest.getInstance("SHA-256")
        while (true) {
            checkCancellation()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        val alphabet = "0123456789abcdef"
        return digest.digest().let { hash -> CharArray(hash.size * 2).apply {
            hash.forEachIndexed { index, byte ->
                val value = byte.toInt() and 255
                this[index * 2] = alphabet[value ushr 4]
                this[index * 2 + 1] = alphabet[value and 15]
            }
        }.concatToString() }
    }
}
