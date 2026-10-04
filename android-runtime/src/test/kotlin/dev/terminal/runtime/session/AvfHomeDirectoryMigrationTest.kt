package dev.terminal.runtime.session

import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AvfHomeDirectoryMigrationTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun profile(runtime: SessionRuntime = SessionRuntime.AVF, cwd: String = "/home/user") =
        SessionProfile(UUID.randomUUID().toString(), "Work", runtime, "/bin/bash",
            workingDirectory = cwd, storageId = UUID.randomUUID().toString(),
            environment = mapOf("TERM" to "xterm-256color"), startupCommand = "printf ready")

    private fun SessionProfile.encode() = resolve().toJson().toString()

    @Test fun storedAvfDefaultsMigrateWithoutChangingCustomProfilesStorageOrLiveSnapshots() {
        val files = temporary.newFolder()
        val builtIn = profile().copy(id = SessionProfile.builtInId(SessionRuntime.AVF))
        val independent = profile()
        val custom = profile(cwd = "/workspace")
        val native = profile(SessionRuntime.NATIVE)
        val rootless = profile(SessionRuntime.ROOTLESS)
        val saved = listOf(builtIn, independent, custom, native, rootless)
        val tombstone = SessionProfile.builtInId(SessionRuntime.ROOTLESS_UBUNTU)
        val overrides = saved.associate { it.id to it.encode() } + (tombstone to "")
        val keys = saved.map { StorageRegistry.Key(it.runtime, it.storageId) }
        val names = JSONObject(keys.associate { "${it.runtime.wireName}:${it.storageId}" to "Storage ${it.storageId}" })
        val manifest = File(files, "storage-registry.json").apply {
            writeText(JSONObject().put("profiles", JSONObject(overrides))
                .put("storages", JSONArray(keys.map { it.json() })).put("deleted", JSONArray())
                .put("storageNames", names).toString())
        }
        val liveSnapshot = builtIn.resolve()
        val liveJson = liveSnapshot.toJson().toString()
        val disk = File(files, "vm/default/home.img").apply { parentFile!!.mkdirs(); writeText("existing guest data") }

        val registry = StorageRegistry(files)
        assertEquals(saved.map { if (it.runtime == SessionRuntime.AVF && it.workingDirectory == "/home/user")
            it.copy(workingDirectory = "/home/droid") else it }.toSet(), registry.transaction { profiles(emptyList()).toSet() })
        registry.transaction {
            assertEquals("", overrides().getValue(tombstone))
            listOf(custom, native, rootless).forEach { assertEquals(it.encode(), overrides().getValue(it.id)) }
            keys.forEach { assertEquals("Storage ${it.storageId}", name(it)) }
        }
        assertEquals("existing guest data", disk.readText())
        assertEquals("/home/user", liveSnapshot.workingDirectory)
        assertEquals(liveJson, liveSnapshot.toJson().toString())
        assertEquals(liveSnapshot, SessionLaunchSpec.fromJson(liveJson))
        val persisted = JSONObject(manifest.readText())
        assertTrue(persisted.getBoolean("avfHomeDirectoryMigrated"))
        assertEquals("/home/droid", SessionLaunchSpec.fromJson(persisted.getJSONObject("profiles").getString(builtIn.id)).workingDirectory)
        assertEquals(keys.map { it.json().toString() }, List(persisted.getJSONArray("storages").length()) {
            persisted.getJSONArray("storages").getJSONObject(it).toString() })
    }

    @Test fun legacyPreferenceImportAlsoMigratesTheAvfDefaultDirectory() {
        val files = temporary.newFolder()
        val old = profile().copy(storageId = "legacy")
        val registry = StorageRegistry(files) { mapOf(old.id to old.encode(), "builtin:NATIVE" to "") }
        assertEquals(old.copy(workingDirectory = "/home/droid"), registry.transaction { profiles(emptyList()).single() })
        val reopened = StorageRegistry(files)
        assertEquals(old.copy(workingDirectory = "/home/droid"), reopened.transaction { profiles(emptyList()).single() })
        assertEquals("", reopened.transaction { overrides().getValue("builtin:NATIVE") })
    }

    @Test fun migrationRunsOnceAndRespectsLaterExplicitUserDirectoryChoices() {
        val files = temporary.newFolder()
        val old = profile()
        val registry = StorageRegistry(files) { mapOf(old.id to old.encode()) }
        assertEquals("/home/droid", registry.transaction { profiles(emptyList()).single().workingDirectory })
        registry.transaction { save(old) }
        val manifest = File(files, "storage-registry.json")
        val before = manifest.readText()
        assertEquals(old, StorageRegistry(files).transaction { profiles(emptyList()).single() })
        assertEquals(before, manifest.readText())
    }
}
