package dev.terminal.image

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Host-owned receipt for a package installed from a fully verified, APK-approved image. */
object AvfGuestComponents {
    const val MANIFEST = ".guest-components-manifest"
    const val RECEIPT = ".guest-components-sha256"

    fun record(root: Path, approvedImage: Path) {
        Files.copy(approvedImage.resolve(AvfImageLayout.MANIFEST_FILE), root.resolve(MANIFEST),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        Files.write(root.resolve(RECEIPT), AvfImageVerifier.sha256(root.resolve(MANIFEST)).toByteArray(Charsets.US_ASCII))
    }

    fun effective(root: Path, base: AvfImageManifest): AvfImageManifest {
        val installed = source(root, base) ?: return base
        // Kernel/initrd-dependent capabilities continue to come from the installed boot image.
        return base.copy(shareBrokerVersion = installed.shareBrokerVersion,
            sessionResumeVersion = installed.sessionResumeVersion,
            packageInitializationVersion = installed.packageInitializationVersion)
    }

    /** The verified source generation, rather than the unchanged base, identifies applied components. */
    fun source(root: Path, base: AvfImageManifest): AvfImageManifest? {
        val manifest = root.resolve(MANIFEST)
        val receipt = root.resolve(RECEIPT)
        if (!Files.exists(receipt, NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(receipt, NOFOLLOW_LINKS) && Files.size(receipt) == 64L &&
            Files.isRegularFile(manifest, NOFOLLOW_LINKS) && Files.size(manifest) <= 65536 &&
            Files.readAllBytes(receipt).toString(Charsets.US_ASCII) == AvfImageVerifier.sha256(manifest)) {
            "Invalid guest-component update receipt"
        }
        val installed = AvfImageVerifier.parse(manifest)
        require(installed.distribution == base.distribution && installed.kernelSha256 == base.kernelSha256 &&
            installed.formatVersion == base.formatVersion)
        return installed
    }
}
