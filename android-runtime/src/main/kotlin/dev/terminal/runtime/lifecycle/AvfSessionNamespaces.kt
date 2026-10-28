package dev.terminal.runtime.lifecycle

import dev.terminal.runtime.session.EnvironmentId
import java.util.UUID

/** Persisted before first use so a surviving VM keeps its host identities after :vmhost restart. */
internal class AvfSessionNamespaces(private val read: (String) -> String?,
    private val write: (String, String?) -> Unit) {
    @Synchronized fun get(id: EnvironmentId): String = read(id.value) ?: "avf-${id.value}-${UUID.randomUUID()}".also {
        write(id.value, it)
    }

    /** Caller has proved stop; a new owner must receive a different namespace. */
    @Synchronized fun retire(id: EnvironmentId) {
        if (read(id.value) != null) write(id.value, null)
    }
}
