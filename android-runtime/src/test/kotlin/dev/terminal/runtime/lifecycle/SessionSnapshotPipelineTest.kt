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
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSnapshotPipelineTest {
    @Test fun configuredCustomTitleIsPresentInTheFirstSnapshotAndAllowsALaterRename() = runBlocking {
        val controller = DelayedListController().also { it.releaseFirstList.complete(Unit) }
        val published = mutableListOf<SessionSnapshot>()
        fun newPipeline() = SessionSnapshotPipeline(published::add) { id -> if (id == "native-a") "Workspace build" else null }
        val pipeline = newPipeline()
        val created = pipeline.refresh(controller).getOrThrow()
        assertEquals("Workspace build", published.first().sessions.first().title)
        assertEquals(setOf("native-a"), created.explicitTitleIds)
        assertEquals("native-a", created.initialTitles["native-a"])
        assertEquals("native-b", created.sessions.last().title)
        assertEquals(created, pipeline.refresh(controller).getOrThrow())
        pipeline.rename("native-a", "My new name").getOrThrow()
        assertEquals("My new name", pipeline.refresh(controller).getOrThrow().sessions.first().title)
        // Recovery from the persisted launch snapshot retains its assigned custom title.
        assertEquals("Workspace build", newPipeline().refresh(controller).getOrThrow().sessions.first().title)
    }

    @Test fun launchStatePublicationPreservesSessionsWithoutListingTheControllerAgain() = runBlocking {
        val controller = DelayedListController().also { it.releaseFirstList.complete(Unit) }
        val published = mutableListOf<SessionSnapshot>()
        val pipeline = SessionSnapshotPipeline(published::add)
        val empty = pipeline.republish()
        assertTrue(empty.sessions.isEmpty())
        assertEquals(0, controller.listCalls)
        pipeline.refresh(controller).getOrThrow()
        val renamed = pipeline.rename("native-a", "Build logs").getOrThrow()

        val pendingLaunch = pipeline.republish()

        assertEquals(1, controller.listCalls)
        assertEquals(renamed.revision + 1, pendingLaunch.revision)
        assertEquals(renamed.sessions, pendingLaunch.sessions)
        assertEquals(renamed.explicitTitleIds, pendingLaunch.explicitTitleIds)
        assertEquals(renamed.initialTitles, pendingLaunch.initialTitles)
        assertEquals(pendingLaunch, published.last())
    }

    @Test fun creationAndProfileTitlesAreFallbacksUntilAnExplicitRename() = runBlocking {
        val controller = DelayedListController().also { it.releaseFirstList.complete(Unit) }
        val pipeline = SessionSnapshotPipeline {}
        assertTrue(pipeline.refresh(controller).getOrThrow().explicitTitleIds.isEmpty())
        pipeline.setInitialTitle("native-a", "Profile").getOrThrow()
        val refreshed = pipeline.refresh(controller).getOrThrow()
        assertEquals("Profile", refreshed.sessions.first().title)
        assertTrue(refreshed.explicitTitleIds.isEmpty())
        // Even choosing the identical label explicitly must pin the tab.
        assertEquals(setOf("native-a"), pipeline.rename("native-a", "Profile").getOrThrow().explicitTitleIds)
        pipeline.setInitialTitle("native-a", "Other profile").getOrThrow()
        assertEquals("Profile", pipeline.refresh(controller).getOrThrow().sessions.first().title)
    }

    @Test fun renamedTitleIsPublishedAndSurvivesControllerRefresh() = runBlocking {
        val controller = DelayedListController().also { it.releaseFirstList.complete(Unit) }
        val published = mutableListOf<SessionSnapshot>()
        val pipeline = SessionSnapshotPipeline(published::add)
        pipeline.refresh(controller).getOrThrow()

        val renamed = pipeline.rename("native-a", "  Build logs  ").getOrThrow()
        val refreshed = pipeline.refresh(controller).getOrThrow()

        assertEquals("Build logs", renamed.sessions.first { it.id == "native-a" }.title)
        assertEquals(setOf("native-a"), renamed.explicitTitleIds)
        assertEquals("Build logs", refreshed.sessions.first { it.id == "native-a" }.title)
        assertEquals("native-a", refreshed.initialTitles["native-a"])
        assertEquals(setOf("native-a"), refreshed.explicitTitleIds)
        assertEquals("native-b", refreshed.sessions.first { it.id == "native-b" }.title)
        assertTrue(pipeline.rename("missing", "name").isFailure)
    }

    @Test fun sessionTitleValidationRejectsBlankLongAndControlText() {
        assertEquals("한글 작업", normalizedSessionTitle("  한글 작업  "))
        assertTrue(runCatching { normalizedSessionTitle(" ") }.isFailure)
        assertTrue(runCatching { normalizedSessionTitle("a".repeat(65)) }.isFailure)
        assertTrue(runCatching { normalizedSessionTitle("line\nbreak") }.isFailure)
    }

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
        var listCalls = 0
            private set
        private val sessions = linkedMapOf(
            "native-a" to descriptor("native-a", 1),
            "native-b" to descriptor("native-b", 2),
        )

        override suspend fun list(): Result<List<SessionDescriptor>> {
            listCalls++
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
