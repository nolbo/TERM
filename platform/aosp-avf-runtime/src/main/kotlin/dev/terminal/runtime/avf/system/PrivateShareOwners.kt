package dev.terminal.runtime.avf.system

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/** Verifier/revocation drivers must join the original driver's I/O before releasing durable leases. */
internal object PrivateShareOwners {
    private val owners = ConcurrentHashMap<String, Closeable>()

    fun register(key: String, owner: Closeable) {
        check(owners.putIfAbsent(key, owner) == null) { "Private share owner has not stopped" }
    }

    fun releaseStopped(key: String) {
        owners[key]?.let { owner -> synchronized(owner) {
            owner.close() // Failure retains both this owner and the caller's persisted lease.
            owners.remove(key, owner)
        } }
    }
}
