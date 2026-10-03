package dev.terminal.runtime.image

import android.content.ContextWrapper
import dev.terminal.image.DebianImageInstaller
import dev.terminal.image.DebianImageVerifier
import dev.terminal.image.ImageVerification
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimeSetupCoordinatorTest {
    private val setup = RuntimeSetupCoordinator(ContextWrapper(null))

    @Test fun existingOfflineImageIsVerifiedOnlyAtPreparationAndRetainsGuestWrites() = runBlocking {
        withImageDirectory { root ->
            writeImage(root)
            setup.ensureImage(root, "not-in-catalog")
            val receipt = root.resolve(".installed-manifest-sha256")
            assertFalse("Provisioning must not verify/adopt the image", Files.exists(receipt))

            assertTrue(DebianImageInstaller.verifyForStartup(root) is ImageVerification.Ready)
            assertTrue(Files.exists(receipt))
            Files.writeString(root.resolve("home.img"), "persistent guest writes")
            assertTrue(DebianImageInstaller.verifyForStartup(root) is ImageVerification.Ready)
            assertEquals("persistent guest writes", Files.readString(root.resolve("home.img")))
        }
    }

    @Test fun installReceiptNeverReplacesImmutableChecksumVerification() = runBlocking {
        for (name in listOf("vmlinuz", "initrd.img", "system.img", "config.img")) {
            withImageDirectory { root ->
                writeImage(root)
                assertTrue(DebianImageInstaller.verifyForStartup(root) is ImageVerification.Ready)
                Files.writeString(root.resolve(name), "tampered")

                setup.ensureImage(root, "not-in-catalog")
                val verification = DebianImageInstaller.verifyForStartup(root)
                assertTrue(verification is ImageVerification.Invalid)
                assertEquals("Checksum verification failed for $name", (verification as ImageVerification.Invalid).reason)
                assertEquals("tampered", Files.readString(root.resolve(name)))
            }
        }
    }

    @Test fun invalidExistingEntriesArePreservedForPreparationToReject() = runBlocking {
        withImageDirectory { parent ->
            val regularFile = Files.writeString(parent.resolve("file"), "user data")
            val brokenLink = Files.createSymbolicLink(parent.resolve("link"), parent.resolve("missing"))
            for (root in listOf(regularFile, brokenLink)) {
                setup.ensureImage(root, "not-in-catalog")
                assertTrue(DebianImageInstaller.verifyForStartup(root) is ImageVerification.Invalid)
            }
            assertEquals("user data", Files.readString(regularFile))
            assertTrue(Files.isSymbolicLink(brokenLink))
        }
    }

    @Test fun missingImageStillRequiresAnApprovedCatalog() = runBlocking {
        withImageDirectory { parent ->
            val root = parent.resolve("missing")
            val failure = runCatching { setup.ensureImage(root, "not-in-catalog") }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message.orEmpty().contains("등록되지 않았습니다"))
            assertFalse(Files.exists(root))
        }
    }

    private suspend fun withImageDirectory(block: suspend (Path) -> Unit) {
        val root = Files.createTempDirectory("avf-startup-test")
        try { block(root) } finally { root.toFile().deleteRecursively() }
    }

    private fun writeImage(root: Path) {
        val artifacts = listOf("kernel" to "vmlinuz", "initrd" to "initrd.img", "system" to "system.img",
            "home" to "home.img", "config" to "config.img")
        for ((_, name) in artifacts) Files.writeString(root.resolve(name), name)
        Files.writeString(root.resolve("manifest.properties"),
            "formatVersion=1\ndistribution=debian\narchitecture=arm64\n" +
                artifacts.joinToString("\n") { (key, name) -> "$key.sha256=${DebianImageVerifier.sha256(root.resolve(name))}" } + "\n")
    }
}
