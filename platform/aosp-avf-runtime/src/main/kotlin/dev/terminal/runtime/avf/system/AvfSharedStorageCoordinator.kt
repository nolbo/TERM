package dev.terminal.runtime.avf.system

import dev.terminal.runtime.storage.AvfSharedDirectoryLeases
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes share-free boot with cleanup of pre-upgrade storage-sharing VMs. */
internal class AvfSharedStorageCoordinator(
    private val leases: AvfSharedDirectoryLeases,
    private val accessGranted: () -> Boolean,
    private val stopAndRelease: suspend (String) -> Unit,
    private val bootLock: Mutex = GLOBAL_BOOT_LOCK,
) {
    suspend fun <T> withoutShares(operation: suspend () -> T): T = bootLock.withLock { operation() }

    suspend fun revoke(): Unit = bootLock.withLock {
        if (accessGranted()) return@withLock
        var failure: Throwable? = null
        for (instanceId in leases.androidStorageOwners()) {
            try { stopAndRelease(instanceId) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
        Unit
    }

    private companion object { val GLOBAL_BOOT_LOCK = Mutex() }
}
