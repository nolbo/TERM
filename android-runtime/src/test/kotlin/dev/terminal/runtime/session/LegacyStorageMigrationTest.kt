package dev.terminal.runtime.session

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyStorageMigrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun profile(runtime: SessionRuntime, id: String = UUID.randomUUID().toString()) =
        SessionProfile(id, "Existing ${runtime.wireName}", runtime, "/bin/sh", environment = mapOf("CUSTOM" to "keep"))

    @Test fun adoptsAllOldLocationsAndSharedVmWithoutCopyingOrChangingSnapshots() {
        val files = temporary.newFolder()
        val saved = SessionRuntime.entries.map { profile(it) }
        val defaults = SessionRuntime.entries.map { profile(it, SessionProfile.builtInId(it)) }
        val registry = StorageRegistry(files) { saved.associate { profile ->
            profile.id to profile.resolve().toJson().apply { remove("storageId") }.toString()
        } }
        val snapshots = saved.map { it.resolve() }
        val shared = File(files, "shared-vms/default/data").apply { requireNotNull(parentFile).mkdirs(); writeText("VM workspace") }
        val workspace = File(files, "workspace/shared.txt").apply { requireNotNull(parentFile).mkdirs(); writeText("shared Rootless work") }
        val data = SessionRuntime.entries.associateWith { runtime ->
            File(SessionStorage.legacyRoot(files, runtime), "home/sentinel").apply { requireNotNull(parentFile).mkdirs(); writeText(runtime.wireName) }
        }
        val before = data.mapValues { Files.readAttributes(it.value.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey() }
        val migrated = registry.transaction {
            rename(StorageRegistry.Key(SessionRuntime.ROOTLESS, "legacy"), "My installed Linux", defaults)
            SessionRuntime.entries.associateWith { migrateLegacy(it, defaults) }
        }
        val reopened = StorageRegistry(files)
        reopened.transaction {
            assertEquals(migrated.values.toSet(), keys())
            assertEquals("My installed Linux", name(migrated.getValue(SessionRuntime.ROOTLESS)))
            for (runtime in SessionRuntime.entries) {
                val key = migrated.getValue(runtime)
                assertEquals(key, migrateLegacy(runtime, defaults)) // Restart/retry uses the same identity.
                val linked = profiles(defaults).filter { it.runtime == runtime }
                assertEquals(2, linked.size)
                assertTrue(linked.all { it.storageId == key.storageId && it.environment["CUSTOM"] == "keep" })
                assertEquals(SessionStorage.legacyRoot(files, runtime), reopened.root(key))
                assertFalse(File(files, "profile-storage/${runtime.wireName}/${key.storageId}").exists())
                assertEquals(before.getValue(runtime), Files.readAttributes(data.getValue(runtime).toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
                assertEquals(runtime.wireName, data.getValue(runtime).readText())
                assertEquals(key, canonicalKey(StorageRegistry.Key(runtime, "legacy")))
                assertTrue(runCatching { requireOrphan(key, defaults) }.isFailure)
            }
        }
        assertTrue(snapshots.all { it.storageId == "legacy" })
        assertEquals("default", SessionStorage.avfInstanceId(files, migrated.getValue(SessionRuntime.AVF).storageId))
        assertEquals("VM workspace", shared.readText())
        assertEquals("shared Rootless work", workspace.readText())
    }

    @Test fun preservesTombstonesDynamicDefaultsAndFreshProfilesDuringAdoption() {
        val files = temporary.newFolder()
        val native = profile(SessionRuntime.NATIVE)
        val deletedDefault = profile(SessionRuntime.NATIVE, SessionProfile.builtInId(SessionRuntime.NATIVE))
        val fresh = native.copy(id = "fresh", storageId = UUID.randomUUID().toString())
        val registry = StorageRegistry(files) { mapOf(native.id to native.resolve().toJson().toString(), deletedDefault.id to "") }
        val key = registry.transaction { save(fresh); migrateLegacy(SessionRuntime.NATIVE, listOf(deletedDefault)) }
        registry.transaction {
            assertEquals("", overrides().getValue(deletedDefault.id))
            assertEquals(fresh, profiles(listOf(deletedDefault)).single { it.id == fresh.id })
            save(native.copy(name = "Editor opened before update")) // Stale legacy drafts resolve to adopted storage.
            assertEquals(key.storageId, profiles(emptyList()).single { it.id == native.id }.storageId)
        }
        val rootlessDefault = profile(SessionRuntime.ROOTLESS, SessionProfile.builtInId(SessionRuntime.ROOTLESS))
        val rootlessKey = registry.transaction { migrateLegacy(SessionRuntime.ROOTLESS, listOf(rootlessDefault)) }
        val changed = rootlessDefault.copy(shell = "/bin/bash", environment = mapOf("TERM" to "new preference"))
        assertEquals(changed.copy(storageId = rootlessKey.storageId), registry.transaction { profiles(listOf(changed)).single { it.id == changed.id } })
        assertFalse(registry.transaction { overrides().containsKey(changed.id) })
    }

    @Test fun interruptedMetadataCommitLeavesDataAndOldReferencesIntactThenRetries() {
        val files = temporary.newFolder()
        val old = profile(SessionRuntime.NATIVE)
        val registry = StorageRegistry(files) { mapOf(old.id to old.resolve().toJson().toString()) }
        registry.transaction { save(old) }
        val sentinel = File(SessionStorage.legacyRoot(files, old.runtime), "keep").apply { requireNotNull(parentFile).mkdirs(); writeText("valuable data") }
        val blockedTemporary = File(files, "storage-registry.json.tmp").apply { mkdir() }
        assertTrue(runCatching { registry.transaction { migrateLegacy(old.runtime, emptyList()) } }.isFailure)
        val reopened = StorageRegistry(files)
        assertEquals(old, reopened.transaction { profiles(emptyList()).single() })
        assertNull(reopened.transaction { migratedLegacyId(old.runtime) })
        assertEquals("valuable data", sentinel.readText())
        assertTrue(blockedTemporary.delete())
        val key = reopened.transaction { migrateLegacy(old.runtime, emptyList()) }
        assertEquals(key.storageId, StorageRegistry(files).transaction { profiles(emptyList()).single().storageId })
        assertEquals("valuable data", sentinel.readText())
    }

    @Test fun migratedOrphanCanBeDeletedWithoutRemovingSiblingOrSharedWorkAndCannotResurrect() {
        val files = temporary.newFolder()
        val old = profile(SessionRuntime.ROOTLESS)
        val registry = StorageRegistry(files)
        val sentinel = File(SessionStorage.legacyRoot(files, old.runtime), "rootfs/file").apply { requireNotNull(parentFile).mkdirs(); writeText("old packages") }
        val sibling = File(SessionStorage.legacyRoot(files, SessionRuntime.ROOTLESS_UBUNTU), "home/file").apply { requireNotNull(parentFile).mkdirs(); writeText("Ubuntu") }
        val workspace = File(files, "workspace/file").apply { requireNotNull(parentFile).mkdirs(); writeText("shared") }
        val key = registry.transaction { save(old); migrateLegacy(old.runtime, emptyList()) }
        registry.transaction { deleteProfile(old.id, false); requireOrphan(key, emptyList()); delete(key) }
        assertFalse(sentinel.exists())
        assertEquals("Ubuntu", sibling.readText())
        assertEquals("shared", workspace.readText())
        val reopened = StorageRegistry(files)
        reopened.transaction {
            assertEquals(key, migrateLegacy(old.runtime, emptyList()))
            assertFalse(key in keys())
            assertTrue(runCatching { save(old) }.isFailure)
            assertTrue(runCatching { validateConnection(StorageRegistry.Key(old.runtime, "legacy")) }.isFailure)
            assertTrue(runCatching { validateConnection(StorageRegistry.Key(SessionRuntime.ROOTLESS_ARCH, key.storageId)) }.isFailure)
        }
        val fresh = old.copy(storageId = UUID.randomUUID().toString())
        reopened.transaction { save(fresh) }
        assertNotEquals(SessionStorage.legacyRoot(files, old.runtime), reopened.root(StorageRegistry.Key(fresh.runtime, fresh.storageId)))
    }

    @Test fun avfOrphanDeletionPreservesAllLegacyHostFolders() {
        val files = temporary.newFolder()
        val registry = StorageRegistry(files)
        val old = profile(SessionRuntime.AVF)
        val image = File(SessionStorage.legacyRoot(files, old.runtime), "home.img").apply { requireNotNull(parentFile).mkdirs(); writeText("guest data") }
        val shared = File(files, "shared-vms/default/file").apply { requireNotNull(parentFile).mkdirs(); writeText("guest shared data") }
        val independentId = UUID.randomUUID().toString()
        val independent = File(files, "shared-vms/$independentId/file").apply { requireNotNull(parentFile).mkdirs(); writeText("independent") }
        val key = registry.transaction { save(old); migrateLegacy(old.runtime, emptyList()) }
        assertEquals(image.length() + shared.length(), registry.measure(key))
        assertEquals(independentId, SessionStorage.avfInstanceId(files, independentId))
        registry.transaction { deleteProfile(old.id, false); requireOrphan(key, emptyList()); delete(key) }
        assertFalse(image.exists())
        assertEquals("guest shared data", shared.readText())
        assertEquals("independent", independent.readText())
    }
}
