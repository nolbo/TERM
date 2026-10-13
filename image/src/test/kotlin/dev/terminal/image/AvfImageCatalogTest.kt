package dev.terminal.image

import org.junit.Assert.*
import org.junit.Test

class AvfImageCatalogTest {
    private fun read(overrides: String = "") = BundledAvfImageCatalog.readApprovedRelease("""
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

    @Test fun collectionPinsEachDistributionIndependently() {
        val catalog = "formatVersion=2\ndistributions=debian,ubuntu,archlinux\n" + AvfDistribution.entries.joinToString("") { distro ->
            "${distro.id}.id=${distro.releaseId}\n" +
                "${distro.id}.manifestUri=https://example.invalid/${distro.id}/manifest.properties\n" +
                "${distro.id}.manifestSha256=${"a".repeat(64)}\n${distro.id}.maxArtifactBytes=1024\n"
        }
        val releases = BundledAvfImageCatalog.readApprovedReleases(catalog.byteInputStream())
        assertEquals(AvfDistribution.entries, releases.map { it.distribution })
        assertEquals(3, releases.map { it.id }.distinct().size)
        for (invalid in listOf(catalog.replace("ubuntu.id=ubuntu", "ubuntu.id=debian"),
            catalog.replace("distributions=debian,ubuntu,archlinux", "distributions=debian,debian"),
            catalog + "fedora.id=fedora-arm64-current\n")) {
            assertThrows(IllegalArgumentException::class.java) { BundledAvfImageCatalog.readApprovedReleases(invalid.byteInputStream()) }
        }
    }
}
