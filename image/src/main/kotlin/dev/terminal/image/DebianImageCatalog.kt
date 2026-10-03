package dev.terminal.image

import java.net.URI
import java.io.InputStream
import java.util.Properties

/** A release must come from an app/OEM-provided catalog, never arbitrary UI input. */
data class DebianImageRelease(
    val id: String,
    val manifestUri: URI,
    val manifestSha256: String,
    val maxArtifactBytes: Long,
    val gzipArtifacts: Boolean = false,
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "Invalid image release ID" }
        require(manifestUri.scheme == "https" && !manifestUri.host.isNullOrBlank() &&
            manifestUri.userInfo == null && manifestUri.query == null && manifestUri.fragment == null &&
            manifestUri.path.endsWith("/manifest.properties")) { "Invalid HTTPS image manifest URI" }
        require(manifestSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid manifest checksum" }
        require(maxArtifactBytes in 1..MAX_ARTIFACT_BYTES) { "Invalid artifact size limit" }
    }

    companion object { const val MAX_ARTIFACT_BYTES = 32L * 1024 * 1024 * 1024 }
}

interface DebianImageCatalog {
    fun find(releaseId: String): DebianImageRelease?
}

/**
 * The image factory generates this resource; Gradle embeds it into the signed
 * APK with -PavfImageCatalog=... . There is no runtime catalog update or UI URL.
 */
object BundledDebianImageCatalog : DebianImageCatalog {
    const val DEFAULT_RELEASE_ID = "debian-arm64-current"
    private val approved by lazy {
        javaClass.getResourceAsStream("/dev/terminal/image/approved-debian.properties")
            ?.use { input -> Properties().apply { load(input) }.let { if (it.isEmpty()) null else readApprovedProperties(it) } }
    }
    override fun find(releaseId: String): DebianImageRelease? = approved?.takeIf { it.id == releaseId }

    fun readApprovedRelease(input: InputStream): DebianImageRelease =
        readApprovedProperties(Properties().apply { load(input) })

    private fun readApprovedProperties(properties: Properties): DebianImageRelease = properties.run {
        require(getProperty("id") == DEFAULT_RELEASE_ID) { "Unsupported catalog ID" }
        val compression = getProperty("compression", "none")
        require(compression == "none" || compression == "gzip") { "Unsupported image compression" }
        DebianImageRelease(DEFAULT_RELEASE_ID, URI(getProperty("manifestUri") ?: error("Missing manifest URI")),
            getProperty("manifestSha256") ?: error("Missing manifest checksum"),
            getProperty("maxArtifactBytes")?.toLongOrNull() ?: error("Missing artifact size limit"),
            compression == "gzip")
    }
}
