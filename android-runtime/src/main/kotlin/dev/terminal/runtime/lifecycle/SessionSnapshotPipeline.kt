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
    private val renamedTitles = mutableMapOf<String, String>()
    private val initialTitles = mutableMapOf<String, String>()
    private var revision = 0L
    private var current: SessionSnapshot? = null

    suspend fun refresh(client: SessionController): Result<SessionSnapshot> = mutex.withLock {
        refreshLocked(client)
    }

    /** Publishes service-owned launch state without querying or starting a runtime. */
    suspend fun republish(): SessionSnapshot = mutex.withLock {
        commit(current?.sessions.orEmpty(), force = true)
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

    suspend fun rename(sessionId: String, title: String): Result<SessionSnapshot> = mutex.withLock {
        runCatching {
            val snapshot = current ?: throw NoSuchElementException("Session not found")
            val session = snapshot.sessions.firstOrNull { it.id == sessionId }
                ?: throw NoSuchElementException("Session not found")
            val normalized = normalizedSessionTitle(title)
            if (session.title == normalized && sessionId in snapshot.explicitTitleIds) return@runCatching snapshot
            renamedTitles[sessionId] = normalized
            commit(
                snapshot.sessions.map { if (it.id == sessionId) it.copy(title = normalized) else it },
                force = true,
            )
        }
    }

    /** A launch/profile label is a fallback, never an explicit tab rename. */
    suspend fun setInitialTitle(sessionId: String, title: String): Result<SessionSnapshot> = mutex.withLock {
        runCatching {
            val snapshot = current ?: throw NoSuchElementException("Session not found")
            require(snapshot.sessions.any { it.id == sessionId }) { "Session not found" }
            val normalized = normalizedSessionTitle(title)
            initialTitles[sessionId] = normalized
            commit(snapshot.sessions.map {
                if (it.id == sessionId) it.copy(title = renamedTitles[sessionId] ?: normalized) else it
            })
        }
    }

    suspend fun clear(close: () -> Unit): SessionSnapshot = mutex.withLock {
        close()
        renamedTitles.clear()
        initialTitles.clear()
        commit(emptyList(), force = true)
    }

    private suspend fun refreshLocked(client: SessionController): Result<SessionSnapshot> =
        runCatching { client.list().getOrThrow() }.map { sessions ->
            val sessionIds = sessions.mapTo(mutableSetOf()) { it.id }
            renamedTitles.keys.retainAll(sessionIds)
            initialTitles.keys.retainAll(sessionIds)
            sessions.forEach { initialTitles.putIfAbsent(it.id, it.title) }
            commit(sessions.map { session ->
                (renamedTitles[session.id] ?: initialTitles[session.id])?.let { session.copy(title = it) } ?: session
            })
        }

    private fun commit(sessions: List<SessionDescriptor>, force: Boolean = false): SessionSnapshot {
        val previous = current
        if (!force && previous?.sessions == sessions && previous.initialTitles == initialTitles) return previous
        val explicitTitleIds = renamedTitles.keys.intersect(sessions.mapTo(mutableSetOf()) { it.id })
        return SessionSnapshot(++revision, sessions, explicitTitleIds, initialTitles.toMap()).also {
            current = it
            publish(it)
        }
    }
}

internal fun normalizedSessionTitle(value: String): String {
    val title = value.trim()
    require(title.isNotEmpty()) { "세션 이름을 입력하세요" }
    require(title.codePointCount(0, title.length) <= MAX_SESSION_TITLE_CODE_POINTS) {
        "세션 이름은 ${MAX_SESSION_TITLE_CODE_POINTS}자 이하여야 합니다"
    }
    require(title.encodeToByteArray().size <= MAX_SESSION_TITLE_UTF8_BYTES) { "세션 이름이 너무 깁니다" }
    require(title.none(Char::isISOControl)) { "세션 이름에 제어 문자를 사용할 수 없습니다" }
    return title
}

private const val MAX_SESSION_TITLE_CODE_POINTS = 64
private const val MAX_SESSION_TITLE_UTF8_BYTES = 256

internal data class SessionSnapshot(
    val revision: Long,
    val sessions: List<SessionDescriptor>,
    val explicitTitleIds: Set<String> = emptySet(),
    val initialTitles: Map<String, String> = emptyMap(),
)

internal data class SessionMutationResult<T>(
    val operation: Result<T>,
    val snapshot: Result<SessionSnapshot>?,
)
