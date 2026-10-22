package dev.terminal.image

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

enum class AvfImageUpdateKind { COMPONENTS, IMAGE }

sealed interface AvfImageUpdateResult {
    data object UpToDate : AvfImageUpdateResult
    data object Unsupported : AvfImageUpdateResult
    data class Available(val kind: AvfImageUpdateKind) : AvfImageUpdateResult
}

/** Read-only metadata check. Boot/activation still verify every immutable artifact in the service. */
class AvfImageUpdateChecker(private val cancelled: () -> Boolean = { false }) {
    internal var downloadManifest: (AvfImageRelease, Path) -> Unit = { release, destination ->
        AvfArtifactDownloader(cancelled).download(release.manifestUri, destination, 64 * 1024)
    }

    fun check(root: Path, release: AvfImageRelease, cache: Path): AvfImageUpdateResult {
        checkCancelled()
        require(AvfImageVerifier.hasInstalledImage(root, release.distribution)) { "Invalid installed AVF image metadata" }
        require(!AvfImageTransitions.interrupted(root)) { "AVF image recovery required" }
        val basePath = root.resolve(AvfImageLayout.MANIFEST_FILE)
        val base = AvfImageVerifier.parse(basePath)
        val components = AvfGuestComponents.source(root, base)
        // Writable home.img contents are deliberately excluded from this comparison.
        val sourcePath = if (components == null) basePath else root.resolve(AvfGuestComponents.MANIFEST)
        if (AvfImageVerifier.sha256(sourcePath, ::checkCancelled) == release.manifestSha256) {
            return AvfImageUpdateResult.UpToDate
        }
        val temporary = Files.createTempDirectory(cache, "avf-update-check-")
        val manifest = temporary.resolve(AvfImageLayout.MANIFEST_FILE)
        try {
            downloadManifest(release, manifest)
            checkCancelled()
            require(Files.size(manifest) <= 64 * 1024 &&
                AvfImageVerifier.sha256(manifest, ::checkCancelled) == release.manifestSha256) { "AVF manifest differs from catalog" }
            val next = AvfImageVerifier.parse(manifest)
            require(next.architecture == "arm64" && next.guestDistribution == release.distribution &&
                listOf(next.kernelSha256, next.initrdSha256, next.systemSha256, next.homeSha256, next.configSha256)
                    .all { it.matches(Regex("[0-9a-f]{64}")) }) { "Invalid AVF update manifest" }
            if (next.maintenanceVersion != 1 || next.formatVersion < base.formatVersion) return AvfImageUpdateResult.Unsupported
            return AvfImageUpdateResult.Available(kind(base, next))
        } finally {
            Files.deleteIfExists(manifest)
            Files.deleteIfExists(temporary)
        }
    }

    private fun checkCancelled() {
        if (cancelled()) throw CancellationException("AVF update check cancelled")
    }

    companion object {
        /** Components retain the boot/base; kernel or root layout changes require a full transition. */
        fun kind(base: AvfImageManifest, next: AvfImageManifest): AvfImageUpdateKind {
            require(base.distribution == next.distribution && base.architecture == next.architecture)
            return if (base.kernelSha256 == next.kernelSha256 && base.formatVersion == next.formatVersion)
                AvfImageUpdateKind.COMPONENTS else AvfImageUpdateKind.IMAGE
        }
    }
}
