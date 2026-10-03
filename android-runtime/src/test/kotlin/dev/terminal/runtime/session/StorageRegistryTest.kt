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
    @Test fun defaultNamesUseCreatorNameAndAvoidNamesAcrossRuntimesAndOrphans() {
        val registry = StorageRegistry(temporary.newFolder())
        val first = profile().copy(name = "Development")
        val second = profile(SessionRuntime.ROOTLESS).copy(name = first.name)
        val third = profile(SessionRuntime.ROOTLESS_UBUNTU).copy(name = first.name)
        fun key(profile: SessionProfile) = StorageRegistry.Key(profile.runtime, profile.storageId)
        registry.transaction {
            save(first)
            save(second, "")
            deleteProfile(first.id, false) // Unused storage still reserves its display name.
            save(third)
            assertEquals("Storage for Development", name(key(first)))
            assertEquals("Storage for Development (2)", name(key(second)))
            assertEquals("Storage for Development (3)", name(key(third)))
            save(second.copy(name = "Renamed profile"))
            assertEquals("Storage for Development (2)", name(key(second)))
            val custom = profile()
            save(custom, "Workspace")
            val duplicate = profile()
            save(duplicate, "Workspace")
            assertEquals("Workspace", name(key(custom)))
            assertEquals("Workspace (2)", name(key(duplicate)))
        }
    }
    @Test fun generatedNamesAndSuffixesFitTheLimitWithoutSplittingUnicode() {
        val longName = "x".repeat(128)
        val first = StorageRegistry.defaultName(longName)
        val second = StorageRegistry.defaultName(longName, listOf(first))
        assertEquals(StorageRegistry.MAX_NAME_LENGTH, first.length)
        assertTrue(second.endsWith(" (2)"))
        assertEquals(StorageRegistry.MAX_NAME_LENGTH, second.length)
        val unicode = StorageRegistry.defaultName("x".repeat(107) + "😀")
        assertFalse(unicode.last().isHighSurrogate())
        assertEquals("Storage for " + "x".repeat(107), unicode)
        assertEquals("Storage for Dev (3)", StorageRegistry.defaultName("Dev", listOf("Storage for Dev", "Storage for Dev (2)")))
    }
    @Test fun simultaneousProfileCreationReservesDistinctNamesAcrossRegistryInstances() {
        val files = temporary.newFolder()
        val start = java.util.concurrent.CountDownLatch(1)
        val profiles = listOf(profile(), profile()).map { it.copy(name = "Concurrent") }
        val futures = profiles.map { profile ->
            java.util.concurrent.CompletableFuture.runAsync {
                assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                StorageRegistry(files).transaction { save(profile) }
            }
        }
        start.countDown()
        futures.forEach { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
        val names = StorageRegistry(files).transaction { profiles.map { name(StorageRegistry.Key(it.runtime, it.storageId)) }.toSet() }
        assertEquals(setOf("Storage for Concurrent", "Storage for Concurrent (2)"), names)
    }
    @Test fun newStorageNameAndProfileSaveTogetherWithoutRenamingSharedStorage() {
        val files = temporary.newFolder()
        val registry = StorageRegistry(files)
        val saved = profile()
        val key = StorageRegistry.Key(saved.runtime, saved.storageId)
        registry.transaction { save(saved, "  Development  ") }
        val reopened = StorageRegistry(files)
        assertEquals(saved, reopened.transaction { profiles(emptyList()).single() })
        assertEquals("Development", reopened.transaction { name(key) })
        assertFalse(registry.root(key).exists()) // Naming never creates or clones environment files.
        val shared = saved.copy(id = "shared", name = "Sharing")
        assertTrue(runCatching { reopened.transaction { save(shared, "Replacement name") } }.isFailure)
        assertEquals("Development", reopened.transaction { name(key) })
        reopened.transaction { save(shared) }
        val invalid = profile()
        assertTrue(runCatching { reopened.transaction { save(invalid, "x".repeat(StorageRegistry.MAX_NAME_LENGTH + 1)) } }.isFailure)
        assertFalse(reopened.transaction { profiles(emptyList()).any { it.id == invalid.id } })
        assertFalse(reopened.transaction { keys().any { it.storageId == invalid.storageId } })
    }
    @Test fun storageNamesPersistIndependentlyOfProfilesAndAreRemovedOnDeletion() {
        val files = temporary.newFolder()
        val registry = StorageRegistry(files)
        val saved = profile()
        val snapshot = saved.resolve()
        val key = StorageRegistry.Key(saved.runtime, saved.storageId)
        registry.transaction { save(saved); rename(key, "  Work environment  ") }
        val reopened = StorageRegistry(files)
        assertEquals("Work environment", reopened.transaction { name(key) })
        reopened.transaction { save(saved.copy(name = "Edited profile")); deleteProfile(saved.id, false) }
        assertEquals("Work environment", reopened.transaction { name(key) })
        assertEquals(saved.storageId, snapshot.storageId)
        reopened.transaction { rename(key, ""); assertNull(name(key)); rename(key, "Unused environment") }
        assertTrue(runCatching { reopened.transaction { rename(key, "x".repeat(StorageRegistry.MAX_NAME_LENGTH + 1)) } }.isFailure)
        reopened.transaction { requireOrphan(key, emptyList()); delete(key) }
        assertNull(StorageRegistry(files).transaction { name(key) })
        assertTrue(runCatching { reopened.transaction { rename(key, "Stale editor") } }.isFailure)
        assertTrue(runCatching { reopened.transaction { rename(StorageRegistry.Key(saved.runtime, UUID.randomUUID().toString()), "Missing") } }.isFailure)
    }
    @Test fun legacyNamesAreScopedByRuntimeWithoutChangingIdentity() {
        val registry = StorageRegistry(temporary.newFolder())
        val defaults = listOf(profile().copy(storageId = "legacy"), profile(SessionRuntime.ROOTLESS).copy(storageId = "legacy"))
        val native = StorageRegistry.Key(SessionRuntime.NATIVE, "legacy")
        val rootless = StorageRegistry.Key(SessionRuntime.ROOTLESS, "legacy")
        registry.transaction {
            assertNull(name(native)) // Existing manifests need no migration for optional names.
            rename(native, "Native environment", defaults)
            rename(rootless, "Linux environment", defaults)
            assertEquals("Native environment", name(native))
            assertEquals("Linux environment", name(rootless))
            assertTrue(runCatching { requireOrphan(native, emptyList()) }.isFailure)
        }
    }
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
    @Test fun zeroBeforeInstallationDoesNotHideNewRootfsFiles() {
        val registry = StorageRegistry(temporary.newFolder())
        val key = StorageRegistry.Key(SessionRuntime.ROOTLESS, UUID.randomUUID().toString())
        assertEquals(StorageRegistry.Usage(0), registry.measureUsage(key))
        assertNull(registry.cachedUsage(key))
        val file = File(registry.root(key), "rootfs/usr/bin/shell").apply {
            parentFile!!.mkdirs(); writeText("installed shell")
        }
        assertEquals(StorageRegistry.Usage(file.length()), registry.measureUsage(key))
    }
    @Test fun deniedRootlessDescendantReportsReadableBytesWithoutChangingPermissions() {
        val registry = StorageRegistry(temporary.newFolder())
        val key = StorageRegistry.Key(SessionRuntime.ROOTLESS, UUID.randomUUID().toString())
        val root = registry.root(key).apply { mkdirs() }
        File(root, "rootfs/usr/bin/shell").apply { parentFile!!.mkdirs(); writeText("shell") }
        val denied = File(root, "rootfs/workspace").apply { mkdirs() }
        File(denied, "hidden").writeText("must not be counted or modified")
        val original = Files.getPosixFilePermissions(denied.toPath())
        try {
            Files.setPosixFilePermissions(denied.toPath(), emptySet())
            assertFalse("Run permission tests as an unprivileged user", denied.canRead())
            val usage = registry.measureUsage(key)
            assertEquals(StorageRegistry.Usage(5, incomplete = true), usage)
            assertEquals(usage, registry.cachedUsage(key))
            assertEquals(usage, StorageRegistry.Usage.parse(usage.json().toString()))
            assertEquals(emptySet<java.nio.file.attribute.PosixFilePermission>(), Files.getPosixFilePermissions(denied.toPath()))
        } finally {
            Files.setPosixFilePermissions(denied.toPath(), original)
        }
    }
    @Test fun inaccessibleStorageRootFailsInsteadOfReturningZero() {
        val registry = StorageRegistry(temporary.newFolder())
        val key = StorageRegistry.Key(SessionRuntime.ROOTLESS, UUID.randomUUID().toString())
        val root = registry.root(key).apply { mkdirs() }
        val original = Files.getPosixFilePermissions(root.toPath())
        try {
            Files.setPosixFilePermissions(root.toPath(), emptySet())
            assertTrue(runCatching { registry.measureUsage(key) }.exceptionOrNull() is java.nio.file.AccessDeniedException)
            assertNull(registry.cachedUsage(key))
        } finally {
            Files.setPosixFilePermissions(root.toPath(), original)
        }
    }
    @Test fun emptyDeniedDirectoryStillMarksMeasurementIncomplete() {
        val registry = StorageRegistry(temporary.newFolder())
        val key = StorageRegistry.Key(SessionRuntime.ROOTLESS, UUID.randomUUID().toString())
        val denied = File(registry.root(key), "rootfs/workspace").apply { mkdirs() }
        val original = Files.getPosixFilePermissions(denied.toPath())
        try {
            Files.setPosixFilePermissions(denied.toPath(), emptySet())
            assertEquals(StorageRegistry.Usage(0, incomplete = true), registry.measureUsage(key))
        } finally {
            Files.setPosixFilePermissions(denied.toPath(), original)
        }
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
