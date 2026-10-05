package dev.terminal.runtime.rootless

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RootlessInstallationTest {
    @Test fun cancellationKeepsOwnershipThroughCleanupAndAllowsRetry() = runBlocking {
        val installation = RootlessInstallation()
        val cleaning = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val attempt = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                installation.run {
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) {
                            cleaning.complete(Unit)
                            releaseCleanup.await()
                        }
                    }
                }
            }
        }
        assertTrue(installation.active)
        assertTrue(installation.cancel())
        withTimeout(5000) { cleaning.await() }
        assertTrue(installation.active)
        assertFalse(installation.cancellable)
        assertFalse(installation.cancel())
        releaseCleanup.complete(Unit)
        assertTrue(withTimeout(5000) { attempt.await() }.exceptionOrNull() is RootlessInstallationCancelledException)
        assertFalse(installation.active)
        assertEquals(7, installation.run { 7 })
        assertFalse(installation.cancel())
    }

    @Test fun cancellationDoesNotStopAnotherEnvironmentOrItsCaller() = runBlocking {
        val cancelled = RootlessInstallation()
        val other = RootlessInstallation()
        val releaseOther = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { cancelled.run { awaitCancellation() } }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            other.run { releaseOther.await(); "ready" }
        }
        assertTrue(cancelled.cancel())
        assertTrue(withTimeout(5000) { first.await() }.isFailure)
        assertTrue(other.cancellable)
        assertFalse(second.isCompleted)
        releaseOther.complete(Unit)
        assertEquals("ready", withTimeout(5000) { second.await() })
        assertFalse(other.active)
    }
}
