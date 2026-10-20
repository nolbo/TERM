package dev.terminal.runtime.image

import dev.terminal.image.AvfImageInstaller
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class AvfImageDownloadStagingTest {
    @Test fun onlyAnActiveRetryRetainsItsOwnCacheAndNeverTouchesInstalledState() {
        for ((retry, stopped) in listOf(true to false, false to false, false to true, true to true)) {
            val root = Files.createTempDirectory("avf-download-finish")
            try {
                val destination = Files.createDirectory(root.resolve("installed"))
                val sentinel = Files.writeString(destination.resolve("home.img"), "original user state")
                val id = UUID.randomUUID()
                val staging = Files.createDirectory(AvfImageDownloadStaging.path(destination, id))
                val cache = Files.createDirectory(AvfImageDownloadStaging.cachePath(destination, id))
                Files.writeString(cache.resolve("partial"), "partial bytes")
                AvfImageDownloadStaging.finish(destination, id, retry, stopped)
                assertEquals(retry && !stopped, Files.exists(staging))
                assertEquals(retry && !stopped, Files.exists(cache))
                assertEquals("original user state", Files.readString(sentinel))
            } finally { root.toFile().deleteRecursively() }
        }
    }

    @Test fun retriesRetainOnlyTheirOwnRegularStagingAndCacheDirectories() {
        val root = Files.createTempDirectory("avf-download-retry")
        try {
            val destination = root.resolve("installed")
            val id = UUID.randomUUID()
            val other = UUID.randomUUID()
            val staging = Files.createDirectory(AvfImageDownloadStaging.path(destination, id))
            val cache = Files.createDirectory(AvfImageDownloadStaging.cachePath(destination, id))
            val partial = Files.writeString(cache.resolve("partial"), "bytes")
            val stale = Files.createDirectory(AvfImageDownloadStaging.cachePath(destination, other))
            AvfImageDownloadStaging.clean(destination, id, preserveCurrent = true) { false }
            assertTrue(Files.isDirectory(staging))
            assertEquals("bytes", Files.readString(partial))
            assertFalse(Files.exists(stale))
            AvfImageDownloadStaging.clean(destination, id) { false }
            assertFalse(Files.exists(cache))
            assertFalse(Files.exists(staging))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun cleansRestartedAndInactiveDownloadsWithoutTouchingActiveImportsOrImages() {
        val root = Files.createTempDirectory("avf-download-staging")
        try {
            val destination = Files.createDirectory(root.resolve("installed"))
            val installed = Files.writeString(destination.resolve("home.img"), "user data")
            val currentId = UUID.randomUUID()
            val activeId = UUID.randomUUID()
            val current = Files.createDirectory(AvfImageDownloadStaging.path(destination, currentId))
            val stale = Files.createDirectory(AvfImageDownloadStaging.path(destination, UUID.randomUUID()))
            Files.writeString(current.resolve("partial.img"), "partial")
            Files.writeString(stale.resolve("partial.img"), "partial")
            val active = Files.createDirectory(AvfImageDownloadStaging.path(destination, activeId))
            val imported = Files.createDirectory(AvfImageInstaller.newStagingPath(destination))
            val differentEnvironment = Files.createDirectory(AvfImageDownloadStaging.path(root.resolve("other"), UUID.randomUUID()))
            val unknown = Files.createDirectory(root.resolve(".installed.install-work-not-a-uuid"))
            val outside = Files.createDirectory(root.resolve("outside"))
            val sentinel = Files.writeString(outside.resolve("keep.txt"), "keep")
            val link = Files.createSymbolicLink(AvfImageDownloadStaging.path(destination, UUID.randomUUID()), outside)
            val queried = mutableListOf<UUID>()
            AvfImageDownloadStaging.clean(destination, currentId) { id -> queried += id; id == activeId }
            assertFalse(Files.exists(current))
            assertFalse(Files.exists(stale))
            assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            for (retained in listOf(active, imported, differentEnvironment, unknown, outside)) assertTrue(Files.exists(retained))
            assertEquals("user data", Files.readString(installed))
            assertEquals("keep", Files.readString(sentinel))
            assertFalse(queried.contains(currentId))
            assertTrue(queried.contains(activeId))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun missingParentDoesNotCreateOrQueryAnything() {
        val root = Files.createTempDirectory("avf-download-no-parent")
        try {
            val parent = root.resolve("missing")
            AvfImageDownloadStaging.clean(parent.resolve("installed"), UUID.randomUUID()) { error("No query expected") }
            assertFalse(Files.exists(parent))
        } finally { root.toFile().deleteRecursively() }
    }
}
