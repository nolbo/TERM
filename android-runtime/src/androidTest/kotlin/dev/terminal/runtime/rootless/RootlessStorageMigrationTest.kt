package dev.terminal.runtime.rootless

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise Android's NIO provider, including no-follow marker reads. */
@RunWith(AndroidJUnit4::class)
class RootlessStorageMigrationTest {
    @Test fun resolvesLegacyKnownAndInvalidFormatsWithoutRewritingData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.terminal.runtime.test")
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "storage-migration-")
        val marker = root.resolve(".terminal-storage-format")
        try {
            repeat(2) {
                assertEquals(RootlessStorageFormat.LaunchFormat.LEGACY_EMULATED,
                    RootlessStorageFormat.resolveForLaunch(root))
                assertFalse(Files.exists(marker))
            }
            for ((value, expected) in listOf(
                "native-hardlinks-1\n" to RootlessStorageFormat.LaunchFormat.NATIVE,
                "emulated-hardlinks-1\n" to RootlessStorageFormat.LaunchFormat.EMULATED,
            )) {
                Files.write(marker, value.toByteArray())
                assertEquals(expected, RootlessStorageFormat.resolveForLaunch(root))
                assertEquals(value, String(Files.readAllBytes(marker)))
            }
            Files.write(marker, "unknown\n".toByteArray())
            assertTrue(runCatching { RootlessStorageFormat.resolveForLaunch(root) }.isFailure)
            assertEquals("unknown\n", String(Files.readAllBytes(marker)))
            Files.delete(marker)
            Files.createSymbolicLink(marker, root.resolve("missing"))
            assertTrue(runCatching { RootlessStorageFormat.resolveForLaunch(root) }.isFailure)
            assertTrue(Files.isSymbolicLink(marker))
        } finally {
            deleteRootlessTree(root)
        }
    }
}
