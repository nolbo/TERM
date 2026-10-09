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

    suspend fun <T> withStorage(runtime: SessionRuntime, storageId: String = "legacy", block: suspend () -> T): T =
        withStorage({ runtime to storageId }, { it }) { block() }

    /** Resolve identities after admission so adoption cannot race resolution or session creation. */
    suspend fun <S, T> withStorage(resolve: () -> S, key: (S) -> Pair<SessionRuntime, String>, block: suspend (S) -> T): T {
        admission.withLock {
            synchronized(monitor) {
                if (active++ == 0) drained = CompletableDeferred()
            }
        }
        try {
            val resolved = resolve()
            return locks.computeIfAbsent(key(resolved)) { Mutex() }.withLock { block(resolved) }
        } finally {
            synchronized(monitor) { if (--active == 0) drained.complete(Unit) }
        }
    }

    suspend fun <T> withAll(block: suspend () -> T): T = admission.withLock {
        synchronized(monitor) { drained }.await()
        block()
    }

    /** One admission for all legacy Workspace users; no nested admission/global-migration deadlock. */
    suspend fun <T> withStorages(resolve: () -> List<Pair<SessionRuntime, String>>, block: suspend () -> T): T {
        admission.withLock {
            synchronized(monitor) { if (active++ == 0) drained = CompletableDeferred() }
        }
        try {
            val selected = resolve().distinct().sortedWith(compareBy({ it.first.wireName }, { it.second }))
                .map { locks.computeIfAbsent(it) { Mutex() } }
            suspend fun take(index: Int): T = if (index == selected.size) block()
                else selected[index].withLock { take(index + 1) }
            return take(0)
        } finally { synchronized(monitor) { if (--active == 0) drained.complete(Unit) } }
    }

    /** Opportunistic maintenance must not queue behind an installation and close admission. */
    suspend fun tryWithAll(block: suspend () -> Unit): Boolean {
        if (!admission.tryLock()) return false
        try {
            if (synchronized(monitor) { active != 0 }) return false
            block()
            return true
        } finally {
            admission.unlock()
        }
    }
}
