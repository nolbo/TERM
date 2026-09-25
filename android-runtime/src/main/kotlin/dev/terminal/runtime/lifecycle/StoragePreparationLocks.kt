package dev.terminal.runtime.lifecycle

import dev.terminal.runtime.session.SessionRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Independent storage preparation, with an exclusive barrier for shared-tree migrations. */
internal class StoragePreparationLocks {
    private val admission = Mutex()
    private val monitor = Any()
    private val locks = java.util.concurrent.ConcurrentHashMap<Pair<SessionRuntime, String>, Mutex>()
    private var active = 0
    private var drained = CompletableDeferred<Unit>().apply { complete(Unit) }

    fun isLocked(runtime: SessionRuntime, storageId: String = "legacy") =
        locks[runtime to storageId]?.isLocked == true

    suspend fun <T> withStorage(runtime: SessionRuntime, storageId: String = "legacy", block: suspend () -> T): T {
        admission.withLock {
            synchronized(monitor) {
                if (active++ == 0) drained = CompletableDeferred()
            }
        }
        try {
            return locks.computeIfAbsent(runtime to storageId) { Mutex() }.withLock { block() }
        } finally {
            synchronized(monitor) { if (--active == 0) drained.complete(Unit) }
        }
    }

    suspend fun <T> withAll(block: suspend () -> T): T = admission.withLock {
        synchronized(monitor) { drained }.await()
        block()
    }
}
