package dev.terminal.runtime.avf.system

import dev.terminal.runtime.storage.AvfSharedDirectoryLeases
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AvfSharedStorageCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun revocationRecoversPersistedStorageUsersAndPreservesOrdinaryFolderVms() = runBlocking {
        val store = AvfSharedDirectoryLeases(temporary.newFolder())
        val folder = UUID.randomUUID().toString()
        store.record("storage-only", emptyList(), androidStorage = true)
        store.record("mixed", listOf(UUID.randomUUID().toString()), androidStorage = true)
        store.record("folders-only", listOf(folder))
        // Android 17 folder-only boots need the same grant, without exporting /sdcard.
        store.record("public-folders-only", listOf(UUID.randomUUID().toString()), "v-p-123", androidStorage = true)
        val stopped = mutableSetOf<String>()
        AvfSharedStorageCoordinator(store, { false }, { stopped.add(it); store.release(it); Unit }).revoke()
        assertEquals(setOf("storage-only", "mixed", "public-folders-only"), stopped)
        assertTrue(store.androidStorageOwners().isEmpty())
        assertEquals(listOf("folders-only"), store.owners(folder))
    }

    @Test fun aRestoredGrantCancelsPendingRevocationWithoutDiscardingTheMountRecord() = runBlocking {
        val store = AvfSharedDirectoryLeases(temporary.newFolder())
        store.record("vm", emptyList(), androidStorage = true)
        var stopped = false
        AvfSharedStorageCoordinator(store, { true }, { stopped = true }).revoke()
        assertFalse(stopped)
        assertEquals(listOf("vm"), store.androidStorageOwners())
    }

    @Test fun unsharedBootDoesNotRequireStoragePermissionOrStopOtherVms() = runBlocking {
        val store = AvfSharedDirectoryLeases(temporary.newFolder())
        store.record("old-vm", emptyList(), androidStorage = true)
        val coordinator = AvfSharedStorageCoordinator(store, { error("New boots need no storage grant") },
            { error("Unrelated VM must be preserved") })
        var ran = false
        coordinator.withoutShares { ran = true }
        assertTrue(ran)
        assertEquals(listOf("old-vm"), store.androidStorageOwners())
    }

    @Test fun legacyRevocationCannotStopANewUnsharedBoot() = runBlocking {
        val store = AvfSharedDirectoryLeases(temporary.newFolder())
        val bootLock = Mutex()
        var granted = true
        store.record("vm", emptyList(), androidStorage = true)
        val coordinator = AvfSharedStorageCoordinator(store, { granted }, { store.release(it); Unit }, bootLock)
        val inside = CompletableDeferred<Unit>()
        val continueBoot = CompletableDeferred<Unit>()
        val boot = async { coordinator.withoutShares {
            inside.complete(Unit)
            continueBoot.await()
            store.release("vm")
        } }
        inside.await()
        granted = false
        val revocation = async { coordinator.revoke() }
        continueBoot.complete(Unit)
        boot.await()
        revocation.await()
        assertTrue(store.androidStorageOwners().isEmpty())
    }

    @Test fun failedStopRetainsItsLeaseWhileOtherVmsAreRevokedAndNoLeasesNeedNoPlatformCall() = runBlocking {
        val store = AvfSharedDirectoryLeases(temporary.newFolder())
        val touched = mutableSetOf<String>()
        val coordinator = AvfSharedStorageCoordinator(store, { false }, {
            touched.add(it)
            if (it == "failed") error("Platform stop failed")
            store.release(it)
            Unit
        })
        coordinator.revoke()
        assertTrue(touched.isEmpty())
        store.record("failed", emptyList(), androidStorage = true)
        store.record("other", emptyList(), androidStorage = true)
        assertTrue(runCatching { coordinator.revoke() }.isFailure)
        assertEquals(setOf("failed", "other"), touched)
        assertEquals(listOf("failed"), store.androidStorageOwners())
    }
}
