package dev.terminal.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.rootless.RootlessDebianBackend
import dev.terminal.runtime.rootless.RootlessDebianLayout
import dev.terminal.runtime.rootless.RootlessDistribution
import dev.terminal.runtime.rootless.RootlessStatus
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real pinned-image install/remove/reinstall in isolated test storage, never user distributions. */
@RunWith(AndroidJUnit4::class)
class RootlessImageRemovalTest {
    @Test fun removalRejectsSymlinkedEnvironmentAndCacheParentsWithoutDeletingData() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val testRoot = Files.createTempDirectory(target.cacheDir.toPath(), "unsafe-image-removal-").toFile()
        val isolated = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(testRoot, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(testRoot, "cache").apply { mkdirs() }
        }
        val layout = RootlessDebianLayout(File(isolated.filesDir, "rootless/debian"))
        val outside = File(testRoot, "outside/rootfs/keep").apply { parentFile!!.mkdirs(); writeText("retained") }
        val backend = RootlessDebianBackend(isolated, distribution = RootlessDistribution.DEBIAN)
        try {
            layout.root.parentFile!!.mkdirs()
            Files.createSymbolicLink(layout.root.toPath(), outside.parentFile!!.parentFile!!.toPath())
            assertTrue(backend.remove(removeHome = true).isFailure)
            assertEquals("retained", outside.readText())
            Files.delete(layout.root.toPath())
            val image = File(layout.rootfs, "keep").apply { parentFile!!.mkdirs(); writeText("image") }
            val outsideCache = File(testRoot, "outside-cache/debian/keep").apply { parentFile!!.mkdirs(); writeText("cached") }
            Files.createSymbolicLink(File(isolated.cacheDir, "rootless-downloads").toPath(), outsideCache.parentFile!!.parentFile!!.toPath())
            assertTrue(backend.remove(removeHome = true).isFailure)
            assertEquals("image", image.readText())
            assertEquals("cached", outsideCache.readText())
        } finally { testRoot.deleteRecursively() }
    }

    @Test fun installedImageCanBeRemovedAndInstalledAgain() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val testRoot = Files.createTempDirectory(target.cacheDir.toPath(), "image-removal-test-").toFile()
        val isolated = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(testRoot, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(testRoot, "cache").apply { mkdirs() }
        }
        val layout = RootlessDebianLayout(File(isolated.filesDir, "rootless/debian"))
        val backend = RootlessDebianBackend(isolated, distribution = RootlessDistribution.DEBIAN)
        try {
            backend.prepare().getOrThrow()
            assertEquals(RootlessStatus.Ready, backend.status())
            val home = File(layout.home, "keep").apply { writeText("home") }
            val workspace = File(layout.workspace, "keep").apply { requireNotNull(parentFile).mkdirs(); writeText("workspace") }
            val sibling = File(isolated.filesDir, "rootless/ubuntu/rootfs/keep").apply { parentFile!!.mkdirs(); writeText("sibling") }
            backend.remove().getOrThrow()
            assertFalse(layout.rootfs.exists())
            assertTrue(backend.status() is RootlessStatus.SetupRequired)
            assertEquals("home", home.readText())
            assertEquals("workspace", workspace.readText())
            assertEquals("sibling", sibling.readText())
            backend.prepare().getOrThrow()
            assertEquals(RootlessStatus.Ready, backend.status())
            assertEquals("home", home.readText())
        } finally {
            backend.remove().getOrThrow()
            testRoot.deleteRecursively()
        }
    }
}
