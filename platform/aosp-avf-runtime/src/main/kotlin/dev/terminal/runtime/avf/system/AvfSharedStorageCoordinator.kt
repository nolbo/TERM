package dev.terminal.runtime.avf.system

import dev.terminal.runtime.storage.AvfSharedDirectoryLeases
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All drivers in :vmhost serialize export setup/run with persisted-VM revocation. */
internal class AvfSharedStorageCoordinator(
    private val leases: AvfSharedDirectoryLeases,
    private val accessGranted: () -> Boolean,
    private val stopAndRelease: suspend (String) -> Unit,
    private val bootLock: Mutex = GLOBAL_BOOT_LOCK,
) {
    suspend fun <T> boot(instanceId: String, operation: suspend (Boolean) -> T): T = bootLock.withLock {
        val granted = accessGranted()
        if (!granted && instanceId in leases.androidStorageOwners()) stopAndRelease(instanceId)
        operation(granted)
    }

    fun requirePermission() = check(accessGranted()) { "AVF 공유 저장소의 모든 파일 접근 권한이 해제되었습니다. Android 설정에서 권한을 허용한 뒤 VM을 다시 시작하세요." }

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
