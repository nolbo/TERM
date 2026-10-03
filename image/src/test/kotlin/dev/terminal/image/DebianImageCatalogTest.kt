package dev.terminal.image

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class DebianImageCatalogTest {
    private fun read(overrides: String = "") = BundledDebianImageCatalog.readApprovedRelease("""
        id=debian-arm64-current
        manifestUri=https://example.invalid/releases/v1/manifest.properties
        manifestSha256=${"a".repeat(64)}
        maxArtifactBytes=8589934592
        compression=gzip
        $overrides
    """.trimIndent().byteInputStream())

    @Test fun factoryCatalogKeepsPinnedManifestAndCompression() {
        val release = read()
        assertEquals("a".repeat(64), release.manifestSha256)
        assertTrue(release.gzipArtifacts)
        assertEquals(8589934592, release.maxArtifactBytes)
    }

    @Test fun rejectsUnpinnedCatalogAndUnknownCompression() {
        for (invalid in listOf("manifestSha256=", "compression=zip", "manifestUri=http://example.invalid/manifest.properties",
            "manifestUri=https://user@example.invalid/manifest.properties", "maxArtifactBytes=999999999999", "id=arbitrary")) {
            assertThrows(IllegalArgumentException::class.java) { read(invalid) }
        }
    }

    @Test fun boundedCopyRejectsExpandedPayloadAndCancellation() {
        assertThrows(IllegalArgumentException::class.java) {
            DebianArtifactDownloader.copyBounded(ByteArrayInputStream(ByteArray(1024)), ByteArrayOutputStream(), 128)
        }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            DebianArtifactDownloader.copyBounded(ByteArrayInputStream(ByteArray(10)), ByteArrayOutputStream(), 128) { true }
        }
    }
}
