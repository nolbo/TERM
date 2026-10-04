package dev.terminal.runtime.session

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EnvironmentRegistryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun profile(id: String = UUID.randomUUID().toString(), runtime: SessionRuntime = SessionRuntime.ROOTLESS,
        storage: String = UUID.randomUUID().toString()) = SessionProfile(id, "Development", runtime, "/bin/bash", storageId = storage)

    @Test fun sharedProfilesMigrateToOneEnvironmentAndRestartIsIdempotent() {
        val files = temporary.newFolder()
        val first = profile()
        val second = first.copy(id = "second", shell = "/bin/zsh", workingDirectory = "/home/droid/server")
        val registry = EnvironmentRegistry(files) { listOf(first, second).associate { it.id to it.resolve().toJson().toString() } }
        val migrated = registry.transaction { profiles() }
        assertEquals(1, migrated.map { it.environmentId }.distinct().size)
        assertNotNull(migrated.first().environmentId)
        val manifest = File(files, "storage-registry.json").readText()
        val reopened = EnvironmentRegistry(files).transaction { profiles() }
        assertEquals(migrated, reopened)
        assertEquals(manifest, File(files, "storage-registry.json").readText())
        assertFalse(File(files, "profile-storage").exists())
        registry.transaction {
            val environment = requireEnvironment(requireNotNull(migrated.first().environmentId))
            assertEquals(EnvironmentRuntime.ROOTLESS, environment.runtime)
            assertEquals(EnvironmentDistribution.DEBIAN, environment.distribution)
            assertEquals("rootfs", environment.runtimeMetadata.rootfsLayout)
        }
    }

    @Test fun distributionIsPartOfIdentityEvenWhenLegacyStorageIdsMatch() {
        val files = temporary.newFolder()
        val debian = profile(runtime = SessionRuntime.ROOTLESS, storage = "legacy")
        val arch = profile(runtime = SessionRuntime.ROOTLESS_ARCH, storage = "legacy")
        val registry = EnvironmentRegistry(files) { listOf(debian, arch).associate { it.id to it.resolve().toJson().toString() } }
        registry.transaction {
            val profiles = profiles()
            assertNotEquals(profiles[0].environmentId, profiles[1].environmentId)
            assertEquals(setOf(EnvironmentDistribution.DEBIAN, EnvironmentDistribution.ARCH), all().map { it.distribution }.toSet())
            assertEquals(2, all().map { registry.storage.root(it.storageKey) }.distinct().size)
        }
    }

    @Test fun editingEnvironmentReferenceOnlyChangesFutureLaunchesAndRenameKeepsIdentity() {
        val registry = EnvironmentRegistry(temporary.newFolder())
        val firstEnvironment = Environment.create(SessionRuntime.ROOTLESS, "Debian Development")
        val secondEnvironment = Environment.create(SessionRuntime.ROOTLESS_ARCH, "Arch Test")
        val first = registry.transaction { save(profile(), firstEnvironment) }
        val original = registry.transaction { resolve(first) }
        val edited = registry.transaction { save(first.copy(environmentId = null), secondEnvironment) }
        val future = registry.transaction { resolve(edited) }
        assertEquals(firstEnvironment.id, original.environmentId)
        assertEquals(firstEnvironment.storage.id, original.storageId)
        assertEquals(SessionRuntime.ROOTLESS, original.runtime)
        assertEquals(secondEnvironment.id, future.environmentId)
        assertEquals(SessionRuntime.ROOTLESS_ARCH, future.runtime)
        registry.transaction { rename(firstEnvironment.id, "Renamed Debian") }
        assertEquals(original, SessionLaunchSpec.fromJson(original.toJson().toString()))
        registry.transaction {
            assertEquals("Renamed Debian", requireEnvironment(firstEnvironment.id).name)
            assertEquals(firstEnvironment.id, original.environmentId)
            assertEquals(secondEnvironment.id, profiles().single().environmentId)
        }
    }

    @Test fun environmentIsSourceOfTruthForTransitionalProfileFields() {
        val registry = EnvironmentRegistry(temporary.newFolder())
        val environment = Environment.create(SessionRuntime.ROOTLESS_UBUNTU, "Ubuntu")
        val saved = registry.transaction { save(profile(), environment) }
        registry.transaction {
            val edited = save(saved.copy(runtime = SessionRuntime.NATIVE, storageId = UUID.randomUUID().toString()))
            assertEquals(SessionRuntime.ROOTLESS_UBUNTU, edited.runtime)
            assertEquals(environment.storage.id, edited.storageId)
            assertEquals(environment.id, resolve(edited).executionEnvironment?.id)
            assertEquals(1, all().size)
        }
    }

    @Test fun freshAndSharedDuplicatesHaveExplicitPersistencePolicy() {
        val registry = EnvironmentRegistry(temporary.newFolder())
        val saved = registry.transaction { save(profile(), Environment.create(SessionRuntime.ROOTLESS, "Debian")) }
        registry.transaction {
            val fresh = duplicate(saved)
            assertNull(fresh.environmentId)
            assertNotEquals(saved.storageId, fresh.storageId)
            val independent = save(fresh)
            assertNotEquals(saved.environmentId, independent.environmentId)
            val shared = save(duplicate(saved, shareEnvironment = true))
            assertEquals(saved.environmentId, shared.environmentId)
            assertEquals(2, all().size)
        }
    }

    @Test fun deletionBlocksReferencesSessionsUnknownStateAndStaleSaves() {
        val registry = EnvironmentRegistry(temporary.newFolder())
        val environment = Environment.create(SessionRuntime.NATIVE, "Native")
        val saved = registry.transaction { save(profile(), environment) }
        registry.transaction {
            assertTrue(runCatching { requireDeletable(environment.id) }.isFailure)
            deleteProfile(saved.id, false)
            assertTrue(runCatching { requireDeletable(environment.id, setOf(environment.id)) }.isFailure)
            assertTrue(runCatching { requireDeletable(environment.id, runtimeKnown = false) }.isFailure)
            delete(environment.id)
            assertNull(find(environment.id))
            assertTrue(runCatching { save(saved) }.isFailure)
            assertTrue(runCatching { resolveLaunch(saved.resolve(environment)) }.isFailure)
        }
        registry.transaction { assertTrue(all().isEmpty()) }
    }

    @Test fun deletingOneEnvironmentPreservesSiblingDataAndSymlinkTargets() {
        val files = temporary.newFolder()
        val registry = EnvironmentRegistry(files)
        val first = Environment.create(SessionRuntime.NATIVE, "First")
        val second = Environment.create(SessionRuntime.NATIVE, "Second")
        registry.transaction {
            val a = save(profile(), first)
            save(profile(), second)
            deleteProfile(a.id, false)
        }
        val root = registry.storage.root(first.storageKey).also { it.mkdirs() }
        val sibling = registry.storage.root(second.storageKey).also { it.mkdirs() }
        val data = File(sibling, "keep").apply { writeText("persistent") }
        Files.createSymbolicLink(File(root, "link").toPath(), sibling.toPath())
        registry.transaction { delete(first.id) }
        assertFalse(root.exists())
        assertEquals("persistent", data.readText())
        registry.transaction { assertEquals(listOf(second), all()) }
    }

    @Test fun orphanAndLegacyAdoptionOnlyAddMetadataAndPreserveAvfVmIdentity() {
        val files = temporary.newFolder()
        val runtimes = listOf(SessionRuntime.NATIVE, SessionRuntime.ROOTLESS, SessionRuntime.ROOTLESS_ARCH, SessionRuntime.AVF)
        val markers = runtimes.associateWith { runtime ->
            File(SessionStorage.legacyRoot(files, runtime).also { it.mkdirs() }, "keep").apply { writeText(runtime.wireName) }
        }
        val inodes = markers.mapValues { Files.getAttribute(it.value.toPath(), "unix:ino") }
        val orphan = StorageRegistry.Key(SessionRuntime.ROOTLESS_UBUNTU, UUID.randomUUID().toString())
        val orphanData = File(SessionStorage.root(files, orphan.runtime, orphan.storageId).also { it.mkdirs() }, "orphan").apply { writeText("keep") }
        val registry = EnvironmentRegistry(files)
        val beforeIds = registry.transaction { all().associate { it.sessionRuntime to it.id } }
        registry.transaction {
            runtimes.forEach { runtime ->
                val key = migrateLegacy(runtime)
                val environment = forStorage(key)
                assertEquals(beforeIds[runtime], environment.id)
                assertEquals(SessionStorage.legacyRoot(files, runtime), registry.storage.root(environment.storageKey))
                assertFalse(File(files, "profile-storage/${runtime.wireName}/${key.storageId}").exists())
                if (runtime == SessionRuntime.AVF) assertEquals("default", environment.runtimeMetadata.vmInstanceId)
            }
        }
        assertEquals("keep", orphanData.readText())
        markers.forEach { (runtime, marker) ->
            assertEquals(runtime.wireName, marker.readText())
            assertEquals(inodes[runtime], Files.getAttribute(marker.toPath(), "unix:ino"))
        }
        assertEquals(beforeIds, EnvironmentRegistry(files).transaction { all().associate { it.sessionRuntime to it.id } })
    }

    @Test fun v1RestorationRetainsLaunchSettingsAndV2RetainsRuntimeMetadataAfterRestart() {
        val files = temporary.newFolder()
        val old = profile(runtime = SessionRuntime.AVF, storage = "legacy").copy(workingDirectory = "/home/user", startupCommand = "echo original")
        val registry = EnvironmentRegistry(files) { mapOf(old.id to old.resolve().toJson().toString()) }
        val restored = registry.transaction { resolveLaunch(SessionLaunchSpec.fromJson(old.resolve().toJson().toString())) }
        assertEquals("/home/user", restored.workingDirectory) // live snapshots bypass saved-profile home migration
        assertEquals("echo original", restored.startupCommand)
        assertEquals("default", restored.executionEnvironment?.runtimeMetadata?.vmInstanceId)
        registry.transaction { migrateLegacy(SessionRuntime.AVF) }
        val afterAdoption = EnvironmentRegistry(files).transaction { resolveLaunch(restored) }
        assertEquals(restored.environmentId, afterAdoption.environmentId)
        assertEquals(restored.executionEnvironment?.runtimeMetadata, afterAdoption.executionEnvironment?.runtimeMetadata)
        assertEquals(afterAdoption, SessionLaunchSpec.fromJson(afterAdoption.toJson().toString()))
    }

    @Test fun concurrentRegistriesReserveNamesAndDoNotDuplicateSharedMigration() {
        val files = temporary.newFolder()
        val profile = profile()
        StorageRegistry(files).transaction { save(profile) }
        val results = java.util.concurrent.ConcurrentLinkedQueue<EnvironmentId>()
        val threads = List(6) { Thread {
            EnvironmentRegistry(files).transaction { results.add(requireNotNull(profiles().single().environmentId)) }
        }.also(Thread::start) }
        threads.forEach(Thread::join)
        assertEquals(6, results.size)
        assertEquals(1, results.distinct().size)
        EnvironmentRegistry(files).transaction { assertEquals(1, all().size) }
    }

    @Test fun newAvfEnvironmentCannotReuseTheLegacyVmAndFailedSaveLeavesNoMetadata() {
        val registry = EnvironmentRegistry(temporary.newFolder())
        val avf = Environment.create(SessionRuntime.AVF, "AVF", vmInstanceId = "default")
        assertTrue(runCatching { registry.transaction { save(profile(), avf) } }.isFailure)
        registry.transaction { assertTrue(all().isEmpty()); assertTrue(profiles().isEmpty()) }
        val invalid = profile().copy(shell = "relative")
        assertTrue(runCatching { registry.transaction { save(invalid, Environment.create(SessionRuntime.ROOTLESS, "Invalid")) } }.isFailure)
        registry.transaction { assertTrue(all().isEmpty()); assertTrue(profiles().isEmpty()) }
    }

    @Test fun aMaximumSizeLegacyProfileCanMigrateAndAdoptWithoutLosingVariables() {
        val files = temporary.newFolder()
        val base = profile(runtime = SessionRuntime.ROOTLESS, storage = "legacy").copy(environment = mapOf("DATA" to ""))
        val remaining = SessionLaunchSpec.LEGACY_MAX_BYTES - base.resolve().toJson().toString().toByteArray().size
        val old = base.copy(environment = mapOf("DATA" to "x".repeat(remaining)))
        assertEquals(SessionLaunchSpec.LEGACY_MAX_BYTES, old.resolve().toJson().toString().toByteArray().size)
        val registry = EnvironmentRegistry(files) { mapOf(old.id to old.resolve().toJson().toString()) }
        registry.transaction {
            migrateLegacy(SessionRuntime.ROOTLESS)
            val restored = resolve(profiles().single())
            assertEquals(old.environment, restored.environment)
            assertEquals(restored, SessionLaunchSpec.fromJson(restored.toJson().toString()))
            assertTrue(restored.toJson().toString().toByteArray().size <= SessionLaunchSpec.ENVIRONMENT_MAX_BYTES)
        }
    }

    @Test fun largeTombstoneHistoryMigratesOnlyLiveStorage() {
        val files = temporary.newFolder()
        val deleted = List(300) { StorageRegistry.Key(SessionRuntime.entries[it % SessionRuntime.entries.size], UUID.randomUUID().toString()) }
        val live = List(90) { StorageRegistry.Key(SessionRuntime.entries[it % SessionRuntime.entries.size], UUID.randomUUID().toString()) }
        StorageRegistry(files).transaction {
            state.put("deleted", org.json.JSONArray(deleted.map { it.json() }))
            state.put("storages", org.json.JSONArray((live + deleted).map { it.json() }))
            persist()
        }
        val migrated = EnvironmentRegistry(files).transaction { all().associateBy { it.storageKey } }
        assertEquals(live.toSet(), migrated.keys)
        assertEquals(migrated, EnvironmentRegistry(files).transaction { all().associateBy { it.storageKey } })
    }
}
