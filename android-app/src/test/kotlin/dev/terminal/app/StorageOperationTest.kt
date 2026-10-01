package dev.terminal.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class StorageOperationTest {
    @Test fun cancellationOfInFlightStorageReadDoesNotUpdateErrorOrEntries() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var entries: String? = "loaded storage"
        var error: String? = null
        var finished = false
        val observation = launch {
            runStorageOperation {
                withContext(Dispatchers.IO) {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }.onSuccess { entries = it }
                .onFailure { entries = null; error = it.message }
            finished = true
        }
        started.await()
        observation.cancel(CancellationException("The coroutine scope left the composition"))
        observation.join()
        assertEquals("loaded storage", entries)
        assertNull(error)
        assertFalse(finished)
        assertTrue(observation.isCancelled)
    }

    @Test fun actualStorageFailureRemainsAvailableToErrorUi() = runBlocking {
        val failure = IllegalStateException("Storage unavailable")
        val result = runStorageOperation<String> { throw failure }
        assertSame(failure, result.exceptionOrNull())
    }

    @Test fun successfulStorageOperationReturnsValue() = runBlocking {
        assertEquals(42L, runStorageOperation { 42L }.getOrThrow())
    }
}
