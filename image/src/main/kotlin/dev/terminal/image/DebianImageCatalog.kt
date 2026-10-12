package dev.terminal.image

import java.net.URI

/** A release must come from an app/OEM-provided catalog, never arbitrary UI input. */
data class DebianImageRelease(
    val id: String,
    val manifestUri: URI,
    val manifestSha256: String,
    val maxArtifactBytes: Long,
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "Invalid image release ID" }
        require(manifestUri.scheme == "https") { "Image manifest must use HTTPS" }
        require(manifestSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid manifest checksum" }
        require(maxArtifactBytes in 1..MAX_ARTIFACT_BYTES) { "Invalid artifact size limit" }
    }

    companion object { const val MAX_ARTIFACT_BYTES = 32L * 1024 * 1024 * 1024 }
}

interface DebianImageCatalog {
    fun find(releaseId: String): DebianImageRelease?
}

/**
 * Product/OEM builds replace this with a code-signed, versioned catalog. An
 * empty catalog is intentional for this source build: no unreviewed URL can
 * become a guest image simply by being entered in the UI.
 */
object BundledDebianImageCatalog : DebianImageCatalog {
    override fun find(releaseId: String): DebianImageRelease? = null
}
