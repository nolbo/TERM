package dev.terminal.runtime.storage

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.UUID

class AvfSharedDirectoryLeasesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun internalStorageBootsAreRecoverableEvenWithoutConnectedFoldersAndLegacyRecordsRemainValid() {
        val files = temporary.newFolder()
        val store = AvfSharedDirectoryLeases(files)
        val folder = UUID.randomUUID().toString()
        store.record("storage-only", emptyList(), "v-storage", androidStorage = true)
        store.record("mixed", listOf(folder), "v-mixed", androidStorage = true)
        store.record("folders-only", listOf(folder))
        val legacy = File(files, "avf-share-leases").listFiles()!!.single { it.readText().contains("folders-only") }
        val json = org.json.JSONObject(legacy.readText()).apply { remove("androidStorage") }
        legacy.writeText(json.toString())
        val restarted = AvfSharedDirectoryLeases(files)
        assertEquals(setOf("storage-only", "mixed"), restarted.androidStorageOwners().toSet())
        assertEquals(setOf("mixed", "folders-only"), restarted.owners(folder).toSet())
        assertEquals("v-storage", restarted.release("storage-only"))
        assertEquals(listOf("mixed"), restarted.androidStorageOwners())
        assertTrue(runCatching { store.record("empty", emptyList()) }.isFailure)
    }

    @Test fun liveVmUsageSurvivesOwnerRestartAndEnvironmentDisconnection() {
        val files = temporary.newFolder()
        val folderId = UUID.randomUUID().toString()
        val another = UUID.randomUUID().toString()
        AvfSharedDirectoryLeases(files).record("vm-one", listOf(folderId), "v-first-boot")
        val restarted = AvfSharedDirectoryLeases(files)
        assertEquals(listOf("vm-one"), restarted.owners(folderId))
        restarted.record("vm-two", listOf(folderId, another))
        assertEquals(setOf("vm-one", "vm-two"), restarted.owners(folderId).toSet())
        assertEquals("v-first-boot", restarted.release("vm-one"))
        assertEquals(listOf("vm-two"), restarted.owners(folderId))
        assertEquals(listOf("vm-two"), restarted.owners(another))
    }

    @Test fun nextStoppedBootReplacesOnlyItsOwnSnapshot() {
        val files = temporary.newFolder()
        val old = UUID.randomUUID().toString()
        val next = UUID.randomUUID().toString()
        val store = AvfSharedDirectoryLeases(files)
        store.record("vm", listOf(old))
        store.record("vm", listOf(next))
        assertTrue(store.owners(old).isEmpty())
        assertEquals(listOf("vm"), store.owners(next))
    }

    @Test fun inaccessiblePublicArtifactsRetainTheLeaseUntilCleanupSucceeds() {
        val store = AvfSharedDirectoryLeases(temporary.newFolder())
        val folder = UUID.randomUUID().toString()
        store.record("vm", listOf(folder), "v-p-123", androidStorage = true)
        assertTrue(runCatching { store.release("vm") { error("Permission revoked") } }.isFailure)
        assertEquals(listOf("vm"), store.owners(folder))
        assertEquals(listOf("vm"), store.androidStorageOwners())
        var cleaned: String? = null
        assertEquals("v-p-123", store.release("vm") { cleaned = it })
        assertEquals("v-p-123", cleaned)
        assertTrue(store.owners(folder).isEmpty())
    }

    @Test fun noAvfUsageRequiresNoPlatformAccessAndUnsafeRecordsFailClosed() {
        val files = temporary.newFolder()
        val store = AvfSharedDirectoryLeases(files)
        val folderId = UUID.randomUUID().toString()
        assertTrue(store.owners(folderId).isEmpty())
        assertTrue(files.listFiles()!!.isEmpty())
        assertTrue(runCatching { store.record("vm", listOf(folderId), "../../private") }.isFailure)
        val outside = temporary.newFolder()
        Files.createSymbolicLink(File(files, "avf-share-leases").toPath(), outside.toPath())
        assertTrue(runCatching { store.record("vm", listOf(folderId)) }.isFailure)
        assertTrue(runCatching { store.owners(folderId) }.isFailure)
        assertTrue(outside.listFiles()!!.isEmpty())
    }
}
