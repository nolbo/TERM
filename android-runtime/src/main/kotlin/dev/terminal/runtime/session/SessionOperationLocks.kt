package dev.terminal.runtime.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Geometry requests serialize per session without retaining locks for dead/idle sessions. */
internal class SessionOperationLocks {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withSession(id: String, action: suspend () -> T): T {
        val entry = synchronized(entries) { entries.getOrPut(id) { Entry() }.also { it.users++ } }
        try { return entry.mutex.withLock { action() } }
        finally { synchronized(entries) { if (--entry.users == 0) entries.remove(id) } }
    }
}
