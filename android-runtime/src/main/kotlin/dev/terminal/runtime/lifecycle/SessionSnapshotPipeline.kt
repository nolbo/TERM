package dev.terminal.runtime.lifecycle

import dev.terminal.protocol.SessionController
import dev.terminal.protocol.SessionDescriptor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes controller mutations with the authoritative snapshot they publish. */
internal class SessionSnapshotPipeline(
    private val publish: (SessionSnapshot) -> Unit,
) {
    private val mutex = Mutex()
    private var revision = 0L
    private var current: SessionSnapshot? = null

    suspend fun refresh(client: SessionController): Result<SessionSnapshot> = mutex.withLock {
        refreshLocked(client)
    }

    suspend fun <T> mutateAndRefresh(
        client: SessionController,
        mutation: suspend () -> Result<T>,
    ): SessionMutationResult<T> = mutex.withLock {
        val operation = runCatching { mutation().getOrThrow() }
        SessionMutationResult(
            operation = operation,
            snapshot = if (operation.isSuccess) refreshLocked(client) else null,
        )
    }

    suspend fun clear(close: () -> Unit): SessionSnapshot = mutex.withLock {
        close()
        commit(emptyList(), force = true)
    }

    private suspend fun refreshLocked(client: SessionController): Result<SessionSnapshot> =
        runCatching { client.list().getOrThrow() }.map(::commit)

    private fun commit(sessions: List<SessionDescriptor>, force: Boolean = false): SessionSnapshot {
        val previous = current
        if (!force && previous?.sessions == sessions) return previous
        return SessionSnapshot(++revision, sessions).also {
            current = it
            publish(it)
        }
    }
}

internal data class SessionSnapshot(
    val revision: Long,
    val sessions: List<SessionDescriptor>,
)

internal data class SessionMutationResult<T>(
    val operation: Result<T>,
    val snapshot: Result<SessionSnapshot>?,
)
