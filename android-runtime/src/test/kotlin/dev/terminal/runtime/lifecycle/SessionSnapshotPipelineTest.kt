package dev.terminal.runtime.lifecycle

import dev.terminal.protocol.SessionConnectionState
import dev.terminal.protocol.SessionController
import dev.terminal.protocol.SessionDescriptor
import dev.terminal.protocol.SessionEvent
import dev.terminal.protocol.SessionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionSnapshotPipelineTest {
    @Test fun delayedOlderRefreshCannotOverwritePostTerminationSnapshot() = runBlocking {
        val controller = DelayedListController()
        val published = mutableListOf<SessionSnapshot>()
        val pipeline = SessionSnapshotPipeline(published::add)

        val olderRefresh = async { pipeline.refresh(controller).getOrThrow() }
        controller.firstListStarted.await()
        val termination = async {
            pipeline.mutateAndRefresh(controller) { controller.terminate("native-a", 9) }
        }
        controller.releaseFirstList.complete(Unit)

        olderRefresh.await()
        val result = termination.await()

        result.operation.getOrThrow()
        result.snapshot?.getOrThrow()
        assertEquals(listOf(1L, 2L), published.map { it.revision })
        assertEquals(listOf("native-a", "native-b"), published.first().sessions.map { it.id })
        assertEquals(listOf("native-b"), published.last().sessions.map { it.id })
    }

    private class DelayedListController : SessionController {
        override val events = MutableSharedFlow<SessionEvent>()
        override val connectionState = MutableStateFlow(SessionConnectionState.CONNECTED)
        val firstListStarted = CompletableDeferred<Unit>()
        val releaseFirstList = CompletableDeferred<Unit>()
        private var firstList = true
        private val sessions = linkedMapOf(
            "native-a" to descriptor("native-a", 1),
            "native-b" to descriptor("native-b", 2),
        )

        override suspend fun list(): Result<List<SessionDescriptor>> {
            val snapshot = sessions.values.toList()
            if (firstList) {
                firstList = false
                firstListStarted.complete(Unit)
                releaseFirstList.await()
            }
            return Result.success(snapshot)
        }

        override suspend fun terminate(sessionId: String, signal: Int): Result<Unit> =
            if (sessions.remove(sessionId) != null) Result.success(Unit)
            else Result.failure(NoSuchElementException("Session not found"))

        override suspend fun create(shell: String, cwd: String, env: Map<String, String>, rows: UShort, cols: UShort) =
            Result.failure<SessionDescriptor>(UnsupportedOperationException())
        override suspend fun attach(sessionId: String) = Result.failure<SessionDescriptor>(UnsupportedOperationException())
        override suspend fun detach(sessionId: String) = Result.failure<Unit>(UnsupportedOperationException())
        override suspend fun resize(sessionId: String, rows: UShort, cols: UShort) = Result.failure<SessionDescriptor>(UnsupportedOperationException())
        override suspend fun write(sessionId: String, bytes: ByteArray) = Result.failure<Int>(UnsupportedOperationException())
        override fun close() = Unit
    }

    private companion object {
        fun descriptor(id: String, createdAt: Long) =
            SessionDescriptor(id, id, "/bin/sh", "/", createdAt, 24u, 80u, SessionStatus.RUNNING)
    }
}
