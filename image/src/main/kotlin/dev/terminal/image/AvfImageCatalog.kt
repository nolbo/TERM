package dev.terminal.image

import java.net.URI
import java.io.InputStream
import java.util.Properties

/** A release must come from an app/OEM-provided catalog, never arbitrary UI input. */
data class AvfImageRelease(
    val id: String,
    val manifestUri: URI,
    val manifestSha256: String,
    val maxArtifactBytes: Long,
    val gzipArtifacts: Boolean = false,
    val distribution: AvfDistribution = AvfDistribution.DEBIAN,
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

interface AvfImageCatalog {
    fun find(releaseId: String): AvfImageRelease?
}

/** Signed APK catalog: legacy single-release properties or distribution-prefixed entries. */
object BundledAvfImageCatalog : AvfImageCatalog {
    const val DEFAULT_RELEASE_ID = "debian-arm64-current"
    private val approved by lazy {
        // Keep the resource path compatible with catalog-bearing APK verification.
        javaClass.getResourceAsStream("/dev/terminal/image/approved-debian.properties")
            ?.use(::readApprovedReleases).orEmpty()
    }
    override fun find(releaseId: String): AvfImageRelease? = approved.firstOrNull { it.id == releaseId }
    fun find(distribution: AvfDistribution): AvfImageRelease? = find(distribution.releaseId)

    fun readApprovedRelease(input: InputStream): AvfImageRelease = readApprovedReleases(input).single()

    fun readApprovedReleases(input: InputStream): List<AvfImageRelease> {
        val properties = Properties().apply { load(input) }
        if (properties.isEmpty()) return emptyList()
        if (properties.containsKey("id")) return listOf(readRelease(properties))
        require(properties.getProperty("formatVersion") == "2") { "Unsupported catalog format" }
        val ids = properties.getProperty("distributions")?.split(',') ?: error("Missing catalog distributions")
        require(ids.isNotEmpty() && ids.distinct().size == ids.size) { "Duplicate catalog distribution" }
        val distributions = ids.map(AvfDistribution::fromId)
        val keys = setOf("id", "releaseId", "manifestUri", "manifestSha256", "maxArtifactBytes", "compression")
        require(properties.stringPropertyNames().all { key -> key in setOf("formatVersion", "distributions") ||
            distributions.any { key.startsWith("${it.id}.") && key.removePrefix("${it.id}.") in keys } }) { "Unknown catalog entry" }
        return distributions.map { distribution ->
            val entry = Properties()
            keys.forEach { key -> properties.getProperty("${distribution.id}.$key")?.let { entry.setProperty(key, it) } }
            readRelease(entry).also { require(it.distribution == distribution) { "Catalog distribution mismatch" } }
        }
    }

    private fun readRelease(properties: Properties): AvfImageRelease = properties.run {
        val id = getProperty("id") ?: error("Missing catalog ID")
        val distribution = AvfDistribution.entries.firstOrNull { it.releaseId == id }
            ?: throw IllegalArgumentException("Unsupported catalog ID")
        val compression = getProperty("compression", "none")
        require(compression == "none" || compression == "gzip") { "Unsupported image compression" }
        AvfImageRelease(id, URI(getProperty("manifestUri") ?: error("Missing manifest URI")),
            getProperty("manifestSha256") ?: error("Missing manifest checksum"),
            getProperty("maxArtifactBytes")?.toLongOrNull() ?: error("Missing artifact size limit"),
            compression == "gzip", distribution)
    }
}
