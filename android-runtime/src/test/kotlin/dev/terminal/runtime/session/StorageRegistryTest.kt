package dev.terminal.runtime.session

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageRegistryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun profile(runtime: SessionRuntime = SessionRuntime.NATIVE) = SessionProfile(UUID.randomUUID().toString(), "Test", runtime,
        "/bin/sh", storageId = UUID.randomUUID().toString())
    @Test fun migratedOverridesAndTombstonesPersistAcrossInstances() {
        val files = temporary.newFolder()
        val saved = profile()
        val registry = StorageRegistry(files) { mapOf(saved.id to saved.resolve().toJson().toString(), "builtin:NATIVE" to "") }
        registry.transaction { save(saved.copy(name = "Edited")) }
        val reopened = StorageRegistry(files)
        assertEquals("Edited", reopened.transaction { profiles(emptyList()).single().name })
        assertEquals("", reopened.transaction { overrides().getValue("builtin:NATIVE") })
        reopened.transaction { deleteProfile(saved.id, false) }
        assertTrue(reopened.transaction { profiles(emptyList()).isEmpty() })
        assertTrue(StorageRegistry.Key(saved.runtime, saved.storageId) in reopened.transaction { keys() })
    }
    @Test fun freshSwitchRetainsOldEnvironmentWithoutCopyingAndSnapshotsRemainUnchanged() {
        val registry = StorageRegistry(temporary.newFolder())
        val saved = profile()
        val spec = saved.resolve()
        val oldKey = StorageRegistry.Key(saved.runtime, saved.storageId)
        registry.root(oldKey).mkdirs()
        File(registry.root(oldKey), "sentinel").writeText("keep me")
        registry.transaction { save(saved) }
        val edited = saved.copy(storageId = UUID.randomUUID().toString())
        registry.transaction { save(edited) }
        val freshKey = StorageRegistry.Key(edited.runtime, edited.storageId)
        assertEquals(saved.storageId, spec.storageId)
        assertEquals("keep me", File(registry.root(oldKey), "sentinel").readText())
        assertFalse(registry.root(freshKey).exists())
        assertTrue(oldKey in registry.transaction { keys() })
        registry.transaction { requireOrphan(oldKey, emptyList()); delete(oldKey) }
        assertTrue(runCatching { registry.transaction { save(saved) } }.isFailure)
    }
    @Test fun rejectsCrossRuntimeAndCrossDistributionConnections() {
        val registry = StorageRegistry(temporary.newFolder())
        val original = profile(SessionRuntime.ROOTLESS)
        registry.transaction { save(original) }
        for (runtime in SessionRuntime.entries.filter { it != original.runtime }) {
            assertTrue(runCatching { registry.transaction { save(original.copy(id = UUID.randomUUID().toString(), runtime = runtime)) } }.isFailure)
        }
        registry.transaction { save(original.copy(id = "shared", name = "Sharing")) }
        assertEquals(2, registry.transaction { profiles(emptyList()).size })
    }
    @Test fun referencedAndLegacyStorageCannotBeDeleted() {
        val registry = StorageRegistry(temporary.newFolder())
        val saved = profile()
        registry.transaction { save(saved) }
        val key = StorageRegistry.Key(saved.runtime, saved.storageId)
        assertTrue(runCatching { registry.transaction { requireOrphan(key, emptyList()) } }.isFailure)
        assertTrue(runCatching { registry.transaction { requireOrphan(StorageRegistry.Key(SessionRuntime.NATIVE, "legacy"), emptyList()) } }.isFailure)
        registry.transaction { deleteProfile(saved.id, false); requireOrphan(key, emptyList()); delete(key) }
        assertFalse(key in registry.transaction { keys() })
    }
    @Test fun enumeratesOrphansAndNeverTraversesSymlinksForSizeOrDeletion() {
        val registry = StorageRegistry(temporary.newFolder())
        val orphan = StorageRegistry.Key(SessionRuntime.ROOTLESS, UUID.randomUUID().toString())
        val root = registry.root(orphan).apply { mkdirs() }
        File(root, "data").writeText("12345")
        val outside = temporary.newFolder("outside")
        val sentinel = File(outside, "secret").apply { writeText("untouched") }
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        assertTrue(orphan in registry.transaction { keys() })
        assertTrue(registry.measure(orphan) >= 5)
        val cached = registry.cachedSize(orphan)
        File(root, "more").writeText("more data")
        assertEquals(cached, registry.measure(orphan))
        registry.transaction { requireOrphan(orphan, emptyList()); delete(orphan) }
        assertFalse(root.exists())
        assertEquals("untouched", sentinel.readText())
    }
    @Test fun deleteAndProfileSaveShareOneTransactionAcrossRegistryInstances() {
        val files = temporary.newFolder()
        val first = StorageRegistry(files)
        val second = StorageRegistry(files)
        val saved = profile()
        val key = StorageRegistry.Key(saved.runtime, saved.storageId)
        first.transaction { save(saved); deleteProfile(saved.id, false) }
        val entered = java.util.concurrent.CountDownLatch(1)
        val savedAgain = java.util.concurrent.CompletableFuture<Boolean>()
        first.transaction {
            requireOrphan(key, emptyList())
            Thread {
                entered.countDown()
                savedAgain.complete(runCatching { second.transaction { save(saved) } }.isSuccess)
            }.start()
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            delete(key)
        }
        assertFalse(savedAgain.get(5, java.util.concurrent.TimeUnit.SECONDS))
    }
}
