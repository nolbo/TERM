package dev.terminal.app

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.session.EnvironmentId
import dev.terminal.runtime.storage.AndroidSharedFolderStorage
import dev.terminal.runtime.storage.ScopedDirectory
import dev.terminal.runtime.storage.SharedFolder
import dev.terminal.runtime.storage.SharedFolderRegistry
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real JNI + primary shared-storage tests; grant all-files access on the test target explicitly. */
@RunWith(AndroidJUnit4::class)
class Android17SharedFolderStorageTest {
    private fun fixture(work: (File, File) -> Unit) {
        assumeTrue("Requires an explicitly provisioned all-files grant", Environment.isExternalStorageManager())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = Files.createTempDirectory(context.filesDir.canonicalFile.toPath(), "share-promotion-test-").toFile()
        val storage = File(AndroidSharedFolderStorage.root(context), "test-${UUID.randomUUID()}")
        try { work(files, storage) } finally {
            if (storage.exists()) { ScopedDirectory.open(storage).use { it.clear() }; storage.delete() }
            ScopedDirectory.open(files).use { it.clear() }; files.delete()
        }
    }

    @Test fun conversionPreservesOriginalAndAllRuntimesResolveTheSameDurableCopy() = fixture { files, storage ->
        var saved: String? = null
        val registry = SharedFolderRegistry(files, { saved }, { saved = it }, { storage }, { true })
        val environment = EnvironmentId.create()
        val folder = SharedFolder(UUID.randomUUID().toString(), "Project", setOf(environment))
        registry.save(folder)
        val original = registry.directory(folder)
        val bytes = ByteArray(200_000) { it.toByte() }
        File(original, "empty").mkdir()
        File(original, "nested").mkdir()
        File(original, "nested/keep").writeBytes(bytes)
        registry.promoteToAndroidStorage(folder)
        val converted = registry.requireFolder(folder.id)
        assertTrue(converted.androidStorage)
        val directory = registry.directory(converted)
        assertTrue(directory.toPath().startsWith(storage.toPath()))
        assertArrayEquals(bytes, File(directory, "nested/keep").readBytes())
        assertArrayEquals(bytes, File(original, "nested/keep").readBytes())
        assertTrue(File(directory, "empty").isDirectory)
        assertEquals(directory, registry.bindings(environment).single().directory)
        registry.save(converted.copy(name = "Renamed"))
        val restarted = SharedFolderRegistry(files, { saved }, { saved = it }, { storage }, { true })
        File(directory, "written-after-conversion").writeText("new data")
        assertEquals(directory, restarted.bindings(environment).single().directory)
        restarted.delete(folder.id)
        assertTrue(restarted.all().isEmpty())
        assertFalse(directory.exists())
        assertFalse(original.exists())
    }

    @Test fun interruptedCopyAndMetadataFailureCanRetryWithoutOverwritingUnownedData() = fixture { files, storage ->
        var saved: String? = null
        var failWrite = false
        val registry = SharedFolderRegistry(files, { saved }, { if (failWrite) throw IOException("metadata unavailable") else saved = it },
            { storage }, { true })
        val folder = SharedFolder(UUID.randomUUID().toString(), "Project", emptySet())
        registry.save(folder)
        val source = registry.directory(folder)
        File(source, "keep").writeText("original data")
        val previous = saved
        assertTrue(runCatching { registry.promoteToAndroidStorage(folder) { _, destination ->
            File(destination, "partial").writeText("interrupted copy")
            throw IOException("owner interrupted")
        } }.isFailure)
        assertEquals(previous, saved)
        assertEquals("original data", File(source, "keep").readText())
        // Retrying removes only the journaled, unpublished copy; metadata publication is last.
        failWrite = true
        assertTrue(runCatching { registry.promoteToAndroidStorage(folder) }.isFailure)
        assertEquals(previous, saved)
        failWrite = false
        registry.promoteToAndroidStorage(folder)
        assertEquals(listOf("keep"), registry.directory(registry.requireFolder(folder.id)).list()!!.toList())
        assertEquals("original data", File(source, "keep").readText())
        val another = SharedFolder(UUID.randomUUID().toString(), "Other", emptySet())
        val unowned = File(storage, "shared-folders/${another.id}").apply { mkdir() }
        File(unowned, "keep").writeText("external data")
        assertTrue(runCatching { registry.promoteToAndroidStorage(another) }.isFailure)
        assertEquals("external data", File(unowned, "keep").readText())
        assertEquals(1, registry.all().size)
    }

    @Test fun linksAndRevokedPermissionCannotPublishOrRecreateData() = fixture { files, storage ->
        var saved: String? = null; var granted = true
        val registry = SharedFolderRegistry(files, { saved }, { saved = it }, { storage }, { granted })
        val folder = SharedFolder(UUID.randomUUID().toString(), "Project", emptySet())
        registry.save(folder)
        val source = registry.directory(folder)
        val outside = File(files, "outside").apply { writeText("outside data") }
        val link = File(source, "link")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertTrue(runCatching { registry.promoteToAndroidStorage(folder) }.isFailure)
        assertFalse(registry.requireFolder(folder.id).androidStorage)
        assertEquals("outside data", outside.readText())
        Files.delete(link.toPath())
        assertTrue(runCatching { registry.promoteToAndroidStorage(folder) { origin, destination ->
            File(destination, "keep").writeText("copied")
            granted = false
        } }.isFailure)
        assertFalse(registry.requireFolder(folder.id).androidStorage)
        granted = true
        registry.promoteToAndroidStorage(folder)
        val converted = registry.requireFolder(folder.id)
        val directory = registry.directory(converted)
        granted = false
        assertTrue(runCatching { registry.directory(converted) }.isFailure)
        assertTrue(runCatching { registry.delete(folder.id) }.isFailure)
        assertEquals(directory, registry.path(converted))
        granted = true
        directory.delete()
        assertTrue(runCatching { registry.directory(converted) }.isFailure)
        assertFalse(directory.exists())
    }

    @Test fun deletingAnInterruptedConversionNeedsPermissionAndCleansOnlyItsOwnCopies() = fixture { files, storage ->
        var saved: String? = null; var granted = true
        val registry = SharedFolderRegistry(files, { saved }, { saved = it }, { storage }, { granted })
        val folder = SharedFolder(UUID.randomUUID().toString(), "Project", emptySet())
        registry.save(folder)
        val source = registry.directory(folder)
        File(source, "keep").writeText("original")
        assertTrue(runCatching { registry.promoteToAndroidStorage(folder) { _, destination ->
            File(destination, "partial").writeText("copy")
            throw IOException("interrupted")
        } }.isFailure)
        val destination = File(storage, "shared-folders/${folder.id}")
        val sibling = File(destination.parentFile, UUID.randomUUID().toString()).apply { mkdir() }
        File(sibling, "keep").writeText("unrelated")
        granted = false
        assertTrue(runCatching { registry.delete(folder.id) }.isFailure)
        assertEquals("original", File(source, "keep").readText())
        assertTrue(destination.isDirectory)
        granted = true
        registry.delete(folder.id)
        assertTrue(registry.all().isEmpty())
        assertFalse(source.exists())
        assertFalse(destination.exists())
        assertEquals("unrelated", File(sibling, "keep").readText())
    }
}
