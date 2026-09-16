package dev.terminal.runtime.native

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeExecutableLoaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun resolvesOnlyExecutableAllowListedPayloadFromCanonicalLibraryDirectory() {
        val directory = temporary.newFolder("lib")
        val proot = File(directory, "libproot.so").apply {
            writeText("payload")
            assertTrue(setExecutable(true))
        }
        val resolver = PackagedExecutableResolver(directory) { listOf("arm64-v8a") }

        assertEquals(proot.canonicalFile, resolver.resolvePackagedExecutable("proot").getOrThrow())
        assertTrue(resolver.resolvePackagedExecutable("shell").exceptionOrNull()?.message?.contains("allow-listed") == true)
    }

    @Test fun missingPayloadNamesAbiAndExecutabilityInDiagnostic() {
        val resolver = PackagedExecutableResolver(temporary.newFolder("lib")) { listOf("arm64-v8a") }

        val message = resolver.resolvePackagedExecutable("proot-loader").exceptionOrNull()?.message.orEmpty()

        assertTrue(message.contains("proot-loader"))
        assertTrue(message.contains("arm64-v8a"))
        assertTrue(message.contains("executable"))
    }

    @Test fun toolingIsPackagedOnlyAndNeverAUsbmuxDaemonOrDownloadedWorker() {
        val directory = temporary.newFolder("bootstrap-lib")
        val resolver = PackagedExecutableResolver(directory) { listOf("arm64-v8a") }
        listOf("terminal-usb", "iphone-tool-probe", "isideload", "rootless-notify-probe").forEach { name ->
            assertTrue(resolver.resolvePackagedExecutable(name).isFailure)
            val binary = File(directory, "lib$name.so").apply { writeText("test"); setExecutable(true) }
            assertEquals(binary.canonicalFile, resolver.resolvePackagedExecutable(name).getOrThrow())
        }
        listOf("usbmuxd", "mux-owner", "../terminal-usb", "sidestore-bootstrap").forEach {
            assertTrue(resolver.resolvePackagedExecutable(it).isFailure)
        }
    }

    @Test fun rootlessProbeCannotResolveOutsideTheApkLibraryDirectory() {
        val directory = temporary.newFolder("notify-lib")
        val outside = temporary.newFile("downloaded-probe").apply { setExecutable(true) }
        Files.createSymbolicLink(File(directory, "librootless-notify-probe.so").toPath(), outside.toPath())
        val result = PackagedExecutableResolver(directory) { listOf("arm64-v8a") }
            .resolvePackagedExecutable("rootless-notify-probe")
        assertTrue(result.exceptionOrNull()?.message?.contains("escaped APK") == true)
    }

    @Test fun iphoneWorkerMustBeAnImmutablePackagedExecutable() {
        val directory = temporary.newFolder("iphone-lib")
        val resolver = PackagedExecutableResolver(directory) { listOf("arm64-v8a") }
        assertTrue(resolver.resolvePackagedExecutable("iphone-worker").isFailure)
        val worker = File(directory, "libiphone-worker.so").apply {
            writeText("test payload")
            assertTrue(setExecutable(true))
        }
        assertEquals(worker.canonicalFile, resolver.resolvePackagedExecutable("iphone-worker").getOrThrow())
        assertTrue(resolver.resolvePackagedExecutable("../iphone-worker").isFailure)
        assertTrue(resolver.resolvePackagedExecutable("usbmuxd").isFailure)
    }
}
