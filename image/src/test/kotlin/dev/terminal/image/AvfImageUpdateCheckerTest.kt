package dev.terminal.image

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AvfImageUpdateCheckerTest {
    private fun image(root: Path, system: String, bootChange: String? = null, maintenance: Boolean = true): Path {
        Files.createDirectories(root)
        val artifacts = mapOf("kernel" to "vmlinuz", "initrd" to "initrd.img", "system" to "system.img",
            "home" to "home.img", "config" to "config.img")
        artifacts.forEach { (key, file) ->
            Files.writeString(root.resolve(file), when {
                key == "system" -> system
                key == bootChange -> "changed $key"
                else -> "same $key"
            })
        }
        Files.writeString(root.resolve("manifest.properties"), "formatVersion=1\ndistribution=debian\narchitecture=arm64\n" +
            (if (maintenance) "maintenanceVersion=1\n" else "") + artifacts.entries.joinToString("\n") { (key, file) ->
                "$key.sha256=${AvfImageVerifier.sha256(root.resolve(file))}"
            })
        return root
    }

    private fun release(root: Path) = AvfImageRelease("debian-arm64-current", URI("https://example.invalid/manifest.properties"),
        AvfImageVerifier.sha256(root.resolve("manifest.properties")), 1024)

    private fun checker(candidate: Path) = AvfImageUpdateChecker().apply {
        downloadManifest = { _, destination -> Files.copy(candidate.resolve("manifest.properties"), destination); Unit }
    }

    private fun fixture(test: (Path, Path, Path) -> Unit) {
        val temp = Files.createTempDirectory("avf-update-check-test")
        try { test(temp, image(temp.resolve("installed"), "old"), Files.createDirectory(temp.resolve("cache"))) }
        finally { temp.toFile().deleteRecursively() }
    }

    @Test fun currentPinAndAppliedComponentsDoNotOfferAnUpdateOrFetchAnything() = fixture { temp, root, cache ->
        val checker = AvfImageUpdateChecker().apply { downloadManifest = { _, _ -> error("Unexpected network fetch") } }
        Files.writeString(root.resolve("home.img"), "user packages and files")
        assertEquals(AvfImageUpdateResult.UpToDate, checker.check(root, release(root), cache))
        val candidate = image(temp.resolve("new"), "new")
        AvfGuestComponents.record(root, candidate)
        assertEquals(AvfImageUpdateResult.UpToDate, checker.check(root, release(candidate), cache))
        assertEquals(0L, Files.list(cache).use { it.count() })
    }

    @Test fun sameKernelOffersComponentsAndChangedKernelOffersAFullImage() = fixture { temp, root, cache ->
        for (boot in listOf(null, "kernel", "initrd", "config")) {
            val candidate = image(temp.resolve("new-${boot ?: "components"}"), "new", boot)
            assertEquals(AvfImageUpdateResult.Available(if (boot == "kernel") AvfImageUpdateKind.IMAGE else AvfImageUpdateKind.COMPONENTS),
                checker(candidate).check(root, release(candidate), cache))
            assertEquals(0L, Files.list(cache).use { it.count() })
        }
        assertEquals("old", Files.readString(root.resolve("system.img")))
    }

    @Test fun unavailableMaintenanceDoesNotOfferAnAction() = fixture { temp, root, cache ->
        val candidate = image(temp.resolve("legacy"), "different", maintenance = false)
        assertEquals(AvfImageUpdateResult.Unsupported, checker(candidate).check(root, release(candidate), cache))
    }

    @Test fun rootLayoutTransitionRequiresAFullImageEvenWithTheSameKernel() = fixture { temp, root, cache ->
        val candidate = image(temp.resolve("ext4"), "new")
        val manifest = candidate.resolve("manifest.properties")
        Files.writeString(manifest, Files.readString(manifest).replace("formatVersion=1", "formatVersion=2"))
        assertEquals(AvfImageUpdateResult.Available(AvfImageUpdateKind.IMAGE),
            checker(candidate).check(root, release(candidate), cache))
        assertEquals(AvfImageUpdateResult.Unsupported,
            checker(root).check(candidate, release(root), cache))
        AvfGuestComponents.record(root, candidate)
        assertThrows(IllegalArgumentException::class.java) { AvfGuestComponents.source(root, AvfImageVerifier.parse(root.resolve("manifest.properties"))) }
    }

    @Test fun wrongPinWrongDistributionAndFailedFetchNeverBecomeUpToDateAndCleanStaging() = fixture { temp, root, cache ->
        val candidate = image(temp.resolve("new"), "new")
        assertThrows(IllegalArgumentException::class.java) { checker(candidate).check(root, release(candidate).copy(manifestSha256 = "a".repeat(64)), cache) }
        val manifest = candidate.resolve("manifest.properties")
        Files.writeString(manifest, Files.readString(manifest).replace("distribution=debian", "distribution=ubuntu"))
        assertThrows(IllegalArgumentException::class.java) { checker(candidate).check(root, release(candidate), cache) }
        val failed = AvfImageUpdateChecker().apply { downloadManifest = { _, _ -> throw java.io.IOException("offline") } }
        assertThrows(java.io.IOException::class.java) { failed.check(root, release(candidate), cache) }
        assertEquals(0L, Files.list(cache).use { it.count() })
    }

    @Test fun corruptComponentReceiptAndCancelledCheckDoNotOfferAnUpdate() = fixture { temp, root, cache ->
        val candidate = image(temp.resolve("new"), "new")
        AvfGuestComponents.record(root, candidate)
        Files.writeString(root.resolve(AvfGuestComponents.RECEIPT), "b".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { checker(candidate).check(root, release(candidate), cache) }
        assertThrows(CancellationException::class.java) { AvfImageUpdateChecker { true }.check(root, release(candidate), cache) }
    }
}
